package core;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import org.apache.maven.project.MavenProject;

public record InferParams(
        MavenProject project,
        boolean failOnIssue,
        boolean enableJavaCheckers,
        String resultsDir,
        Path installDir,
        List<String> extraInferArgs,
        List<String> customInferArgs) {
    public InferParams {
        extraInferArgs = extraInferArgs == null ? Collections.emptyList() : extraInferArgs;
        customInferArgs = customInferArgs == null ? Collections.emptyList() : customInferArgs;
    }
}
