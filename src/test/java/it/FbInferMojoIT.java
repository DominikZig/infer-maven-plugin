package it;

import static com.soebes.itf.extension.assertj.MavenITAssertions.assertThat;

import com.soebes.itf.jupiter.extension.MavenJupiterExtension;
import com.soebes.itf.jupiter.extension.MavenTest;
import com.soebes.itf.jupiter.maven.MavenExecutionResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.AfterAll;

@MavenJupiterExtension
class FbInferMojoIT {

    private static final String INFER_ISSUES_FOUND =
            "Infer analysis completed with issues found, causing the build to fail. Check Infer results for more info.";

    @AfterAll
    static void cleanUp() throws IOException {
        Path userHomeDownloadsPath = Path.of(System.getProperty("user.home"), "Downloads");

        // Make sure any Infer distributions are cleaned up regardless of OS
        List<String> inferDirs = List.of("infer-linux-x86_64-v1.2.0", "infer-osx-arm64-v1.2.0");

        for (String dir : inferDirs) {
            FileUtils.deleteDirectory(userHomeDownloadsPath.resolve(dir).toFile());
        }
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

        List<String> expectedMavenLogs = List.of(
                "Found 1 issue",
                "             Issue Type(ISSUED_TYPE_ID): #",
                "  Null Dereference(NULLPTR_DEREFERENCE): 1");
        assertThat(result).out().info().containsAll(expectedMavenLogs);
        assertThat(result).out().warn().contains(INFER_ISSUES_FOUND);
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

        List<String> expectedMavenLogs = List.of(
                "Found 1 issue",
                "                        Issue Type(ISSUED_TYPE_ID): #",
                "  Thread Safety Violation(THREAD_SAFETY_VIOLATION): 1");
        assertThat(result).out().info().containsAll(expectedMavenLogs);
        assertThat(result).out().warn().contains(INFER_ISSUES_FOUND);
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

        List<String> expectedMavenLogs = List.of(
                "Found 2 issues",
                "                        Issue Type(ISSUED_TYPE_ID): #",
                "  Thread Safety Violation(THREAD_SAFETY_VIOLATION): 1",
                "             Null Dereference(NULLPTR_DEREFERENCE): 1");
        assertThat(result).out().info().containsAll(expectedMavenLogs);
        assertThat(result).out().warn().contains(INFER_ISSUES_FOUND);
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

        List<String> expectedMavenLogs = List.of(
                "Found 1 issue",
                "             Issue Type(ISSUED_TYPE_ID): #",
                "  Null Dereference(NULLPTR_DEREFERENCE): 1");
        assertThat(result).out().info().containsAll(expectedMavenLogs);
        assertThat(result)
                .out()
                .warn()
                .doesNotContain(INFER_ISSUES_FOUND); // does not show log stating build has failed due to Infer issues
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
        String expectedInferDir =
                System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("linux")
                        ? "infer-linux-x86_64-v1.2.0"
                        : "infer-osx-arm64-v1.2.0";
        Path expectedCustomInferPath = Path.of(System.getProperty("user.home"))
                .resolve("somecustomdir")
                .resolve(expectedInferDir)
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
