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
    private static final String INFER_RESULTS_DIR_OPTION = "--results-dir";
    private static final String INFER_ARG_TERMINATOR = "--";
    private static final long PROCESS_MAX_TIMEOUT = 1L;
    public static final int NORMAL_TERMINATION_FLAG = 0;
    public static final int INFER_ISSUES_FOUND = 2;

    @Inject
    private Logger logger;

    private MavenProject project;

    private String resultsDir;

    private static final String JAVA_FILE_EXTENSION = ".java";

    public InferRunner() {
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
                        throw new MojoExecutionException("Failed to find Java sources in: " + rootPath, e);
                    }
                }
            }

            if (javaSourceFiles.isEmpty()) {
                logger.info("No Java sources found in " + javaSourceFiles + ". Skipping Infer analysis.");
                throw new MojoFailureException("No Java sources found; skipping Infer analysis.");
            }

            String compileClasspath = buildCompileClasspathFromProjectArtifacts();

            Path resultsDirPath = Path.of(resultsDir);
            Files.createDirectories(resultsDirPath);

            //Prepare an @argfile for sources to avoid long command lines
            Path argfileWithJavaSources = createJavacArgfile(javaSourceFiles);

            List<String> javacArgs = javacArgBuilder(compileClasspath, argfileWithJavaSources);
            List<String> inferArgs = inferArgBuilder(inferExe.toString(), resultsDirPath.toString(), javacArgs);

            int runExit = executeInferCommands(inferArgs, project.getBasedir().toPath());

            // fail the build if Infer found issues (Infer returns 2 when issues found)
            if (runExit == INFER_ISSUES_FOUND) {
                throw new MojoExecutionException("Infer analysis completed with issues found. Results in: " + resultsDirPath);
            }

            logger.info("Infer analysis completed. Results in: " + resultsDirPath);
        } catch (IOException | MojoFailureException e) {
            if (e instanceof MojoFailureException) {
                throw new MojoFailureException("Failure running Infer on project", e);
            }

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
                INFER_RESULTS_DIR_OPTION,
                resultsDirPathValue,
                INFER_ARG_TERMINATOR
            ),
            javacArgs.stream()
        ).toList();
    }

    private int executeInferCommands(List<String> inferCommands, Path workingDir) throws IOException, MojoExecutionException {
        logger.info("Running: " + String.join(" ", inferCommands));

        var processBuilder = new ProcessBuilder(inferCommands);
        processBuilder.directory(workingDir.toFile());
        processBuilder.redirectErrorStream(true);

        Process process = processBuilder.start();
        logProcessOutput(process);

        try {
            boolean finished = process.waitFor(PROCESS_MAX_TIMEOUT, MINUTES);

            if (!finished) {
                process.destroyForcibly();
                throw new MojoExecutionException("Timeout running: " + inferCommands.getFirst());
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MojoExecutionException("Interrupted running: " + inferCommands.getFirst(), e);
        }

        int exitFlag = process.exitValue();

        if (exitFlag != NORMAL_TERMINATION_FLAG && exitFlag != INFER_ISSUES_FOUND) {
            throw new MojoExecutionException("Command exited with code " + exitFlag + ": " + String.join(" ", inferCommands));
        }

        return exitFlag;
    }

    private void logProcessOutput(Process process) throws IOException {
        try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            reader.lines().map(String::stripTrailing)
                .filter(line -> !line.isBlank())
                .forEach(line -> logger.info(line));
        }
    }

    public void setProject(MavenProject project) {
        this.project = project;
    }

    public void setResultsDir(String resultsDir) {
        this.resultsDir = resultsDir;
    }
}
