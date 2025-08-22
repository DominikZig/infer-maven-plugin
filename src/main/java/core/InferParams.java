package core;

import java.nio.file.Path;
import org.apache.maven.project.MavenProject;

public record InferParams(MavenProject project, boolean failOnIssue, String resultsDir, Path installDir) {}
