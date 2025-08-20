package it;

import com.soebes.itf.jupiter.extension.MavenJupiterExtension;
import com.soebes.itf.jupiter.extension.MavenTest;
import com.soebes.itf.jupiter.maven.MavenExecutionResult;
import java.io.IOException;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.AfterEach;

import java.nio.file.Path;

import static com.soebes.itf.extension.assertj.MavenITAssertions.assertThat;

@MavenJupiterExtension
class FbInferMojoIT {

    @AfterEach
    void tearDown() throws IOException {
        Path userHomeDownloadsPath = Path.of(System.getProperty("user.home"), "Downloads");
        FileUtils.deleteDirectory(userHomeDownloadsPath.resolve("infer-linux-x86_64-v1.2.0").toFile());
    }

    @MavenTest
    void successfully_runs_infer_no_issues_found(MavenExecutionResult result) {
        assertThat(result).isSuccessful();
        Path inferOut = result.getMavenProjectResult().getTargetProjectDirectory()
            .resolve("target")
            .resolve("infer-out");
        assertThat(inferOut).isDirectory();
        assertThat(inferOut.resolve("report.txt")).isEmptyFile();
    }

    @MavenTest
    void successfully_runs_infer_npe_issue_found(MavenExecutionResult result) {
        assertThat(result).isFailure();
        Path inferOut = result.getMavenProjectResult().getTargetProjectDirectory()
            .resolve("target")
            .resolve("infer-out");
        assertThat(inferOut).isDirectory();
        Path expectedReportPath = Path.of("src/test/resources-its/it/FbInferMojoIT/successfully_runs_infer_npe_issue_found/expectedInferReport.txt");
        assertThat(inferOut.resolve("report.txt")).hasSameTextualContentAs(expectedReportPath);
    }

    @MavenTest
    void successfully_runs_infer_threadsafety_issue_found(MavenExecutionResult result) {
        assertThat(result).isFailure();
        Path inferOut = result.getMavenProjectResult().getTargetProjectDirectory()
            .resolve("target")
            .resolve("infer-out");
        assertThat(inferOut).isDirectory();
        Path expectedReportPath = Path.of("src/test/resources-its/it/FbInferMojoIT/successfully_runs_infer_threadsafety_issue_found/expectedInferReport.txt");
        assertThat(inferOut.resolve("report.txt")).hasSameTextualContentAs(expectedReportPath);
    }
}
