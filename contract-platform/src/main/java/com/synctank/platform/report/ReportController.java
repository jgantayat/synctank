package com.synctank.platform.report;

import com.synctank.platform.radar.ContractImpactReport;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Files;
import java.nio.file.Path;

@RestController
@RequestMapping("/report")
public class ReportController {

    private final ChangeReportService changeReportService;

    public ReportController(ChangeReportService changeReportService) {
        this.changeReportService = changeReportService;
    }

    /**
     * Day 06: `diff` is now ContractImpactReport rather than DiffReport.
     * CI posts the raw body of /diff's response here unchanged, so the extra
     * effectiveSeverity/impact fields deserialise instead of being silently dropped.
     */
    public record ReportRequest(ContractImpactReport diff, String frontendSrcPath) {}

    @PostMapping(produces = "application/json", consumes = "application/json")
    public ContractChangeReport generate(@RequestBody ReportRequest request) {
        return changeReportService.generateReport(request.diff(), validSourceRoot(request));
    }

    /**
     * Day 12 (F4) — reject a request that cannot produce a truthful report, BEFORE scanning.
     *
     * Two failure modes existed:
     *   - no frontendSrcPath: Path.of(null) threw a NullPointerException -> anonymous 500;
     *   - a path that does not exist on THIS host (the usual mistake once the platform runs in a
     *     container or on ECS): UsageScanner merges ripgrep's stderr into its hits, so
     *     "rg: /x/src: No such file or directory (os error 2)" came back as a USAGE HIT and was
     *     printed on the pull request as a file that uses the field.
     * RegistryController has returned 400 for the same mistake since Day 10; this matches it.
     * Callers with no consumer source to scan pass an empty directory (the Day 12 action does).
     */
    static Path validSourceRoot(ReportRequest request) {
        if (request == null || request.diff() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "diff is required — POST the body /diff returned");
        }
        String raw = request.frontendSrcPath();
        if (raw == null || raw.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "frontendSrcPath is required — pass an ABSOLUTE path to the consumer's source "
                            + "directory, or an empty directory when there is none to scan");
        }
        Path root = Path.of(raw);
        if (!Files.isDirectory(root)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "frontendSrcPath is not a directory on the platform host: " + root.toAbsolutePath());
        }
        return root;
    }
}