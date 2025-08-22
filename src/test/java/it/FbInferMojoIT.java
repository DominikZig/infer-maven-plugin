package it;

import static com.soebes.itf.extension.assertj.MavenITAssertions.assertThat;

import com.soebes.itf.jupiter.extension.MavenJupiterExtension;
import com.soebes.itf.jupiter.extension.MavenTest;
import com.soebes.itf.jupiter.maven.MavenExecutionResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.AfterAll;

@MavenJupiterExtension
class FbInferMojoIT {

    @AfterAll
    static void cleanUp() throws IOException {
        Path userHomeDownloadsPath = Path.of(System.getProperty("user.home"), "Downloads");
        FileUtils.deleteDirectory(
                userHomeDownloadsPath.resolve("infer-linux-x86_64-v1.2.0").toFile());
    }

    @MavenTest
    void successfully_runs_infer_no_issues_found(MavenExecutionResult result) {
        assertThat(result).isSuccessful();
        Path inferOut = result.getMavenProjectResult()
                .getTargetProjectDirectory()
                .resolve("target")
                .resolve("infer-out");
        assertThat(inferOut).isDirectory();
        assertThat(inferOut.resolve("report.txt")).isEmptyFile();
    }

    @MavenTest
    void successfully_runs_infer_npe_issue_found(MavenExecutionResult result) {
        assertThat(result).isFailure();
        Path inferOut = result.getMavenProjectResult()
                .getTargetProjectDirectory()
                .resolve("target")
                .resolve("infer-out");
        assertThat(inferOut).isDirectory();
        Path expectedReportPath = Path.of(
                "src/test/resources-its/it/FbInferMojoIT/successfully_runs_infer_npe_issue_found/expectedInferReport.txt");
        assertThat(inferOut.resolve("report.txt")).hasSameTextualContentAs(expectedReportPath);
    }

    @MavenTest
    void successfully_runs_infer_threadsafety_issue_found(MavenExecutionResult result) {
        assertThat(result).isFailure();
        Path inferOut = result.getMavenProjectResult()
                .getTargetProjectDirectory()
                .resolve("target")
                .resolve("infer-out");
        assertThat(inferOut).isDirectory();
        Path expectedReportPath = Path.of(
                "src/test/resources-its/it/FbInferMojoIT/successfully_runs_infer_threadsafety_issue_found/expectedInferReport.txt");
        assertThat(inferOut.resolve("report.txt")).hasSameTextualContentAs(expectedReportPath);
    }

    @MavenTest
    void successfully_runs_infer_multiple_issues_found(MavenExecutionResult result) {
        assertThat(result).isFailure();
        Path inferOut = result.getMavenProjectResult()
                .getTargetProjectDirectory()
                .resolve("target")
                .resolve("infer-out");
        assertThat(inferOut).isDirectory();
        Path expectedReportPath = Path.of(
                "src/test/resources-its/it/FbInferMojoIT/successfully_runs_infer_multiple_issues_found/expectedInferReport.txt");
        assertThat(inferOut.resolve("report.txt")).hasSameTextualContentAs(expectedReportPath);
    }

    @MavenTest
    void successfully_runs_infer_issues_found_do_not_fail_build(MavenExecutionResult result) {
        assertThat(result)
                .isSuccessful(); // even though issues are found we do NOT fail build as failOnIssue param is false
        Path inferOut = result.getMavenProjectResult()
                .getTargetProjectDirectory()
                .resolve("target")
                .resolve("infer-out");
        assertThat(inferOut).isDirectory();
        Path expectedReportPath = Path.of(
                "src/test/resources-its/it/FbInferMojoIT/successfully_runs_infer_issues_found_do_not_fail_build/expectedInferReport.txt");
        assertThat(inferOut.resolve("report.txt")).hasSameTextualContentAs(expectedReportPath);
    }

    @MavenTest
    void successfully_runs_infer_custom_install_dir(MavenExecutionResult result) throws IOException {
        assertThat(result).isSuccessful();
        Path inferOut = result.getMavenProjectResult()
                .getTargetProjectDirectory()
                .resolve("target")
                .resolve("infer-out");
        assertThat(inferOut).isDirectory();
        assertThat(inferOut.resolve("report.txt")).isEmptyFile();
        Path expectedCustomInferPath = Path.of(System.getProperty("user.home"))
                .resolve("somecustomdir")
                .resolve("infer-linux-x86_64-v1.2.0")
                .resolve("bin")
                .resolve("infer");
        assertThat(Files.exists(expectedCustomInferPath)).isTrue();

        // cleanup manually
        FileUtils.deleteDirectory(Path.of(System.getProperty("user.home"))
                .resolve("somecustomdir")
                .toFile());
    }

    @MavenTest
    void successfully_runs_infer_custom_results_dir(MavenExecutionResult result) throws IOException {
        Path userHome = Path.of(System.getProperty("user.home"));

        assertThat(result).isSuccessful();
        Path inferOut = userHome.resolve("somecustomdir").resolve("infer-out");
        assertThat(Files.exists(inferOut)).isTrue();
        assertThat(inferOut).isDirectory();
        assertThat(inferOut.resolve("report.txt")).isEmptyFile();

        // cleanup manually
        FileUtils.deleteDirectory(userHome.resolve("somecustomdir").toFile());
    }
}
