package core;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;
import org.apache.commons.io.FileUtils;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.codehaus.plexus.logging.Logger;

@Named
@Singleton
public class InferInstaller {

    private static final URI INFER_DOWNLOAD_URI = URI.create("https://github.com/facebook/infer/releases/download/v1.2.0/infer-linux-x86_64-v1.2.0.tar.xz");
    private static final String GENERIC_INFER_INSTALLATION_ERROR = "Error occurred when attempting to install Infer";
    private static final String DOWNLOAD_INFER_INSTALLATION_ERROR = "Failed to download Infer from " + INFER_DOWNLOAD_URI + ". HTTP status ";
    private static final int POSIX_EXECUTE_PERMISSIONS = 73;
    private static final Set<PosixFilePermission> EXECUTE_PERMISSIONS = EnumSet.of(PosixFilePermission.OWNER_EXECUTE,
        PosixFilePermission.GROUP_EXECUTE,
        PosixFilePermission.OTHERS_EXECUTE);

    private final Logger logger;

    private final HttpClientFactory httpClientFactory;

    @Inject
    public InferInstaller(Logger logger, HttpClientFactory httpClientFactory) {
        this.logger = logger;
        this.httpClientFactory = httpClientFactory;
    }

    public Path tryInstallInfer() throws MojoExecutionException, MojoFailureException {
        URL url = null;
        Path inferDownloadTmpDir = null;

        try {
            logger.info("Attempting to download Infer");

            url = INFER_DOWNLOAD_URI.toURL();

            Path inferTarballFileName = Path.of(url.getPath()).getFileName();
            inferDownloadTmpDir = Files.createTempDirectory("infer-download-");
            Path inferTarballTmpDirFilePath = inferDownloadTmpDir.resolve(inferTarballFileName);

            HttpRequest request = HttpRequest.newBuilder()
                .uri(INFER_DOWNLOAD_URI)
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build();

            logger.info("Downloading Infer from: " + request.uri());

            HttpResponse<Path> response = httpClientFactory.getHttpClient().send(
                request,
                HttpResponse.BodyHandlers.ofFile(inferTarballTmpDirFilePath)
            );

            if (response.statusCode() != 200) {
                logger.error(DOWNLOAD_INFER_INSTALLATION_ERROR + response.statusCode());
                throw new MojoExecutionException(DOWNLOAD_INFER_INSTALLATION_ERROR + response.statusCode());
            }

            logger.info("Successfully downloaded to tmp dir: " + inferTarballTmpDirFilePath);

            Path userHomeDownloadsPath = Path.of(System.getProperty("user.home"), "Downloads");
            Files.createDirectories(userHomeDownloadsPath);

            untarInferTarball(inferTarballTmpDirFilePath, userHomeDownloadsPath);

            Path inferExe = userHomeDownloadsPath
                .resolve("infer-linux-x86_64-v1.2.0")
                .resolve("bin")
                .resolve("infer");
            logger.info("Resolved Infer executable: " + inferExe);

            cleanupInferTarballTmpDir(inferDownloadTmpDir);

            return inferExe;
        } catch (IOException | InterruptedException | MojoFailureException | MojoExecutionException e) {
            if (inferDownloadTmpDir != null) {
                cleanupInferTarballTmpDir(inferDownloadTmpDir); //ensure resourcese are cleaned up even if exception is thrown
            }

            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }

            if (e instanceof MojoFailureException) {
                logger.warn("Failure occurred when attempting to install Infer. Continuing in potentially unstable state.", e);
                throw new MojoFailureException("Failure occurred when attempting to install Infer", e);
            }

            logger.error("Unable to get Infer from URL:" + url + "! Cannot continue Infer check.", e);
            throw new MojoExecutionException(GENERIC_INFER_INSTALLATION_ERROR, e);
        }
    }

    private void untarInferTarball(Path inferTarballTmpDirFilePath, Path userHomeDownloadsPath)
        throws MojoExecutionException, MojoFailureException, IOException {
        logger.info("Extracting " + inferTarballTmpDirFilePath + " to " + userHomeDownloadsPath);

        try (var fileInputStream = new FileInputStream(inferTarballTmpDirFilePath.toFile());
            var bufferedInputStream = new BufferedInputStream(fileInputStream);
            var xzCompressorInputStream = new XZCompressorInputStream(bufferedInputStream);
            var tarArchiveInputStream = new TarArchiveInputStream(xzCompressorInputStream)) {

            TarArchiveEntry tarArchiveEntry;

            while ((tarArchiveEntry = tarArchiveInputStream.getNextEntry()) != null) {
                // Normalize and prevent ZipSlip
                Path target = userHomeDownloadsPath.resolve(tarArchiveEntry.getName()).normalize();
                if (!target.startsWith(userHomeDownloadsPath)) {
                    throw new MojoExecutionException("Potential ZipSlip detected from Infer tarball. Blocked suspicious entry: " + tarArchiveEntry.getName());
                }

                if (tarArchiveEntry.isDirectory()) {
                    Files.createDirectories(target);
                    continue;
                }

                // Ensure parent dirs exist
                Files.createDirectories(target.getParent());

                if (tarArchiveEntry.isSymbolicLink()) {
                    handleSymlink(tarArchiveEntry, target);
                } else if (tarArchiveEntry.isLink()) {
                    handleHardLink(userHomeDownloadsPath, tarArchiveEntry, target);
                } else {
                    handleRegularFile(tarArchiveInputStream, target, tarArchiveEntry);
                }
            }
        } catch (IOException | MojoFailureException | MojoExecutionException e) {
            if (e instanceof MojoFailureException) {
                logger.warn("A failure occurred when untarring the Infer tarball. "
                    + "This could be due to corruption in extracting the files.", e);
                throw new MojoFailureException("Failure occurred when untarring Infer tarball", e);
            }

            logger.error("An error occurred when untarring the Infer tarball.", e);
            throw new MojoExecutionException("Error occurred when untarring Infer tarball", e);
        }
    }

    private void handleSymlink(TarArchiveEntry tarArchiveEntry, Path target) throws MojoFailureException, IOException {
        Path linkTarget = Path.of(tarArchiveEntry.getLinkName());

        // Remove existing if any (to allow re-extraction)
        try {
            Files.deleteIfExists(target);
        } catch (IOException e) {
            logger.warn("Symlink removal failed on: " + target);
            throw new MojoFailureException("Failure removing symlink in Infer tarball" + e);
        }

        Files.createSymbolicLink(target, linkTarget);
    }

    private void handleHardLink(Path userHomeDownloadsPath, TarArchiveEntry tarArchiveEntry, Path target)
        throws IOException {
        // Hard link: create as a copy of the link target if present
        Path linkTarget = userHomeDownloadsPath.resolve(tarArchiveEntry.getLinkName()).normalize();

        if (Files.exists(linkTarget, LinkOption.NOFOLLOW_LINKS)) {
            Files.copy(linkTarget, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
        } else {
            logger.warn("Hard link target does not exist yet: " + tarArchiveEntry.getLinkName());
        }
    }

    private void handleRegularFile(TarArchiveInputStream tarArchiveInputStream, Path target,
        TarArchiveEntry tarArchiveEntry) throws IOException {
        Files.copy(tarArchiveInputStream, target, StandardCopyOption.REPLACE_EXISTING);

        // Restore executable perms if mode has any exec bit
        final int mode = tarArchiveEntry.getMode();
        final boolean isNonExecutableBit = (mode & POSIX_EXECUTE_PERMISSIONS) == 0;

        if (isNonExecutableBit) {
            return; //no executable perms to restore
        }

        final Set<PosixFilePermission> perms = Files.getPosixFilePermissions(target);
        perms.addAll(EXECUTE_PERMISSIONS);
        Files.setPosixFilePermissions(target, perms);
    }

    private void cleanupInferTarballTmpDir(Path tmpDir) throws MojoFailureException {
        try {
            FileUtils.deleteDirectory(tmpDir.toFile());
            logger.info("Successfully cleaned up tmp dir used to download Infer: " + tmpDir);
        } catch (IOException e) {
            logger.warn("Failure occurred during cleanup of tmp dir used to download Infer. The tmp dir will need to be cleaned up manually: " + tmpDir, e);
            throw new MojoFailureException("Failed to cleanup tmp dir used to download Infer: " + tmpDir, e);
        }
    }
}
