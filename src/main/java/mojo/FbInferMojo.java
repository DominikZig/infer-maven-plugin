package mojo;

import core.InferInstaller;
import core.InferRunner;
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

@Mojo(name = "infer-plugin", defaultPhase = LifecyclePhase.COMPILE,
    requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME)
public class FbInferMojo extends AbstractMojo {

    @Inject
    private InferInstaller installer;

    @Inject
    private InferRunner runner;

    @Parameter(defaultValue = "${project}", required = true, readonly = true)
    private MavenProject project;

    @Parameter(property = "infer.resultsDir", defaultValue = "${project.build.directory}/infer-out")
    private String resultsDir;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        Path inferExe = installer.tryInstallInfer();

        runner.setProject(project);
        runner.setResultsDir(resultsDir);
        runner.runInferOnProject(inferExe);
    }
}
