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
        List<String> extraArgs,
        List<String> customArgs) {
    public InferParams {
        extraArgs = extraArgs == null ? Collections.emptyList() : extraArgs;
        customArgs = customArgs == null ? Collections.emptyList() : customArgs;
    }
}
