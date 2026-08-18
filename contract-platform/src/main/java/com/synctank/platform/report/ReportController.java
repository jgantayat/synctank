package com.synctank.platform.report;

import com.synctank.platform.radar.ContractImpactReport;
import org.springframework.web.bind.annotation.*;

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
        return changeReportService.generateReport(request.diff(), Path.of(request.frontendSrcPath()));
    }
}