package core;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import Utils.DummyJavaProject;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.model.Build;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.logging.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class InferRunnerTest {

    @Mock
    private Logger logger;

    @Mock
    private MavenProject project;

    private InferRunner runner;

    @BeforeEach
    void setUp() {
        runner = new InferRunner(logger);
    }

    @DisplayName(
            """
        Given a valid Java source\s
        And and process exits with normal termination flag of 0\s
        When running Infer\s
        Then completes successfully and includes -g and classpath in command\s
       """)
    @Test
    void runInferOnProjectSuccessful(@TempDir Path tmp) throws Exception {
        DummyJavaProject dummyJavaProject = createDummyJavaProject(tmp);

        Path targetDir = dummyJavaProject.projectRoot().resolve("target");
        Path resultsDir = dummyJavaProject.projectRoot().resolve("infer-results");

        Build build = new Build();
        build.setDirectory(targetDir.toString());
        build.setOutputDirectory(targetDir.resolve("classes").toString());

        // Non-empty classpath to assert -classpath is added
        when(project.getCompileSourceRoots())
                .thenReturn(List.of(dummyJavaProject.srcMainJava().toString()));
        when(project.getCompileClasspathElements())
                .thenReturn(
                        List.of(dummyJavaProject.projectRoot().resolve("lib").toString()));
        when(project.getBuild()).thenReturn(build);
        when(project.getBasedir()).thenReturn(dummyJavaProject.projectRoot().toFile());
        when(logger.isDebugEnabled()).thenReturn(true); // expect -g in args since testing 'full' flow

        runner.setProject(project);
        runner.setResultsDir(resultsDir.toString());
        runner.setFailOnIssue(false);

        Path dummyInferExecutable = createDummyInferExecutable(tmp, 0, "infer: ok");

        runner.runInferOnProject(dummyInferExecutable);

        assertThat(Files.exists(resultsDir)).isTrue();
        Path argfile = targetDir.resolve("java-sources.args");
        assertThat(Files.exists(argfile)).isTrue();
        assertThat(Files.readAllLines(argfile))
                .containsExactly(dummyJavaProject.helloJava().toString());
        assertThat(Files.exists(targetDir.resolve("classes"))).isTrue();

        verify(logger).info("infer: ok");
        verify(logger).info("Infer analysis completed. Results in: " + resultsDir);
        var debugLogCaptor = ArgumentCaptor.forClass(String.class);
        verify(logger, atLeastOnce()).debug(debugLogCaptor.capture());
        var debugLogMessages = debugLogCaptor.getAllValues();
        assertThat(debugLogMessages.stream().anyMatch(s -> s.startsWith("Running: ")))
                .isTrue();
        assertThat(debugLogMessages.stream().anyMatch(s -> s.contains("-classpath")))
                .isTrue();
        assertThat(debugLogMessages.stream().anyMatch(s -> s.contains("-g"))).isTrue();
    }

    @DisplayName(
            """
        Given no Java sources\s
        When running Infer\s
        Then throws MojoFailureException and logs info\s
        And does not create results dir\s
       """)
    @Test
    void runInferOnProjectNoJavaSources(@TempDir Path tmp) {
        // Setup a dummy project structure with no Java files
        Path projectRoot = tmp.resolve("proj");
        Path srcMainJava = projectRoot.resolve("src").resolve("main").resolve("java");
        Path resultsDir = projectRoot.resolve("infer-results");

        when(project.getCompileSourceRoots()).thenReturn(List.of(srcMainJava.toString()));

        runner.setProject(project);
        runner.setResultsDir(resultsDir.toString());
        runner.setFailOnIssue(true);

        var mojoFailureException =
                assertThrows(MojoFailureException.class, () -> runner.runInferOnProject(Path.of("infer")));

        assertThat(mojoFailureException.getMessage()).isEqualTo("Failure running Infer on project");
        assertThat(mojoFailureException).hasCauseThat().isInstanceOf(MojoFailureException.class);
        assertThat(mojoFailureException)
                .hasCauseThat()
                .hasMessageThat()
                .isEqualTo("No Java sources found; skipping Infer analysis.");
        verify(logger, times(1)).warn("No Java sources found in []. Skipping Infer analysis.");
        assertThat(Files.exists(resultsDir)).isFalse(); // Ensure results dir not created because early exit

        var warnLogCaptor = ArgumentCaptor.forClass(String.class);
        var warnExCaptor = ArgumentCaptor.forClass(Throwable.class);
        verify(logger, times(1)).warn(warnLogCaptor.capture(), warnExCaptor.capture());
        var warnLogMessage = warnLogCaptor.getValue();
        assertThat(warnLogMessage).contains("A failure occurred when running Infer on the project.");
    }

    @DisplayName(
            """
        Given a valid Java sources\s
        And missing infer executable\s
        When running Infer\s
        Then throws MojoExecutionException\s
        And argfile/results dirs still created\s
       """)
    @Test
    void runInferOnProjectMissingInferExe(@TempDir Path tmp) throws Exception {
        DummyJavaProject dummyJavaProject = createDummyJavaProject(tmp);

        Path targetDir = dummyJavaProject.projectRoot().resolve("target");
        Path resultsDir = dummyJavaProject.projectRoot().resolve("infer-results");

        Build build = new Build();
        build.setDirectory(targetDir.toString());
        build.setOutputDirectory(targetDir.resolve("classes").toString());

        when(project.getCompileSourceRoots())
                .thenReturn(List.of(dummyJavaProject.srcMainJava().toString()));
        when(project.getCompileClasspathElements()).thenReturn(Collections.emptyList());
        when(project.getBuild()).thenReturn(build);
        when(project.getBasedir()).thenReturn(dummyJavaProject.projectRoot().toFile());
        when(logger.isDebugEnabled()).thenReturn(false);

        runner.setProject(project);
        runner.setResultsDir(resultsDir.toString());
        runner.setFailOnIssue(false);

        // Use a non-existent infer executable inside temp so ProcessBuilder.start throws IOException
        Path missingInfer = dummyJavaProject.projectRoot().resolve("bin").resolve("infer-does-not-exist");

        var mojoExecutionException =
                assertThrows(MojoExecutionException.class, () -> runner.runInferOnProject(missingInfer));

        assertThat(mojoExecutionException.getMessage()).isEqualTo("Error running Infer on project");
        assertThat(mojoExecutionException).hasCauseThat().isInstanceOf(IOException.class);

        // Verify side effects before failing to start process - inside target dir so it is fine to leave artifacts
        assertThat(Files.exists(resultsDir)).isTrue(); // results dir should be created
        Path argfile = targetDir.resolve("java-sources.args");
        assertThat(Files.exists(argfile)).isTrue(); // argfile should exist with our Hello.java path inside
        List<String> lines = Files.readAllLines(argfile);
        assertThat(lines).containsExactly(dummyJavaProject.helloJava().toString());
        assertThat(Files.exists(targetDir.resolve("classes")))
                .isTrue(); // javacArgBuilder should have created the classes directory

        var errorLogCaptor = ArgumentCaptor.forClass(String.class);
        var errorExCaptor = ArgumentCaptor.forClass(Throwable.class);
        verify(logger, times(1)).error(errorLogCaptor.capture(), errorExCaptor.capture());
        var errorLogMessage = errorLogCaptor.getValue();
        assertThat(errorLogMessage).contains("An error occurred when running Infer on the project.");
    }

    @DisplayName(
            """
        Given a valid Java sources\s
        And infer process with exit code 2
        And with failOnIssue true\s
        When running Infer\s
        Then throws MojoFailureException\s
       """)
    @Test
    void runInferOnProjectInferExitCode2FailOnIssueTrue(@TempDir Path tmp) throws Exception {
        DummyJavaProject dummyJavaProject = createDummyJavaProject(tmp);

        Path targetDir = dummyJavaProject.projectRoot().resolve("target");
        Path resultsDir = dummyJavaProject.projectRoot().resolve("infer-results");

        Build build = new Build();
        build.setDirectory(targetDir.toString());
        build.setOutputDirectory(targetDir.resolve("classes").toString());

        when(project.getCompileSourceRoots())
                .thenReturn(List.of(dummyJavaProject.srcMainJava().toString()));
        when(project.getCompileClasspathElements()).thenReturn(Collections.emptyList());
        when(project.getBuild()).thenReturn(build);
        when(project.getBasedir()).thenReturn(dummyJavaProject.projectRoot().toFile());
        when(logger.isDebugEnabled()).thenReturn(false);

        InferRunner runner = new InferRunner(logger);
        runner.setProject(project);
        runner.setResultsDir(resultsDir.toString());
        runner.setFailOnIssue(true);

        Path dummyInferExecutableWithExitCode2 = createDummyInferExecutable(tmp, 2, "infer: issues");

        var mojoExecutionException = assertThrows(
                MojoFailureException.class, () -> runner.runInferOnProject(dummyInferExecutableWithExitCode2));

        assertThat(mojoExecutionException.getMessage()).contains("Infer analysis completed with issues");
        assertThat(mojoExecutionException).hasCauseThat().isInstanceOf(MojoFailureException.class);
        assertThat(mojoExecutionException)
                .hasCauseThat()
                .hasMessageThat()
                .startsWith("Infer analysis completed with issues found. Results in:");

        // Ensure results and argfile exist
        assertThat(Files.exists(resultsDir)).isTrue();
        assertThat(Files.exists(targetDir.resolve("java-sources.args"))).isTrue();

        var warnLogCaptor = ArgumentCaptor.forClass(String.class);
        verify(logger, times(1)).warn(warnLogCaptor.capture());
        var warnLogMessage = warnLogCaptor.getValue();
        assertThat(warnLogMessage)
                .contains(
                        "Infer analysis completed with issues found, causing the build to fail. Check Infer results for more info.");
    }

    @DisplayName(
            """
        Given a valid Java sources\s
        And infer process with exit code 2
        And with failOnIssue false\s
        When running Infer\s
        Then completes successfully without throwing exception causing build to fail\s
        """)
    @Test
    void runInferOnProjectInferExitCode2FailOnIssueFalse(@TempDir Path tmp) throws Exception {
        DummyJavaProject dummyJavaProject = createDummyJavaProject(tmp);

        Path targetDir = dummyJavaProject.projectRoot().resolve("target");
        Path resultsDir = dummyJavaProject.projectRoot().resolve("infer-results");

        Build build = new Build();
        build.setDirectory(targetDir.toString());
        build.setOutputDirectory(targetDir.resolve("classes").toString());

        when(project.getCompileSourceRoots())
                .thenReturn(List.of(dummyJavaProject.srcMainJava().toString()));
        when(project.getCompileClasspathElements()).thenReturn(Collections.emptyList());
        when(project.getBuild()).thenReturn(build);
        when(project.getBasedir()).thenReturn(dummyJavaProject.projectRoot().toFile());
        when(logger.isDebugEnabled()).thenReturn(false);

        InferRunner runner = new InferRunner(logger);
        runner.setProject(project);
        runner.setResultsDir(resultsDir.toString());
        runner.setFailOnIssue(false);

        Path dummyInferExecutableWithExitCode2 = createDummyInferExecutable(tmp, 2, "infer: issues");

        // even though exit code is 2, failOnIssue flag is false, so we expect the build to succeed
        assertDoesNotThrow(() -> runner.runInferOnProject(dummyInferExecutableWithExitCode2));

        assertThat(Files.exists(resultsDir)).isTrue();
        Path argfile = targetDir.resolve("java-sources.args");
        assertThat(Files.exists(argfile)).isTrue();
        assertThat(Files.readAllLines(argfile))
                .containsExactly(dummyJavaProject.helloJava().toString());
        assertThat(Files.exists(targetDir.resolve("classes"))).isTrue();
    }

    @DisplayName(
            """
        Given a valid Java sources\s
        And infer process with unexpected exit code\s
        When running Infer\s
        Then throws MojoExecutionException\s
       """)
    @Test
    void runInferOnProjectUnexpectedExitCode(@TempDir Path tmp) throws Exception {
        DummyJavaProject dummyJavaProject = createDummyJavaProject(tmp);

        Path targetDir = dummyJavaProject.projectRoot().resolve("target");
        Path resultsDir = dummyJavaProject.projectRoot().resolve("infer-results");

        Build build = new Build();
        build.setDirectory(targetDir.toString());
        build.setOutputDirectory(targetDir.resolve("classes").toString());

        when(project.getCompileSourceRoots())
                .thenReturn(List.of(dummyJavaProject.srcMainJava().toString()));
        when(project.getCompileClasspathElements()).thenReturn(new ArrayList<>());
        when(project.getBuild()).thenReturn(build);
        when(project.getBasedir()).thenReturn(dummyJavaProject.projectRoot().toFile());
        when(logger.isDebugEnabled()).thenReturn(false);

        InferRunner runner = new InferRunner(logger);
        runner.setProject(project);
        runner.setResultsDir(resultsDir.toString());
        runner.setFailOnIssue(false);

        Path dummyInferExecutableWithExitCode3 = createDummyInferExecutable(tmp, 3, "infer: unexpected");

        var mojoExecutionException = assertThrows(
                MojoExecutionException.class, () -> runner.runInferOnProject(dummyInferExecutableWithExitCode3));

        assertThat(mojoExecutionException.getMessage()).isEqualTo("Error running Infer on project");
        assertThat(mojoExecutionException).hasCauseThat().isInstanceOf(MojoExecutionException.class);
        assertThat(mojoExecutionException)
                .hasCauseThat()
                .hasMessageThat()
                .contains("Infer analysis errored with unexpected exit code 3");

        // Ensure results and argfile exist
        assertThat(Files.exists(resultsDir)).isTrue();
        assertThat(Files.exists(targetDir.resolve("java-sources.args"))).isTrue();

        var errorLogCaptor = ArgumentCaptor.forClass(String.class);
        var errorExCaptor = ArgumentCaptor.forClass(Throwable.class);
        verify(logger)
                .error(
                        "An error occurred during Infer due to unexpected exit code returned by the process running Infer. See stacktrace for more info.");
        verify(logger, atLeastOnce()).error(errorLogCaptor.capture(), errorExCaptor.capture());
        var errorLogMessage = errorLogCaptor.getValue();
        assertThat(errorLogMessage).contains("An error occurred when running Infer on the project.");
    }

    @DisplayName(
            """
        Given Java sources with invalid source root\s
        And causing IOException during discovery\s
        When running Infer\s
        Then throws MojoExecutionException\s
        with 'Failed to find Java sources in: <root>'\s
       """)
    @Test
    void runInferOnProjectFindJavaSourcesIOException(@TempDir Path tmp) throws Exception {
        Path projectRoot = tmp.resolve("proj");
        Path srcMainJava = projectRoot.resolve("src").resolve("main").resolve("java");
        Files.createDirectories(srcMainJava);

        when(project.getCompileSourceRoots()).thenReturn(List.of(srcMainJava.toString()));

        runner.setProject(project);
        runner.setResultsDir(projectRoot.resolve("infer-results").toString());
        runner.setFailOnIssue(false);

        // Mock Files.isDirectory and Files.find to throw IOException on discovery
        try (MockedStatic<Files> filesMock = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            filesMock.when(() -> Files.isDirectory(srcMainJava)).thenReturn(true);
            filesMock
                    .when(() -> Files.find(eq(srcMainJava), eq(Integer.MAX_VALUE), any()))
                    .thenThrow(new IOException("disk error"));

            var mojoExecutionException =
                    assertThrows(MojoExecutionException.class, () -> runner.runInferOnProject(Path.of("infer")));

            assertThat(mojoExecutionException.getMessage()).isEqualTo("Error running Infer on project");
            assertThat(mojoExecutionException).hasCauseThat().isInstanceOf(MojoExecutionException.class);

            Throwable inner = mojoExecutionException.getCause();
            assertThat(inner).isInstanceOf(MojoExecutionException.class);
            assertThat(inner.getMessage()).isEqualTo("Failed to find Java sources in: " + srcMainJava);
            assertThat(inner).hasCauseThat().isInstanceOf(IOException.class);

            var errorLogCaptor = ArgumentCaptor.forClass(String.class);
            verify(logger).error(errorLogCaptor.capture());
            var errorLogMessage = errorLogCaptor.getValue();
            assertThat(errorLogMessage).contains("Error occurred when trying to find Java sources in: ");
        }
    }

    @DisplayName(
            """
        Given discovered Java sources\s
        but classpath resolution fails\s
        When running Infer\s
        Then throws MojoExecutionException\s
        with 'Compile classpath could not be resolved'\s
       """)
    @Test
    void runInferOnProjectCompileClasspathResolutionFails(@TempDir Path tmp) throws Exception {
        DummyJavaProject dummyJavaProject = createDummyJavaProject(tmp);

        // Build and basedir (won't be used because we fail before exec)
        Build build = new Build();
        build.setDirectory(dummyJavaProject.projectRoot().resolve("target").toString());
        build.setOutputDirectory(dummyJavaProject
                .projectRoot()
                .resolve("target")
                .resolve("classes")
                .toString());

        when(project.getCompileSourceRoots())
                .thenReturn(List.of(dummyJavaProject.srcMainJava().toString()));

        // Trigger DependencyResolutionRequiredException when building classpath
        when(project.getCompileClasspathElements()).thenThrow(new DependencyResolutionRequiredException(null));

        runner.setProject(project);
        runner.setResultsDir(
                dummyJavaProject.projectRoot().resolve("infer-results").toString());
        runner.setFailOnIssue(false);

        var mojoExecutionException =
                assertThrows(MojoExecutionException.class, () -> runner.runInferOnProject(Path.of("infer")));
        assertThat(mojoExecutionException.getMessage()).isEqualTo("Error running Infer on project");
        assertThat(mojoExecutionException).hasCauseThat().isInstanceOf(MojoExecutionException.class);
        assertThat(mojoExecutionException)
                .hasCauseThat()
                .hasMessageThat()
                .isEqualTo("Compile classpath could not be resolved");
        assertThat(mojoExecutionException.getCause())
                .hasCauseThat()
                .isInstanceOf(DependencyResolutionRequiredException.class);

        var errorLogCaptor = ArgumentCaptor.forClass(String.class);
        var errorExCaptor = ArgumentCaptor.forClass(Throwable.class);
        verify(logger).error("An error occurred when compiling the classpath and the classpath could not be resolved");
        verify(logger, atLeastOnce()).error(errorLogCaptor.capture(), errorExCaptor.capture());
        var errorLogMessage = errorLogCaptor.getValue();
        assertThat(errorLogMessage).contains("An error occurred when running Infer on the project.");
    }

    @DisplayName(
            """
        Given valid Java sources with multiple Java files\s
        When running Infer\s
        Then Argfile contains one path per line so multiple sources produce multiple lines\s
       """)
    @Test
    void runInferOnProjectCreateJavacArgfileWritesNewlineBetweenEntries(@TempDir Path tmp) throws Exception {
        // Arrange the project structure with two Java files
        Path projectRoot = tmp.resolve("proj");
        Path srcMainJava = projectRoot.resolve("src").resolve("main").resolve("java");
        Path pkgA = srcMainJava.resolve("a");
        Path pkgB = srcMainJava.resolve("b");
        Files.createDirectories(pkgA);
        Files.createDirectories(pkgB);
        Path aJava = pkgA.resolve("A.java");
        Path bJava = pkgB.resolve("B.java");
        Files.writeString(aJava, "package a; class A {}", StandardCharsets.UTF_8);
        Files.writeString(bJava, "package b; class B {}", StandardCharsets.UTF_8);

        Path targetDir = projectRoot.resolve("target");
        Path resultsDir = projectRoot.resolve("infer-results");

        Build build = new Build();
        build.setDirectory(targetDir.toString());
        build.setOutputDirectory(targetDir.resolve("classes").toString());

        when(project.getCompileSourceRoots()).thenReturn(List.of(srcMainJava.toString()));
        when(project.getCompileClasspathElements()).thenReturn(new ArrayList<>());
        when(project.getBuild()).thenReturn(build);
        when(project.getBasedir()).thenReturn(projectRoot.toFile());
        when(logger.isDebugEnabled()).thenReturn(false);

        runner.setProject(project);
        runner.setResultsDir(resultsDir.toString());
        runner.setFailOnIssue(false);

        Path dummyInferExecutable = createDummyInferExecutable(tmp, 0, "infer ok");

        runner.runInferOnProject(dummyInferExecutable);

        // argfile should have exactly one path per line (2 lines for 2 files)
        Path argfile = targetDir.resolve("java-sources.args");
        List<String> lines = Files.readAllLines(argfile);
        assertThat(lines).hasSize(2);
        assertThat(lines).containsExactlyElementsIn(List.of(aJava.toString(), bJava.toString()));
    }

    @DisplayName(
            """
         Given a valid Java source\s
         And Infer command includes @argfile path
         When running Infer\s
         Then completes successfully and includes argfile in command\s
       """)
    @Test
    void runInferOnProjectIncludesArgfilePathInCommand(@TempDir Path tmp) throws Exception {
        DummyJavaProject dummyJavaProject = createDummyJavaProject(tmp);

        Path targetDir = dummyJavaProject.projectRoot().resolve("target");
        Path resultsDir = dummyJavaProject.projectRoot().resolve("infer-results");

        Build build = new Build();
        build.setDirectory(targetDir.toString());
        build.setOutputDirectory(targetDir.resolve("classes").toString());

        when(project.getCompileSourceRoots())
                .thenReturn(List.of(dummyJavaProject.srcMainJava().toString()));
        when(project.getCompileClasspathElements()).thenReturn(Collections.emptyList());
        when(project.getBuild()).thenReturn(build);
        when(project.getBasedir()).thenReturn(dummyJavaProject.projectRoot().toFile());
        when(logger.isDebugEnabled()).thenReturn(false);

        runner.setProject(project);
        runner.setResultsDir(resultsDir.toString());
        runner.setFailOnIssue(false);

        Path dummyInferExecutable = createDummyInferExecutable(tmp, 0, "infer ok");

        runner.runInferOnProject(dummyInferExecutable);

        // Assert: command line includes the @argfile path (would be "@null" if createJavacArgfile returned null)
        Path argfile = targetDir.resolve("java-sources.args");
        verify(logger, atLeastOnce()).debug(argThat(s -> s.startsWith("Running: ") && s.contains("@" + argfile)));

        // Argfile exists and contains our single source path
        assertThat(Files.exists(argfile)).isTrue();
        assertThat(Files.readAllLines(argfile))
                .containsExactly(dummyJavaProject.helloJava().toString());
    }

    @DisplayName(
            """
         Given a valid Java source\s
         And Infer process does not finish within timeout\s
         When running Infer\s
         Then process is destroyed forcibly and throws MojoExecutionException\s
       """)
    @Test
    void runInferOnProjectProcessTimeout(@TempDir Path tmp) throws Exception {
        DummyJavaProject dummyJavaProject = createDummyJavaProject(tmp);

        Path targetDir = dummyJavaProject.projectRoot().resolve("target");
        Path resultsDir = dummyJavaProject.projectRoot().resolve("infer-results");

        Build build = new Build();
        build.setDirectory(targetDir.toString());
        build.setOutputDirectory(targetDir.resolve("classes").toString());

        when(project.getCompileSourceRoots())
                .thenReturn(List.of(dummyJavaProject.srcMainJava().toString()));
        when(project.getCompileClasspathElements()).thenReturn(Collections.emptyList());
        when(project.getBuild()).thenReturn(build);
        when(project.getBasedir()).thenReturn(dummyJavaProject.projectRoot().toFile());
        when(logger.isDebugEnabled()).thenReturn(false);

        runner.setProject(project);
        runner.setResultsDir(resultsDir.toString());
        runner.setFailOnIssue(false);

        // Dummy infer path (won't actually run due to construction mocking)
        Path dummyInferExecutable =
                dummyJavaProject.projectRoot().resolve("bin").resolve("infer");

        // Mock Process so that waitFor(timeout) returns false (timeout)
        Process mockProcess = mock(Process.class);
        when(mockProcess.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
        when(mockProcess.waitFor(anyLong(), any())).thenReturn(false);

        // Intercept new ProcessBuilder(...) and return our mocked Process
        try (MockedConstruction<ProcessBuilder> ignored = mockConstruction(ProcessBuilder.class, (builder, context) -> {
            when(builder.directory(any())).thenReturn(builder);
            when(builder.redirectErrorStream(true)).thenReturn(builder);
            when(builder.start()).thenReturn(mockProcess);
        })) {
            var mojoExecutionException =
                    assertThrows(MojoExecutionException.class, () -> runner.runInferOnProject(dummyInferExecutable));

            assertThat(mojoExecutionException.getMessage()).isEqualTo("Error running Infer on project");
            assertThat(mojoExecutionException).hasCauseThat().isInstanceOf(MojoExecutionException.class);
            assertThat(mojoExecutionException)
                    .hasCauseThat()
                    .hasMessageThat()
                    .isEqualTo("Infer analysis errored with timeout running command: " + dummyInferExecutable);

            verify(mockProcess, times(1)).destroyForcibly(); // Ensure process was destroyed forcibly on timeout

            var errorLogCaptor = ArgumentCaptor.forClass(String.class);
            var errorExCaptor = ArgumentCaptor.forClass(Throwable.class);
            verify(logger)
                    .error(
                            "An error occurred during Infer due to timeout running command. See stacktrace for more info.");
            verify(logger, atLeastOnce()).error(errorLogCaptor.capture(), errorExCaptor.capture());
            var errorLogMessage = errorLogCaptor.getValue();
            assertThat(errorLogMessage).contains("An error occurred when running Infer on the project.");
        }
    }

    @DisplayName(
            """
         Given a valid Java source\s
         And Infer process wait is interrupted\s
         When running Infer\s
         Then thread is re-interrupted and throws MojoExecutionException\s
       """)
    @Test
    void runInferOnProjectProcessInterrupted(@TempDir Path tmp) throws Exception {
        DummyJavaProject dummyJavaProject = createDummyJavaProject(tmp);

        Path targetDir = dummyJavaProject.projectRoot().resolve("target");
        Path resultsDir = dummyJavaProject.projectRoot().resolve("infer-results");

        Build build = new Build();
        build.setDirectory(targetDir.toString());
        build.setOutputDirectory(targetDir.resolve("classes").toString());

        when(project.getCompileSourceRoots())
                .thenReturn(List.of(dummyJavaProject.srcMainJava().toString()));
        when(project.getCompileClasspathElements()).thenReturn(Collections.emptyList());
        when(project.getBuild()).thenReturn(build);
        when(project.getBasedir()).thenReturn(dummyJavaProject.projectRoot().toFile());
        when(logger.isDebugEnabled()).thenReturn(false);

        runner.setProject(project);
        runner.setResultsDir(resultsDir.toString());
        runner.setFailOnIssue(false);

        // Dummy infer path (won't actually run due to construction mocking)
        Path dummyInferExecutable =
                dummyJavaProject.projectRoot().resolve("bin").resolve("infer");

        // Mock Process to throw InterruptedException from waitFor
        Process mockProcess = mock(Process.class);
        when(mockProcess.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
        when(mockProcess.waitFor(anyLong(), any())).thenThrow(new InterruptedException("test interrupt"));

        try (MockedConstruction<ProcessBuilder> ignored = mockConstruction(ProcessBuilder.class, (builder, context) -> {
            when(builder.directory(any())).thenReturn(builder);
            when(builder.redirectErrorStream(true)).thenReturn(builder);
            when(builder.start()).thenReturn(mockProcess);
        })) {
            var mojoExecutionException =
                    assertThrows(MojoExecutionException.class, () -> runner.runInferOnProject(dummyInferExecutable));

            assertThat(mojoExecutionException.getMessage()).isEqualTo("Error running Infer on project");
            assertThat(mojoExecutionException).hasCauseThat().isInstanceOf(MojoExecutionException.class);
            assertThat(mojoExecutionException)
                    .hasCauseThat()
                    .hasMessageThat()
                    .isEqualTo("Infer analysis errored with interrupted running command: " + dummyInferExecutable);
            assertThat(mojoExecutionException.getCause().getCause()).isInstanceOf(InterruptedException.class);

            assertThat(Thread.currentThread().isInterrupted()).isTrue(); // The thread should have been re-interrupted
            Thread.interrupted(); // Clear interrupt flag for subsequent tests

            var errorLogCaptor = ArgumentCaptor.forClass(String.class);
            var errorExCaptor = ArgumentCaptor.forClass(Throwable.class);
            verify(logger)
                    .error(
                            "An error occurred during Infer due to an interruption in the thread running command. See stacktrace for more info.");
            verify(logger, atLeastOnce()).error(errorLogCaptor.capture(), errorExCaptor.capture());
            var errorLogMessage = errorLogCaptor.getValue();
            assertThat(errorLogMessage).contains("An error occurred when running Infer on the project.");
        }
    }

    @DisplayName(
            """
         Given a valid Java source\s
         And process output containing non-empty, empty, and whitespace-only lines\s
         When running Infer\s
         Then Process output logs skip blank/whitespace-only lines\s
       """)
    @Test
    void runInferOnProjectProcessOutputSkipsBlankLines(@TempDir Path tmp) throws Exception {
        DummyJavaProject dummyJavaProject = createDummyJavaProject(tmp);

        Path targetDir = dummyJavaProject.projectRoot().resolve("target");
        Path resultsDir = dummyJavaProject.projectRoot().resolve("infer-results");

        Build build = new Build();
        build.setDirectory(targetDir.toString());
        build.setOutputDirectory(targetDir.resolve("classes").toString());

        when(project.getCompileSourceRoots())
                .thenReturn(List.of(dummyJavaProject.srcMainJava().toString()));
        when(project.getCompileClasspathElements()).thenReturn(Collections.emptyList());
        when(project.getBuild()).thenReturn(build);
        when(project.getBasedir()).thenReturn(dummyJavaProject.projectRoot().toFile());
        when(logger.isDebugEnabled()).thenReturn(false);

        runner.setProject(project);
        runner.setResultsDir(resultsDir.toString());
        runner.setFailOnIssue(false);

        // Dummy infer path (won't actually run due to construction mocking)
        Path dummyInferExecutable =
                dummyJavaProject.projectRoot().resolve("bin").resolve("infer");

        // Mocked process output containing non-empty, empty, and whitespace-only lines
        String processOut = "alpha\n\n   \n\t \n beta \n\ngamma\n";
        Process mockProcess = mock(Process.class);
        when(mockProcess.getInputStream())
                .thenReturn(new ByteArrayInputStream(processOut.getBytes(StandardCharsets.UTF_8)));
        when(mockProcess.waitFor(anyLong(), any())).thenReturn(true);
        when(mockProcess.exitValue()).thenReturn(0);

        try (MockedConstruction<ProcessBuilder> ignored = mockConstruction(ProcessBuilder.class, (builder, context) -> {
            when(builder.directory(any())).thenReturn(builder);
            when(builder.redirectErrorStream(true)).thenReturn(builder);
            when(builder.start()).thenReturn(mockProcess);
        })) {
            runner.runInferOnProject(dummyInferExecutable);

            // non-blank lines logged, blank/whitespace-only lines are NOT logged
            verify(logger, atLeastOnce()).info("alpha");
            verify(logger, atLeastOnce())
                    .info(" beta"); // note: stripTrailing only removes trailing spaces, so leading spaces remain
            verify(logger, atLeastOnce()).info("gamma");

            // Ensure no blank string was logged (after stripTrailing + isBlank filter)
            verify(logger, never()).info(argThat(String::isBlank));
        }
    }

    @Test
    void runInferOnProjectNullMavenProject() {
        runner.setProject(null);
        var nullPointerException = assertThrows(NullPointerException.class, () -> runner.runInferOnProject(null));
        assertThat(nullPointerException)
                .hasMessageThat()
                .isEqualTo("Maven project information required to proceed with Infer analysis");
    }

    @Test
    void runInferOnProjectNullResultsDir() {
        runner.setProject(project);
        runner.setResultsDir(null);
        var nullPointerException = assertThrows(NullPointerException.class, () -> runner.runInferOnProject(null));
        assertThat(nullPointerException)
                .hasMessageThat()
                .isEqualTo("Directory to store results required to proceed with Infer analysis");
    }

    private DummyJavaProject createDummyJavaProject(Path tmp) throws IOException {
        Path projectRoot = tmp.resolve("proj");
        Path srcMainJava = projectRoot.resolve("src").resolve("main").resolve("java");
        Path pkgDir = srcMainJava.resolve("example");
        Files.createDirectories(pkgDir);
        Path helloJava = pkgDir.resolve("Hello.java");
        Files.writeString(helloJava, "package example; class Hello {}", StandardCharsets.UTF_8);
        return new DummyJavaProject(projectRoot, srcMainJava, helloJava);
    }

    private Path createDummyInferExecutable(Path tempDir, int exitCode, String echoLine) throws IOException {
        Path binDir = tempDir.resolve("bin");
        Files.createDirectories(binDir);
        Path inferExe = binDir.resolve("infer.sh");

        // POSIX shell script
        String content =
                "#!/usr/bin/env sh\n" + "echo \"" + echoLine.replace("\"", "\\\"") + "\"\n" + "exit " + exitCode + "\n";
        Files.writeString(inferExe, content, StandardCharsets.UTF_8);
        var perms = Files.getPosixFilePermissions(inferExe);
        perms.addAll(Set.of(
                PosixFilePermission.OWNER_EXECUTE,
                PosixFilePermission.GROUP_EXECUTE,
                PosixFilePermission.OTHERS_EXECUTE));
        Files.setPosixFilePermissions(inferExe, perms);

        return inferExe;
    }
}
