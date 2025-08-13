package core;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream;
import org.apache.maven.plugin.MojoExecutionException;
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
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.jupiter.MockitoExtension;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
            .thenAnswer(inv -> dummyInferUrlHttpResponse(inv, tarBytes));

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
            .thenAnswer(inv -> dummyInferUrlHttpResponse(inv, tarBytes));

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
            .thenAnswer(inv -> dummyInferUrlHttpResponse(inv, tarBytes));

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

    private static HttpResponse<Path> dummyInferUrlHttpResponse(InvocationOnMock inv, byte[] tarBytes) {
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

    // Utility: create a minimal tar.xz archive as bytes
    private static byte[] createTarXz(byte[] fileContent, String rootDirName) throws IOException {
        var byteArrayOutputStream = new ByteArrayOutputStream();
        try (
            var xzCompressorOutputStream = new XZCompressorOutputStream(byteArrayOutputStream);
            var tarArchiveOutputStream = new TarArchiveOutputStream(xzCompressorOutputStream)) {
            String root = createRoot(rootDirName, tarArchiveOutputStream);

            TarArchiveEntry fileEntry = createFile(fileContent, root + "bin/infer");
            tarArchiveOutputStream.putArchiveEntry(fileEntry);
            tarArchiveOutputStream.write(fileContent);
            tarArchiveOutputStream.closeArchiveEntry();

            tarArchiveOutputStream.finish();
        }
        return byteArrayOutputStream.toByteArray();
    }

    private static byte[] createTarXzWithZipSlip(byte[] fileContent) throws IOException {
        var byteArrayOutputStream = new ByteArrayOutputStream();
        try (
            var xzCompressorOutputStream = new XZCompressorOutputStream(byteArrayOutputStream);
            var tarArchiveOutputStream = new TarArchiveOutputStream(xzCompressorOutputStream)) {
            String root = createRoot("root", tarArchiveOutputStream);

            TarArchiveEntry fileEntry = createFile(fileContent, "../evil.txt"); // ZipSlip attempt
            tarArchiveOutputStream.putArchiveEntry(fileEntry);
            tarArchiveOutputStream.write(fileContent);
            tarArchiveOutputStream.closeArchiveEntry();

            tarArchiveOutputStream.finish();
        }
        return byteArrayOutputStream.toByteArray();
    }

    private static String createRoot(String rootDirName, TarArchiveOutputStream tarArchiveOutputStream)
        throws IOException {
        tarArchiveOutputStream.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);

        // Directories
        System.out.println("rootdirname" + rootDirName);
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
