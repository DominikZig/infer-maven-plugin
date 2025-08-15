package core;

import java.io.File;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarConstants;
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream;
import org.apache.commons.io.FileUtils;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.codehaus.plexus.logging.Logger;
import org.junit.jupiter.api.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.jupiter.MockitoExtension;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class InferInstallerTest {

    @Mock
    private Logger logger;

    @Mock
    private HttpClientFactory httpClientFactory;

    @Mock
    private HttpClient httpClient;

    private InferInstaller installer;

    private String originalUserHome;

    @BeforeEach
    void setUp() {
        installer = new InferInstaller(logger, httpClientFactory);
        originalUserHome = System.getProperty("user.home");
    }

    @AfterEach
    void tearDown() {
        System.setProperty("user.home", originalUserHome);
    }

    @DisplayName("""
        Given file available to download\s
        And directory structure correctly setup\s
        When plugin tries to install Infer\s
        Then successfully installs, downloads and extracts Infer and then\s
        successfully cleans up tmp dirs
       """)
    @Test
    void tryInstallInferSuccessful(@TempDir Path dummyHome) throws Exception {
        System.setProperty("user.home", dummyHome.toString());

        // Create minimal Infer tar.xz file bytes
        String rootDir = "infer-linux-x86_64-v1.2.0";
        final byte[] tarBytes = createTarXz("hello".getBytes(StandardCharsets.UTF_8), rootDir);

        when(httpClientFactory.getHttpClient()).thenReturn(httpClient);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
            .thenAnswer(inv -> successfulInferUrlHttpResponse(inv, tarBytes));

        Path inferExe = installer.tryInstallInfer();

        Path expectedInferPath = dummyHome.resolve("Downloads")
            .resolve(rootDir)
            .resolve("bin")
            .resolve("infer");

        assertThat(inferExe).isEqualTo(expectedInferPath);
        assertThat(Files.exists(inferExe)).isTrue();
        assertThat(Files.exists(expectedInferPath)).isTrue();
        assertTmpDirCleanup();
    }

    @DisplayName("""
        Given file is NOT available to download\s
        And directory structure correctly setup\s
        When plugin tries to install Infer\s
        Then throws MojoExecutionException with fail to download message\s
        And successfully cleans up tmp dirs
       """)
    @Test
    void tryInstallInferNon200HttpStatusThrowsMojoExecutionException(@TempDir Path dummyHome) throws Exception {
        System.setProperty("user.home", dummyHome.toString());

        HttpResponse<Path> badHttpResponse = mock(HttpResponse.class);
        when(badHttpResponse.statusCode()).thenReturn(404);
        when(httpClientFactory.getHttpClient()).thenReturn(httpClient);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(badHttpResponse);

        var mojoExecutionException = assertThrows(MojoExecutionException.class, installer::tryInstallInfer);
        assertThat(mojoExecutionException.getMessage()).isEqualTo("Error occurred when attempting to install Infer");
        assertThat(mojoExecutionException).hasCauseThat().isInstanceOf(MojoExecutionException.class);
        assertThat(mojoExecutionException)
            .hasCauseThat()
            .hasMessageThat()
            .isEqualTo("Failed to download Infer from https://github.com/facebook/infer/releases/download/v1.2.0/infer-linux-x86_64-v1.2.0.tar.xz. HTTP status 404");

        var errorLogCaptor = ArgumentCaptor.forClass(String.class);
        verify(logger, times(1)).error(errorLogCaptor.capture());

        assertThat(errorLogCaptor.getAllValues())
            .containsExactly("Failed to download Infer from https://github.com/facebook/infer/releases/download/v1.2.0/infer-linux-x86_64-v1.2.0.tar.xz. HTTP status 404");
        assertTmpDirCleanup();
    }

    @DisplayName("""
        Given file is available to download\s
        And Http connection is interrupted\s
        And directory structure correctly setup\s
        When plugin tries to install Infer\s
        Then throws MojoExecutionException with Generic message\s
        And successfully cleans up tmp dirs
       """)
    @Test
    void tryInstallInferInterruptedException(@TempDir Path dummyHome) throws Exception {
        System.setProperty("user.home", dummyHome.toString());

        when(httpClientFactory.getHttpClient()).thenReturn(httpClient);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenThrow(new InterruptedException("interrupted"));

        assertThat(Thread.currentThread().isInterrupted()).isFalse();

        var mojoExecutionException = assertThrows(MojoExecutionException.class, installer::tryInstallInfer);

        assertThat(mojoExecutionException.getMessage()).isEqualTo("Error occurred when attempting to install Infer");
        assertThat(mojoExecutionException).hasCauseThat().isInstanceOf(InterruptedException.class);
        assertThat(mojoExecutionException)
            .hasCauseThat()
            .hasMessageThat()
            .isEqualTo("interrupted");
        assertThat(Thread.currentThread().isInterrupted()).isTrue();

        var errorLogCaptor = ArgumentCaptor.forClass(String.class);
        var exCaptor = ArgumentCaptor.forClass(Throwable.class);
        verify(logger, times(1)).error(errorLogCaptor.capture(), exCaptor.capture());

        assertThat(errorLogCaptor.getAllValues())
            .containsExactly("Unable to get Infer from URL:https://github.com/facebook/infer/releases/download/v1.2.0/infer-linux-x86_64-v1.2.0.tar.xz! Cannot continue Infer check.");
        assertTmpDirCleanup();
    }

    @DisplayName("""
        Given file is available to download\s
        And directory structure contains a ZipSlip\s
        When plugin tries to install Infer\s
        Then throws MojoExecutionException with Untarring message\s
        And successfully cleans up tmp dirs
       """)
    @Test
    void tryInstallInferUntarZipSlip(@TempDir Path dummyHome) throws Exception {
        System.setProperty("user.home", dummyHome.toString());

        byte[] tarBytes = createTarXzWithZipSlip("bad".getBytes(StandardCharsets.UTF_8));

        when(httpClientFactory.getHttpClient()).thenReturn(httpClient);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
            .thenAnswer(inv -> successfulInferUrlHttpResponse(inv, tarBytes));

        var mojoExecutionException = assertThrows(MojoExecutionException.class, installer::tryInstallInfer);

        assertThat(mojoExecutionException.getMessage()).isEqualTo("Error occurred when attempting to install Infer");
        assertThat(mojoExecutionException).hasCauseThat().isInstanceOf(MojoExecutionException.class);
        assertThat(mojoExecutionException)
            .hasCauseThat()
            .hasMessageThat()
            .isEqualTo("Error occurred when untarring Infer tarball");

        var errorLogCaptor = ArgumentCaptor.forClass(String.class);
        var exCaptor = ArgumentCaptor.forClass(Throwable.class);
        verify(logger, times(2)).error(errorLogCaptor.capture(), exCaptor.capture());

        List<String> expectedErrorLogs = List.of("An error occurred when untarring the Infer tarball.",
            "Unable to get Infer from URL:https://github.com/facebook/infer/releases/download/v1.2.0/infer-linux-x86_64-v1.2.0.tar.xz! Cannot continue Infer check.");
        assertThat(errorLogCaptor.getAllValues()).containsExactlyElementsIn(expectedErrorLogs);
        assertTmpDirCleanup();
    }

    @DisplayName("""
         Given file is available to download\s
         And directory structure correctly setup\s
         When plugin tries to install Infer\s
         Then successfully installs, downloads and extracts Infer\s 
         WITHOUT Exec Permissions and then\s
         And successfully cleans up tmp dirs
       """)
    @Test
    void tryInstallInferExtractsWithoutExecPerms(@TempDir Path dummyHome) throws Exception {
        System.setProperty("user.home", dummyHome.toString());
        String rootDir = "infer-linux-x86_64-v1.2.0";
        byte[] tarBytes = createTarXz("content".getBytes(StandardCharsets.UTF_8), rootDir);

        when(httpClientFactory.getHttpClient()).thenReturn(httpClient);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
            .thenAnswer(inv -> successfulInferUrlHttpResponse(inv, tarBytes));

        Path inferExe = installer.tryInstallInfer();
        // no exception => no POSIX permission changes attempted (file mode didn't set exec bits)

        Path expectedInferPath = dummyHome.resolve("Downloads")
            .resolve(rootDir)
            .resolve("bin")
            .resolve("infer");

        assertThat(inferExe).isEqualTo(expectedInferPath);
        assertThat(Files.exists(inferExe)).isTrue();
        assertThat(Files.exists(expectedInferPath)).isTrue();

        String fileText = Files.readString(expectedInferPath);
        assertThat(fileText).isEqualTo("content");
        assertTmpDirCleanup();
    }

    @DisplayName("""
         Given file is available to download\s
         And directory structure is setup with a hardlink entry\s
         When plugin tries to install Infer\s
         Then successfully installs, downloads and extracts Infer\s 
         And successfully handles the hardlink entry\s
         And successfully cleans up tmp dirs
       """)
    @Test
    void tryInstallInferHandlesHardLinkExistingTarget(@TempDir Path dummyHome) throws Exception {
        System.setProperty("user.home", dummyHome.toString());
        String rootDir = "infer-linux-x86_64-v1.2.0";
        byte[] tarBytes = createTarXzWithHardLinkExistingTarget("orig".getBytes(StandardCharsets.UTF_8),
            rootDir
        );

        when(httpClientFactory.getHttpClient()).thenReturn(httpClient);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
            .thenAnswer(inv -> successfulInferUrlHttpResponse(inv, tarBytes));

        Path inferExe = installer.tryInstallInfer();

        Path downloads = dummyHome.resolve("Downloads");
        Path hardLinkPath = downloads.resolve(rootDir).resolve("bin").resolve("hardlink.txt");
        assertThat(Files.exists(inferExe)).isTrue();
        assertThat(Files.exists(hardLinkPath)).isTrue();
        assertThat(Files.readString(hardLinkPath)).isEqualTo("orig");

        // Should not warn about missing target in this scenario
        verify(logger, never()).warn("Hard link target does not exist yet: " + rootDir + "/bin/original.txt");
        assertTmpDirCleanup();
    }

    @DisplayName("""
         Given file is available to download\s
         And directory structure is setup with a hardlink entry which does not exist yet\s
         When plugin tries to install Infer\s
         Then successfully installs, downloads and extracts Infer\s 
         And successfully warns about the missing hardlink entry\s
         And successfully cleans up tmp dirs
       """)
    @Test
    void tryInstallInferHandlesHardLinkMissingTargetWarns(@TempDir Path dummyHome) throws Exception {
        System.setProperty("user.home", dummyHome.toString());
        String rootDir = "infer-linux-x86_64-v1.2.0";
        String missingLinkTarget = rootDir + "/bin/missing.txt";
        byte[] tarBytes = createTarXzWithHardLinkMissingTarget("content".getBytes(StandardCharsets.UTF_8),
            rootDir,
            missingLinkTarget);

        when(httpClientFactory.getHttpClient()).thenReturn(httpClient);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
            .thenAnswer(inv -> successfulInferUrlHttpResponse(inv, tarBytes));

        Path inferExe = installer.tryInstallInfer();
        assertThat(Files.exists(inferExe)).isTrue();

        // Verify specific warn on missing hard link target
        verify(logger, atLeastOnce()).warn("Hard link target does not exist yet: infer-linux-x86_64-v1.2.0/infer-linux-x86_64-v1.2.0/bin/missing.txt");
        assertTmpDirCleanup();
    }

    @DisplayName("""
         Given file is available to download\s
         And directory structure is setup with a symlink entry\s
         And a symlink deletion failure during untar occurs\s
         When plugin tries to install Infer\s
         Then throws MojoFailureException with warning message\s
         And successfully cleans up tmp dirs
       """)
    @Test
    void tryInstallInferSymlinkDeletionFailureLogsWarnsAndThrows(@TempDir Path dummyHome) throws Exception {
        System.setProperty("user.home", dummyHome.toString());
        String rootDir = "infer-linux-x86_64-v1.2.0";
        byte[] tarBytes = createTarXzWithSymlinkOverNonEmptyDir(rootDir);

        when(httpClientFactory.getHttpClient()).thenReturn(httpClient);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
            .thenAnswer(inv -> successfulInferUrlHttpResponse(inv, tarBytes));

        var mojoFailureException = assertThrows(MojoFailureException.class, installer::tryInstallInfer);

        assertThat(mojoFailureException).hasMessageThat().isEqualTo("Failure occurred when attempting to install Infer");
        assertThat(mojoFailureException).hasCauseThat().hasMessageThat().isEqualTo("Failure occurred when untarring Infer tarball");

        var warnMsgCaptor = ArgumentCaptor.forClass(String.class);
        var warnExCaptor = ArgumentCaptor.forClass(Throwable.class);

        // One warn without exception from handleSymlink
        verify(logger, times(1)).warn(ArgumentMatchers.startsWith("Symlink removal failed on: "));

        // Two warns with exception: from untar and outer tryInstallInfer
        verify(logger, times(2)).warn(warnMsgCaptor.capture(), warnExCaptor.capture());
        assertThat(warnMsgCaptor.getAllValues()).contains("A failure occurred when untarring the Infer tarball. This could be due to corruption in extracting the files.");
        assertThat(warnMsgCaptor.getAllValues()).contains("Failure occurred when attempting to install Infer. Continuing in potentially unstable state.");
        assertTmpDirCleanup();
    }

    //Note: this test is to kill 'removed call to logging' mutation reported by PITest
    @DisplayName("""
         Given file is available to download\s
         And directory structure correctly setup\s
         When plugin tries to install Infer\s
         Then successfully installs, downloads and extracts Infer\s 
         And successfully logs all info logs\s
         And successfully cleans up tmp dirs
       """)
    @Test
    void tryInstallInferLogsInfoMessages(@TempDir Path dummyHome) throws Exception {
        System.setProperty("user.home", dummyHome.toString());
        String rootDir = "infer-linux-x86_64-v1.2.0";
        byte[] tarBytes = createTarXz("ok".getBytes(StandardCharsets.UTF_8), rootDir);

        when(httpClientFactory.getHttpClient()).thenReturn(httpClient);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
            .thenAnswer(inv -> successfulInferUrlHttpResponse(inv, tarBytes));

        Path inferExe = installer.tryInstallInfer();
        assertThat(Files.exists(inferExe)).isTrue();

        var infoCaptor = ArgumentCaptor.forClass(String.class);
        verify(logger, atLeastOnce()).info(infoCaptor.capture());
        var infos = infoCaptor.getAllValues();
        assertThat(infos.stream().anyMatch(s -> s.equals("Attempting to download Infer"))).isTrue();
        assertThat(infos.stream().anyMatch(s -> s.startsWith("Downloading Infer from:"))).isTrue();
        assertThat(infos.stream().anyMatch(s -> s.startsWith("Successfully downloaded to tmp dir:"))).isTrue();
        assertThat(infos.stream().anyMatch(s -> s.startsWith("Extracting "))).isTrue();
        assertThat(infos.stream().anyMatch(s -> s.startsWith("Resolved Infer executable: "))).isTrue();
        assertTmpDirCleanup();
    }

    @DisplayName("""
        Given file available to download\s
        And directory structure correctly setup\s
        When plugin tries to install Infer\s
        Then cleanup fails with IOException, logs warn and throws MojoFailureException\s
        And test manually removes the tmp dir afterwards
       """)
    @Test
    void tryInstallInferIOExceptionOnCleanup(@TempDir Path dummyHome) throws Exception {
        System.setProperty("user.home", dummyHome.toString());

        String rootDir = "infer-linux-x86_64-v1.2.0";
        final byte[] tarBytes = createTarXz("hello".getBytes(StandardCharsets.UTF_8), rootDir);

        when(httpClientFactory.getHttpClient()).thenReturn(httpClient);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
            .thenAnswer(inv -> successfulInferUrlHttpResponse(inv, tarBytes));

        // Capture the directory passed to FileUtils.deleteDirectory so we can clean it manually later
        AtomicReference<Path> tmpDirRef = new AtomicReference<>();

        try (MockedStatic<FileUtils> filesUtils = mockStatic(FileUtils.class)) {
            filesUtils.when(() -> FileUtils.deleteDirectory(any(File.class)))
                .thenAnswer(inv -> {
                    File dir = inv.getArgument(0);
                    tmpDirRef.compareAndSet(null, dir.toPath());
                    throw new IOException("Something went wrong during cleanup");
                });

            var mojoFailureException = assertThrows(MojoFailureException.class, installer::tryInstallInfer);

            assertThat(mojoFailureException.getMessage()).contains("Failed to cleanup tmp dir used to download Infer:");
            assertThat(mojoFailureException).hasCauseThat().isInstanceOf(IOException.class);
            assertThat(mojoFailureException)
                .hasCauseThat()
                .hasMessageThat()
                .isEqualTo("Something went wrong during cleanup");

            var warnLogCaptor = ArgumentCaptor.forClass(String.class);
            var exCaptor = ArgumentCaptor.forClass(Throwable.class);
            verify(logger, atLeastOnce()).warn(warnLogCaptor.capture(), exCaptor.capture());

            assertThat(warnLogCaptor.getAllValues().getFirst())
                .contains("Failure occurred during cleanup of tmp dir used to download Infer. The tmp dir will need to be cleaned up manually:");

            // Ensure we actually captured the tmp dir path
            assertThat(tmpDirRef.get()).isNotNull();
            // At this point the cleanup failed, so the directory should still exist
            assertThat(Files.exists(tmpDirRef.get())).isTrue();
        }

        // call the real FileUtils to delete the tmp dir since prod code did not clean up
        FileUtils.deleteDirectory(tmpDirRef.get().toFile());
        assertThat(Files.notExists(tmpDirRef.get())).isTrue();
    }

    @DisplayName("""
        Given file available to download\s
        And directory structure is setup with NO exec bits on files\s
        When plugin tries to install Infer\s
        Then does not alter POSIX permissions when mode has NO exec bits\s
       """)
    @Test
    void tryInstallInferDoesNotSetExecPermsWhenModeHasNoExecBits(@TempDir Path dummyHome) throws Exception {
        System.setProperty("user.home", dummyHome.toString());
        String rootDir = "infer-linux-x86_64-v1.2.0";

        // This archive sets mode 0644 (no exec bits) for bin/infer (same as createTarXz)
        byte[] tarBytes = createTarXz("noexec".getBytes(StandardCharsets.UTF_8), rootDir);

        when(httpClientFactory.getHttpClient()).thenReturn(httpClient);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
            .thenAnswer(inv -> successfulInferUrlHttpResponse(inv, tarBytes));

        // Let Files static methods call real methods except for the two POSIX methods we verify
        try (MockedStatic<Files> filesMock = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            // We don't stub getPosixFilePermissions/setPosixFilePermissions; we just want to verify they're not called
            Path inferExe = installer.tryInstallInfer();
            assertThat(Files.exists(inferExe)).isTrue();

            // Verify POSIX permission APIs were NOT called
            filesMock.verify(() -> Files.getPosixFilePermissions(any(Path.class)), never());
            filesMock.verify(() -> Files.setPosixFilePermissions(any(Path.class), any(Set.class)), never());
        }

        assertTmpDirCleanup();
    }

    @DisplayName("""
        Given file available to download\s
        And directory structure is setup and HAS exec bits on files\s
        When plugin tries to install Infer\s
        Then restores exec POSIX permissions when mode HAS exec bits\s
       """)
    @Test
    void tryInstallInferRestoresExecPermsWhenModeHasExecBits(@TempDir Path dummyHome) throws Exception {
        System.setProperty("user.home", dummyHome.toString());
        String rootDir = "infer-linux-x86_64-v1.2.0";

        // Archive where the infer file has 0755 (exec bits present)
        byte[] tarBytes = createTarXzWithExecBits("#!/bin/sh\necho ok".getBytes(StandardCharsets.UTF_8), rootDir);

        when(httpClientFactory.getHttpClient()).thenReturn(httpClient);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
            .thenAnswer(inv -> successfulInferUrlHttpResponse(inv, tarBytes));

        // Prepare a baseline POSIX permissions set without execute bits; the code should add exec bits to it
        Set<PosixFilePermission> baseline = EnumSet.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.OTHERS_READ
        );

        // Use static mocking to control only the POSIX get/set, and capture what gets set
        try (MockedStatic<Files> filesMock = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            ArgumentCaptor<Set<PosixFilePermission>> permsCaptor = ArgumentCaptor.forClass(Set.class);

            filesMock.when(() -> Files.getPosixFilePermissions(any(Path.class))).thenReturn(EnumSet.copyOf(baseline));

            Path inferExe = installer.tryInstallInfer();
            assertThat(Files.exists(inferExe)).isTrue();

            // Verify getPosixFilePermissions was called for the extracted file
            filesMock.verify(() -> Files.getPosixFilePermissions(inferExe));

            // Verify setPosixFilePermissions was called and the set contains all exec bits
            filesMock.verify(() -> Files.setPosixFilePermissions(eq(inferExe), permsCaptor.capture()));

            Set<PosixFilePermission> finalPerms = permsCaptor.getValue();
            assertThat(finalPerms).containsAtLeast(
                PosixFilePermission.OWNER_EXECUTE,
                PosixFilePermission.GROUP_EXECUTE,
                PosixFilePermission.OTHERS_EXECUTE
            );
            // Also ensure we didn't drop baseline read/write bits
            assertThat(finalPerms).containsAtLeastElementsIn(baseline);
        }

        assertTmpDirCleanup();
    }

    private static HttpResponse<Path> successfulInferUrlHttpResponse(InvocationOnMock inv, byte[] tarBytes) {
        HttpResponse.BodyHandler<Path> handler = inv.getArgument(1);
        HttpResponse.ResponseInfo info = new HttpResponse.ResponseInfo() {
            @Override
            public int statusCode() { return 200; }
            @Override
            public HttpHeaders headers() { return HttpHeaders.of(Map.of(), (k, v) -> true); }
            @Override
            public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
        };

        HttpResponse.BodySubscriber<Path> subscriber = handler.apply(info);
        // simulate subscription and data flow
        subscriber.onSubscribe(new Flow.Subscription() {
            @Override public void request(long n) {}
            @Override public void cancel() {}
        });
        subscriber.onNext(List.of(ByteBuffer.wrap(tarBytes)));
        subscriber.onComplete();

        HttpResponse<Path> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        return response;
    }

    private static byte[] createTarXz(byte[] fileContent, String rootDirName) throws IOException {
        return buildTarXz(rootDirName, (root, tar) -> {
            TarArchiveEntry fileEntry = createFile(fileContent, root + "bin/infer");
            tar.putArchiveEntry(fileEntry);
            tar.write(fileContent);
            tar.closeArchiveEntry();
        });
    }

    private static byte[] createTarXzWithZipSlip(byte[] fileContent) throws IOException {
        return buildTarXz("root", (root, tar) -> {
            TarArchiveEntry fileEntry = createFile(fileContent, "../evil.txt"); // ZipSlip attempt
            tar.putArchiveEntry(fileEntry);
            tar.write(fileContent);
            tar.closeArchiveEntry();
        });
    }

    private static byte[] createTarXzWithHardLinkExistingTarget(byte[] originalContent, String rootDirName) throws IOException {
        return buildTarXz(rootDirName, (root, tar) -> {
            // original file
            TarArchiveEntry original = createFile(originalContent, root + "bin/original.txt");
            tar.putArchiveEntry(original);
            tar.write(originalContent);
            tar.closeArchiveEntry();

            // hard link entry referencing original
            TarArchiveEntry hardLink = new TarArchiveEntry(root + "bin/hardlink.txt", TarConstants.LF_LINK);
            hardLink.setLinkName(root + "bin/original.txt");
            hardLink.setMode(420);
            hardLink.setSize(0);
            tar.putArchiveEntry(hardLink);
            tar.closeArchiveEntry();

            // include infer executable file so installer returns path that exists
            byte[] inferContent = "#!/bin/sh\necho infer".getBytes(StandardCharsets.UTF_8);
            TarArchiveEntry infer = createFile(inferContent, root + "bin/infer");
            tar.putArchiveEntry(infer);
            tar.write(inferContent);
            tar.closeArchiveEntry();
        });
    }

    private static byte[] createTarXzWithHardLinkMissingTarget(byte[] inferContent, String rootDirName,
        String missingTargetRelativePath) throws IOException {
        return buildTarXz(rootDirName, (root, tar) -> {
            // hard link that points to missing target
            TarArchiveEntry hardLink = new TarArchiveEntry(root + "bin/hardlink-missing.txt", TarConstants.LF_LINK);
            hardLink.setLinkName(root + missingTargetRelativePath);
            hardLink.setMode(420);
            hardLink.setSize(0);
            tar.putArchiveEntry(hardLink);
            tar.closeArchiveEntry();

            // include infer file for success
            TarArchiveEntry infer = createFile(inferContent, root + "bin/infer");
            tar.putArchiveEntry(infer);
            tar.write(inferContent);
            tar.closeArchiveEntry();
        });
    }

    private static byte[] createTarXzWithSymlinkOverNonEmptyDir(String rootDirName) throws IOException {
        return buildTarXz(rootDirName, (root, tar) -> {
            // create a non-empty directory that we will try to replace with a symlink of same name
            String dir = root + "linkdir/";
            TarArchiveEntry dirEntry = new TarArchiveEntry(dir);
            dirEntry.setMode(493);
            tar.putArchiveEntry(dirEntry);
            tar.closeArchiveEntry();

            // file inside the directory
            byte[] inner = "x".getBytes(StandardCharsets.UTF_8);
            TarArchiveEntry innerFile = createFile(inner, dir + "inner.txt");
            tar.putArchiveEntry(innerFile);
            tar.write(inner);
            tar.closeArchiveEntry();

            // symlink with same path as directory (without trailing slash) -> delete will fail
            TarArchiveEntry symlink = new TarArchiveEntry(root + "linkdir", TarConstants.LF_SYMLINK);
            symlink.setLinkName("target-symlink");
            symlink.setMode(493);
            symlink.setSize(0);
            tar.putArchiveEntry(symlink);
            tar.closeArchiveEntry();
        });
    }

    private static byte[] createTarXzWithExecBits(byte[] fileContent, String rootDirName) throws IOException {
        return buildTarXz(rootDirName, (root, tar) -> {
            TarArchiveEntry fileEntry = new TarArchiveEntry(root + "bin/infer");
            fileEntry.setMode(493); // 0755 decimal - has 0755 (exec) mode
            fileEntry.setSize(fileContent.length);
            tar.putArchiveEntry(fileEntry);
            tar.write(fileContent);
            tar.closeArchiveEntry();
        });
    }

    @FunctionalInterface
    private interface TarContent {
        void write(String root, TarArchiveOutputStream tar) throws IOException;
    }

    private static byte[] buildTarXz(String rootDirName, TarContent content) throws IOException {
        var byteArrayOutputStream = new ByteArrayOutputStream();
        try (var xzCompressorOutputStream = new XZCompressorOutputStream(byteArrayOutputStream);
            var tar = new TarArchiveOutputStream(xzCompressorOutputStream)
        ) {
            String root = createRoot(rootDirName, tar);
            content.write(root, tar);
            tar.finish();
        }
        return byteArrayOutputStream.toByteArray();
    }

    private static String createRoot(String rootDirName, TarArchiveOutputStream tarArchiveOutputStream)
        throws IOException {
        tarArchiveOutputStream.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);

        // Directories
        String root = rootDirName + "/";
        String binDir = root + "bin/";

        // Root dir entry
        TarArchiveEntry rootEntry = new TarArchiveEntry(root);
        rootEntry.setMode(493);
        tarArchiveOutputStream.putArchiveEntry(rootEntry);
        tarArchiveOutputStream.closeArchiveEntry();

        // Bin dir entry
        TarArchiveEntry binEntry = new TarArchiveEntry(binDir);
        binEntry.setMode(493);
        tarArchiveOutputStream.putArchiveEntry(binEntry);
        tarArchiveOutputStream.closeArchiveEntry();
        return root;
    }

    private static TarArchiveEntry createFile(byte[] fileContent, String filePath) {
        TarArchiveEntry fileEntry = new TarArchiveEntry(filePath);
        int mode = 420;
        fileEntry.setMode(mode);
        fileEntry.setSize(fileContent.length);
        return fileEntry;
    }

    private void assertTmpDirCleanup() {
        // Verify cleanup occurred by inspecting info logs and checking the directory was deleted
        var infoCaptor = ArgumentCaptor.forClass(String.class);
        verify(logger, atLeastOnce()).info(infoCaptor.capture());
        String cleanedMsgPrefix = "Successfully cleaned up tmp dir used to download Infer: ";

        assertThat(infoCaptor.getAllValues().stream()
            .anyMatch(s -> s.startsWith(cleanedMsgPrefix)))
            .isTrue();

        // Ensure every cleaned tmp dir mentioned in logs was actually removed
        assertThat(infoCaptor.getAllValues().stream()
            .filter(s -> s.startsWith(cleanedMsgPrefix))
            .map(s -> s.substring(cleanedMsgPrefix.length()).trim())
            .map(Path::of)
            .allMatch(Files::notExists))
            .isTrue();
    }
}
