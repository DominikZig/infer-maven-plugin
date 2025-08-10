package core;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
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
import org.codehaus.plexus.logging.Logger;

@Named
@Singleton
public class InferInstaller {

    @Inject
    private Logger logger;

    private static final URI INFER_DOWNLOAD_URI = URI.create("https://github.com/facebook/infer/releases/download/v1.2.0/infer-linux-x86_64-v1.2.0.tar.xz");
    private static final int POSIX_EXECUTE_PERMISSIONS = 73;

    public InferInstaller() {
    }

    public Path installInfer() throws MojoExecutionException {
        URL url = null;

        try {
            logger.info("Attempting to download Infer");

            url = INFER_DOWNLOAD_URI.toURL();

            Path fileNamePath = Path.of(url.getPath()).getFileName();
            Path tempDir = Files.createTempDirectory("infer-download-");
            Path downloadedFilePath = tempDir.resolve(fileNamePath);

            HttpRequest request = HttpRequest.newBuilder()
                .uri(INFER_DOWNLOAD_URI)
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build();

            logger.info("Downloading Infer from: " + request.uri());

            HttpResponse<Path> response = HttpClientFactory.getHttpClient().send(
                request,
                HttpResponse.BodyHandlers.ofFile(downloadedFilePath)
            );

            if (response.statusCode() != 200) {
                throw new IOException("Failed to download Infer from " + INFER_DOWNLOAD_URI + ". HTTP status " + response.statusCode());
            }

            logger.info("Downloaded to temp: " + downloadedFilePath);

            Path extractRoot = Path.of(System.getProperty("user.home"), "Downloads");
            Files.createDirectories(extractRoot);
            untar(downloadedFilePath, extractRoot);

            Path inferExe = extractRoot
                .resolve("infer-linux-x86_64-v1.2.0")
                .resolve("bin")
                .resolve("infer");
            logger.info("Found infer executable: " + inferExe);

            cleanupInferTarball(tempDir);

            return inferExe;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            final String errMsg = String.format("Unable to get Infer from URL: %s! Cannot continue Infer check.", url);
            logger.error(errMsg, e);
            throw new MojoExecutionException(errMsg, e);
        }
    }

    private void untar(Path downloadedFilePath, Path extractRoot) throws IOException {
        logger.info("Extracting " + downloadedFilePath + " to " + extractRoot);
        try (InputStream fis = new FileInputStream(downloadedFilePath.toFile());
            BufferedInputStream bis = new BufferedInputStream(fis);
            XZCompressorInputStream xzIn = new XZCompressorInputStream(bis);
            TarArchiveInputStream tarIn = new TarArchiveInputStream(xzIn)) {

            TarArchiveEntry ae;
            while ((ae = tarIn.getNextEntry()) != null) {

                // Normalize and prevent ZipSlip
                Path target = extractRoot.resolve(ae.getName()).normalize();
                if (!target.startsWith(extractRoot)) {
                    throw new IOException("Blocked suspicious entry: " + ae.getName());
                }

                if (ae.isDirectory()) {
                    Files.createDirectories(target);
                    continue;
                }

                // Ensure parent dirs exist
                Files.createDirectories(target.getParent());

                if (ae.isSymbolicLink()) {
                    Path linkTarget = Path.of(ae.getLinkName());
                    // Remove existing if any (to allow re-extraction)
                    try {
                        Files.deleteIfExists(target);
                    } catch (IOException ignore) {}
                    Files.createSymbolicLink(target, linkTarget);
                } else if (ae.isLink()) {
                    // Hard link: create as a copy of the link target if present
                    Path linkTarget = extractRoot.resolve(ae.getLinkName()).normalize();
                    if (!Files.exists(linkTarget, LinkOption.NOFOLLOW_LINKS)) {
                        logger.warn("Hard link target does not exist yet: " + ae.getLinkName());
                    } else {
                        Files.copy(linkTarget, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                    }
                } else {
                    // Regular file
                    Files.copy(tarIn, target, StandardCopyOption.REPLACE_EXISTING);

                    // Restore executable perms if mode has any exec bit
                    int mode = ae.getMode();
                    boolean anyExec = (mode & POSIX_EXECUTE_PERMISSIONS) != 0;
                    if (anyExec) {
                        Set<PosixFilePermission> perms = Files.getPosixFilePermissions(target);
                        Set<PosixFilePermission> withExec = EnumSet.copyOf(perms);
                        withExec.add(PosixFilePermission.OWNER_EXECUTE);
                        withExec.add(PosixFilePermission.GROUP_EXECUTE);
                        withExec.add(PosixFilePermission.OTHERS_EXECUTE);
                        Files.setPosixFilePermissions(target, withExec);
                    }
                }
            }
        } catch (IOException e) {
            logger.error("Error untarring Infer: " + e.getMessage(), e);
            throw e;
        }
    }

    private void cleanupInferTarball(Path tempDir) throws MojoExecutionException {
        try {
            FileUtils.deleteDirectory(tempDir.toFile());
            logger.info("Cleaned up temp dir: " + tempDir);
        } catch (IOException cleanupEx) {
            throw new MojoExecutionException("Failed to cleanup temp dir: " + tempDir, cleanupEx);
        }
    }
}
