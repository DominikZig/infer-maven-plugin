package mojo;

import core.InferInstaller;
import core.InferParams;
import core.InferRunner;
import java.io.File;
import java.nio.file.Path;
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

    @Inject
    public FbInferMojo(InferInstaller installer, InferRunner runner) {
        this.installer = installer;
        this.runner = runner;
    }

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        InferParams inferParams =
                new InferParams(project, failOnIssue, enableJavaCheckers, resultsDir, installDir.toPath());

        Path inferExe = installer.tryInstallInfer(inferParams.installDir());

        runner.runInferOnProject(inferParams, inferExe);
    }
}
