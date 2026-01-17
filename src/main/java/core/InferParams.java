package core;

import java.nio.file.Path;
import org.apache.maven.project.MavenProject;

public record InferParams(
        MavenProject project,
        boolean failOnIssue,
        boolean enableJavaCheckers,
        String resultsDir,
        Path installDir,
        java.util.List<String> extraInferArgs) {
    public InferParams {
        extraInferArgs = extraInferArgs == null ? java.util.List.of() : extraInferArgs;
    }
}
