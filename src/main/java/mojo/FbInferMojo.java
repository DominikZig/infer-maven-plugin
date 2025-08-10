package mojo;

import core.InferInstaller;
import core.InferRunner;
import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import javax.inject.Inject;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;
import org.apache.commons.io.FileUtils;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
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

//    @Parameter(property = "infer.failOnIssue", defaultValue = "false")
//    private boolean failOnIssue;

//    @Parameter(property = "infer.additionalArgs")
//    private java.util.List<String> additionalInferArgs;

    public void execute() throws MojoExecutionException {
        Path inferExe = installer.installInfer();

        runner.setProject(project);
        runner.setResultsDir(resultsDir);
        runner.runInferOnProject(inferExe);
    }
}
