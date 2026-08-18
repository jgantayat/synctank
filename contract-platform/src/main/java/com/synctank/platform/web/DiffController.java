package com.synctank.platform.web;

import com.synctank.platform.diff.DiffReport;
import com.synctank.platform.diff.DiffService;
import com.synctank.platform.radar.ContractImpactReport;
import com.synctank.platform.radar.ImpactRadarService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/diff")
public class DiffController {

    private final DiffService diffService;
    private final ImpactRadarService radar;

    public DiffController(DiffService diffService, ImpactRadarService radar) {
        this.diffService = diffService;
        this.radar = radar;
    }

    public record DiffRequest(String baseline, String candidate) {}

    /**
     * Day 06: the response is now ContractImpactReport instead of DiffReport.
     *
     * Enrichment happens HERE rather than inside DiffService, so that DiffService stays a pure
     * function with no database dependency and Day 03's tests keep running without a datasource.
     * ContractImpactReport is a JSON superset of DiffReport, so existing CI jq expressions and
     * the /report endpoint's request body both keep working.
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ContractImpactReport diff(@RequestBody DiffRequest request) {
        DiffReport report = diffService.diff(request.baseline(), request.candidate());
        return radar.enrich(report);
    }
}