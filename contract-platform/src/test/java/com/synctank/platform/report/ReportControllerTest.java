package com.synctank.platform.report;

import com.synctank.platform.diff.Severity;
import com.synctank.platform.radar.ContractImpactReport;
import com.synctank.platform.radar.EffectiveSeverity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Day 12 (F4) — /report rejects a source root it cannot scan, instead of a 500 (null) or a
 * ripgrep error message reported as a usage hit (missing directory).
 *
 * Tests the static validator directly: no Spring context, no ChatClient, no ripgrep.
 */
class ReportControllerTest {

    private static final ContractImpactReport EMPTY_DIFF = new ContractImpactReport(
            false, Severity.ADDITIVE, List.of(), "", EffectiveSeverity.ADDITIVE, List.of());

    @Test
    void aMissingSourcePathIsABadRequestNotANullPointer() {
        assertBadRequest(new ReportController.ReportRequest(EMPTY_DIFF, null), "frontendSrcPath is required");
        assertBadRequest(new ReportController.ReportRequest(EMPTY_DIFF, "  "), "frontendSrcPath is required");
    }

    @Test
    void aPathThatIsNotADirectoryOnThisHostIsABadRequest() {
        assertBadRequest(new ReportController.ReportRequest(EMPTY_DIFF, "/synctank/no/such/dir-7f3a"),
                "not a directory");
    }

    @Test
    void aMissingDiffIsABadRequest(@TempDir Path dir) {
        assertBadRequest(new ReportController.ReportRequest(null, dir.toString()), "diff is required");
    }

    @Test
    void anExistingDirectoryIsAccepted(@TempDir Path dir) {
        assertThat(ReportController.validSourceRoot(
                new ReportController.ReportRequest(EMPTY_DIFF, dir.toString()))).isEqualTo(dir);
    }

    private static void assertBadRequest(ReportController.ReportRequest request, String reasonFragment) {
        assertThatThrownBy(() -> ReportController.validSourceRoot(request))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getReason()).contains(reasonFragment);
                });
    }
}