package core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.logging.Logger;

@Named
@Singleton
public class InferRunner {

    @Inject
    private Logger logger;

    private MavenProject project;

    private String resultsDir;

    public InferRunner() {

    }

    public void runInferOnProject(Path inferExe) throws MojoExecutionException {
        try {
            // 1) Collect Java sources
            java.util.List<String> roots = project.getCompileSourceRoots();

            // Found 26 source files to analyze in - should be 26

            List<Path> javaSourceFiles = new ArrayList<>();

            roots.stream()
                .map(Path::of)
                .filter(Files::isDirectory)
                .forEach(rootPath -> {
                    try (Stream<Path> stream = Files.find(rootPath, Integer.MAX_VALUE,
                        (path, attrs) -> path.getFileName().toString().endsWith(".java"))) {
                        javaSourceFiles.addAll(stream.toList());
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });

            if (javaSourceFiles.isEmpty()) {
                logger.info("No Java sources found; skipping Infer.");
                return;
            }

            // 2) Build compile classpath from project artifacts
            String cp = buildCompileClasspath();

            // 3) Ensure results dir exists
            Path results = Path.of(resultsDir);
            Files.createDirectories(results);

            // 4) Prepare an @argfile for sources to avoid long command lines
            Path argfile = createArgFile("infer-sources", javaSourceFiles);

            // 5) Build javac command line used for capture
            //    We don’t change the actual compilation output; Infer just observes the invocation.
            //    -proc:none avoids annotation processors causing tools to run.
            java.util.List<String> javacArgs = new java.util.ArrayList<>();
            javacArgs.add("javac");
            if (cp != null && !cp.isBlank()) {
                javacArgs.add("-classpath");
                javacArgs.add(cp);
            }
            // Use source and target derived from Maven compiler plugin if desired.
            // For a start, rely on defaults or add simple flags:
            // javacArgs.addAll(java.util.List.of("-g", "-proc:none"));
            javacArgs.add("-g");
//            javacArgs.add("-proc:none");
            // Direct the class output to target/classes so types resolve consistently
            Path classesDir = Path.of(project.getBuild().getOutputDirectory());
            Files.createDirectories(classesDir);
            javacArgs.add("-d");
            javacArgs.add(classesDir.toString());
            // Add all sources via @argfile
            javacArgs.add("@" + argfile.toString());

            // 6) infer -- javac ... (single run phase)
            java.util.List<String> inferCmd = new java.util.ArrayList<>();
            inferCmd.add(inferExe.toString());
            inferCmd.add("--results-dir");
            inferCmd.add(results.toString());
            // Keep going on errors during capture
//            inferCmd.add("--keep-going");
//            if (additionalInferArgs != null) inferCmd.addAll(additionalInferArgs);
            inferCmd.add("--");
            inferCmd.addAll(javacArgs);

            int runExit = runAndLog(inferCmd, project.getBasedir().toPath());

            // Optionally fail the build if Infer found issues (Infer returns 2 when issues found)
//            if (failOnIssue && runExit == 2) {
            if (runExit == 2) {
                throw new MojoExecutionException("Infer found issues. See: " + results);
            }

            logger.info("Infer analysis completed. Results in: " + results);
        } catch (IOException e) {
            throw new MojoExecutionException("Failed running Infer on project", e);
        }
    }

    private String buildCompileClasspath() {
        try {
            @SuppressWarnings("unchecked")
            java.util.List<String> elements = project.getCompileClasspathElements();
            return String.join(java.io.File.pathSeparator, elements);
        } catch (org.apache.maven.artifact.DependencyResolutionRequiredException e) {
            throw new RuntimeException("Compile classpath not resolved", e);
        }
    }

    private Path createArgFile(String prefix, java.util.List<Path> files) throws IOException {
        Path dir = Path.of(project.getBuild().getDirectory());
        Files.createDirectories(dir);
        Path arg = dir.resolve(prefix + ".args");
        try (java.io.BufferedWriter w = java.nio.file.Files.newBufferedWriter(arg)) {
            for (Path p : files) {
                w.write(p.toString());
                w.newLine();
            }
        }
        return arg;
    }

    private int runAndLog(java.util.List<String> command, Path workingDir) throws IOException, MojoExecutionException {
        logger.info("Running: " + String.join(" ", command));
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workingDir.toFile());
        pb.redirectErrorStream(true);

        Process proc = pb.start();
        try (InputStream is = proc.getInputStream()) {
            byte[] buf = new byte[8192];
            int read;
            StringBuilder sb = new StringBuilder();
            while ((read = is.read(buf)) != -1) {
                String chunk = new String(buf, 0, read);
                sb.append(chunk);
                int idx;
                while ((idx = sb.indexOf("\n")) >= 0) {
                    String line = sb.substring(0, idx).stripTrailing();
                    if (!line.isBlank()) logger.info(line);
                    sb.delete(0, idx + 1);
                }
            }
            if (sb.length() > 0) logger.info(sb.toString());
        }
        try {
            boolean finished = proc.waitFor(15, java.util.concurrent.TimeUnit.MINUTES);
            if (!finished) {
                proc.destroyForcibly();
                throw new MojoExecutionException("Timeout running: " + command.get(0));
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new MojoExecutionException("Interrupted running: " + command.get(0), ie);
        }
        int exit = proc.exitValue();
        if (exit != 0 && exit != 2) {
            throw new MojoExecutionException("Command exited with code " + exit + ": " + String.join(" ", command));
        }
        return exit;
    }

    public void setProject(MavenProject project) {
        this.project = project;
    }

    public void setResultsDir(String resultsDir) {
        this.resultsDir = resultsDir;
    }
}
