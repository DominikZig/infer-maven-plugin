package core;

import static java.util.concurrent.TimeUnit.MINUTES;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.logging.Logger;

@Named
@Singleton
public class InferRunner {

    private static final String JAVAC_COMMAND = "javac";
    private static final String JAVAC_CLASSPATH_OPTION = "-classpath";
    private static final String JAVAC_DEBUG_OPTION = "-g";
    private static final String JAVAC_DEST_DIRECTORY_OPTION = "-d";
    private static final String JAVAC_ARGFILE_PREFIX = "@";
    public static final String INFER_FAIL_ON_ISSUE_OPTION = "--fail-on-issue";
    private static final String INFER_RESULTS_DIR_OPTION = "--results-dir";
    private static final String INFER_ARG_TERMINATOR = "--";
    private static final long PROCESS_MAX_TIMEOUT = 1L;
    public static final int NORMAL_TERMINATION_FLAG = 0;
    public static final int INFER_ISSUES_FOUND = 2;

    private final Logger logger;

    private MavenProject project;

    private boolean failOnIssue;

    private String resultsDir;

    private static final String JAVA_FILE_EXTENSION = ".java";

    @Inject
    public InferRunner(Logger logger) {
        this.logger = logger;
    }

    public void runInferOnProject(Path inferExe) throws MojoExecutionException, MojoFailureException {
        Objects.requireNonNull(project, "Maven project information required to proceed with Infer analysis");
        Objects.requireNonNull(resultsDir, "Directory to store results required to proceed with Infer analysis");

        try {
            List<String> compileSourceRoots = project.getCompileSourceRoots();
            List<Path> javaSourceFiles = new ArrayList<>();

            for (String compileSourceRoot : compileSourceRoots) {
                Path rootPath = Path.of(compileSourceRoot);

                if (Files.isDirectory(rootPath)) {
                    try (Stream<Path> stream = Files.find(rootPath, Integer.MAX_VALUE,
                        (path, attrs) -> isJavaFileType(path))) {
                        javaSourceFiles.addAll(stream.toList());
                    } catch (IOException e) {
                        logger.error("Error occurred when trying to find Java sources in: " + rootPath);
                        throw new MojoExecutionException("Failed to find Java sources in: " + rootPath, e);
                    }
                }
            }

            if (javaSourceFiles.isEmpty()) {
                logger.warn("No Java sources found in " + javaSourceFiles + ". Skipping Infer analysis.");
                throw new MojoFailureException("No Java sources found; skipping Infer analysis.");
            }

            String compileClasspath = buildCompileClasspathFromProjectArtifacts();

            Path resultsDirPath = Path.of(resultsDir);
            Files.createDirectories(resultsDirPath);

            //Prepare an @argfile for sources to avoid long command lines
            Path argfileWithJavaSources = createJavacArgfile(javaSourceFiles);

            List<String> javacArgs = javacArgBuilder(compileClasspath, argfileWithJavaSources);
            List<String> inferArgs = inferArgBuilder(inferExe.toString(), resultsDirPath.toString(), javacArgs);

            int exitCode = executeInferCommands(inferArgs, project.getBasedir().toPath());

            // fail the build if Infer found issues (Infer returns 2 when issues found)
            if (failOnIssue && exitCode == INFER_ISSUES_FOUND) {
                logger.warn("Infer analysis completed with issues found, causing the build to fail. Check Infer results for more info.");
                throw new MojoFailureException("Infer analysis completed with issues found. Results in: " + resultsDirPath);
            }

            logger.info("Infer analysis completed. Results in: " + resultsDirPath);
        } catch (IOException | MojoFailureException | MojoExecutionException e) {
            if (e instanceof MojoFailureException) {
                if (e.getMessage().contains("Infer analysis completed with issues found.")) {
                    throw new MojoFailureException("Infer analysis completed with issues", e);
                }

                logger.warn("A failure occurred when running Infer on the project.", e);
                throw new MojoFailureException("Failure running Infer on project", e);
            }

            logger.error("An error occurred when running Infer on the project.", e);
            throw new MojoExecutionException("Error running Infer on project", e);
        }
    }

    private static boolean isJavaFileType(Path path) {
        return path.getFileName().toString().endsWith(JAVA_FILE_EXTENSION);
    }

