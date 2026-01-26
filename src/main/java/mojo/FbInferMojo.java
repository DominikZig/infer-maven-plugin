package io.github.dominikzig.infer.maven.plugin.mojo;

import io.github.dominikzig.infer.maven.plugin.core.InferInstaller;
import io.github.dominikzig.infer.maven.plugin.core.InferParams;
import io.github.dominikzig.infer.maven.plugin.core.InferRunner;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.inject.Inject;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

@Mojo(
        name = "infer-plugin",
        defaultPhase = LifecyclePhase.COMPILE,
        requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME)
public class FbInferMojo extends AbstractMojo {

    private final InferInstaller installer;

    private final InferRunner runner;

    @Parameter(defaultValue = "${project}", required = true, readonly = true)
    private MavenProject project;

    @Parameter(property = "failOnIssue", defaultValue = "true")
    private boolean failOnIssue;

    @Parameter(property = "enableJavaCheckers", defaultValue = "true")
    private boolean enableJavaCheckers;

    @Parameter(property = "resultsDir", defaultValue = "${project.build.directory}/infer-out")
    private String resultsDir;

    @Parameter(property = "installDir", defaultValue = "${user.home}/Downloads")
    private File installDir;

    @Parameter(property = "extraArgs")
    private List<String> extraArgs = new ArrayList<>();

    @Parameter(property = "customArgs")
    private List<String> customArgs = new ArrayList<>();

    // No-arg constructor required by some Mojo containers; keep for compatibility.
    public FbInferMojo() {
        this.installer = null;
        this.runner = null;
    }

    @Inject
    public FbInferMojo(InferInstaller installer, InferRunner runner) {
        this.installer = installer;
        this.runner = runner;
    }

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        Objects.requireNonNull(project, "MavenProject must not be null");
        Objects.requireNonNull(installDir, "installDir must not be null");
        Objects.requireNonNull(resultsDir, "resultsDir must not be null");

        // Defensive copies to avoid later external mutation
        List<String> safeExtraArgs = extraArgs == null ? List.of() : List.copyOf(extraArgs);
        List<String> safeCustomArgs = customArgs == null ? List.of() : List.copyOf(customArgs);

        InferParams inferParams = new InferParams(
                project,
                failOnIssue,
                enableJavaCheckers,
                resultsDir,
                installDir.toPath(),
                safeExtraArgs,
                safeCustomArgs);

        Path inferExe = installer.tryInstallInfer(inferParams.installDir());

        runner.runInferOnProject(inferParams, inferExe);
    }
}