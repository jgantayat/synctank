package com.synctank.platform.report;

import com.synctank.platform.diff.DiffReport;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;

@RestController
@RequestMapping("/report")
public class ReportController {

    private final ChangeReportService changeReportService;

    public ReportController(ChangeReportService changeReportService) {
        this.changeReportService = changeReportService;
    }

    public record ReportRequest(DiffReport diff, String frontendSrcPath) {}

    @PostMapping(produces = "application/json", consumes = "application/json")
    public ChangeReport generate(@RequestBody ReportRequest request) {
        return changeReportService.generateReport(request.diff(), Path.of(request.frontendSrcPath()));
    }
}