    private String buildCompileClasspathFromProjectArtifacts() throws MojoExecutionException {
        try {
            List<String> compileClasspathElements = project.getCompileClasspathElements();
            return String.join(File.pathSeparator, compileClasspathElements);
        } catch (DependencyResolutionRequiredException e) {
            logger.error("An error occurred when compiling the classpath and the classpath could not be resolved");
            throw new MojoExecutionException("Compile classpath could not be resolved", e);
        }
    }

    private Path createJavacArgfile(List<Path> javaSourceFiles) throws IOException {
        Path buildDirPath = Path.of(project.getBuild().getDirectory());
        Files.createDirectories(buildDirPath);

        Path argfileWithJavaSources = buildDirPath.resolve("java-sources" + ".args");

        try (var bufferedWriter = Files.newBufferedWriter(argfileWithJavaSources)) {
            for (Path sourceFile : javaSourceFiles) {
                bufferedWriter.write(sourceFile.toString());
                bufferedWriter.newLine();
            }
        }

        return argfileWithJavaSources;
    }

    private List<String> javacArgBuilder(String compileClasspath, Path argfile) throws IOException {
        List<String> javacArgs = new ArrayList<>();
        javacArgs.add(JAVAC_COMMAND);

        if (!compileClasspath.isBlank()) {
            javacArgs.add(JAVAC_CLASSPATH_OPTION);
            javacArgs.add(compileClasspath);
        }

        if (logger.isDebugEnabled()) {
            javacArgs.add(JAVAC_DEBUG_OPTION);
        }

        // Direct the class output to target/classes so types resolve consistently
        Path classesDir = Path.of(project.getBuild().getOutputDirectory());
        Files.createDirectories(classesDir);
        javacArgs.add(JAVAC_DEST_DIRECTORY_OPTION);
        javacArgs.add(classesDir.toString());

        // Add all sources via @argfile
        javacArgs.add(JAVAC_ARGFILE_PREFIX + argfile);

        return javacArgs;
    }

    private List<String> inferArgBuilder(String inferExeOption, String resultsDirPathValue, List<String> javacArgs) {
        return Stream.concat(
            Stream.of(
                inferExeOption,
                INFER_FAIL_ON_ISSUE_OPTION,
                INFER_RESULTS_DIR_OPTION,
                resultsDirPathValue,
                INFER_ARG_TERMINATOR
            ),
            javacArgs.stream()
        ).toList();
    }

    private int executeInferCommands(List<String> inferCommands, Path workingDir) throws IOException, MojoExecutionException {
        logger.debug("Running: " + String.join(" ", inferCommands));

        var processBuilder = new ProcessBuilder(inferCommands);
        processBuilder.directory(workingDir.toFile());
        processBuilder.redirectErrorStream(true);

        Process process = processBuilder.start();
        logProcessOutput(process);

        try {
            boolean finished = process.waitFor(PROCESS_MAX_TIMEOUT, MINUTES);

            if (!finished) {
                process.destroyForcibly();
                logger.error("An error occurred during Infer due to timeout running command. See stacktrace for more info.");
                throw new MojoExecutionException("Infer analysis errored with timeout running command: " + inferCommands.getFirst());
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("An error occurred during Infer due to an interruption in the thread running command. See stacktrace for more info.");
            throw new MojoExecutionException("Infer analysis errored with interrupted running command: " + inferCommands.getFirst(), e);
        }

        int exitCode = process.exitValue();

        if (exitCode != NORMAL_TERMINATION_FLAG && exitCode != INFER_ISSUES_FOUND) {
            logger.error("An error occurred during Infer due to unexpected exit code returned by the process running Infer. See stacktrace for more info.");
            throw new MojoExecutionException("Infer analysis errored with unexpected exit code " + exitCode + ": " + String.join(" ", inferCommands));
        }

        return exitCode;
    }

    private void logProcessOutput(Process process) throws IOException {
        try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            reader.lines().map(String::stripTrailing)
                .filter(line -> !line.isBlank())
                .forEach(logger::info);
        }
    }

    public void setProject(MavenProject project) {
        this.project = project;
    }

    public void setFailOnIssue(boolean failOnIssue) {
        this.failOnIssue = failOnIssue;
    }

    public void setResultsDir(String resultsDir) {
        this.resultsDir = resultsDir;
    }
}
