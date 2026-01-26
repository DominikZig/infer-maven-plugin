package io.github.dominikzig.infer.maven.plugin.core;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.apache.maven.project.MavenProject;

/**
 * Parameter holder for Infer invocation. Makes defensive copies of the provided lists and exposes
 * unmodifiable lists to callers.
 */
public record InferParams(
        MavenProject project,
        boolean failOnIssue,
        boolean enableJavaCheckers,
        String resultsDir,
        Path installDir,
        List<String> extraArgs,
        List<String> customArgs) {
    public InferParams {
        Objects.requireNonNull(project, "project must not be null");
        Objects.requireNonNull(resultsDir, "resultsDir must not be null");
        // Make defensive copies and ensure non-null unmodifiable lists
        extraArgs = extraArgs == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(extraArgs));
        customArgs = customArgs == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(customArgs));
    }
}