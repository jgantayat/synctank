package com.synctank.platform.ai;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Day 11 — GET /health/ai. "How much has this instance spent today, and how close is it
 * to its cap?" answered without a trip to a billing console that cannot see this spend.
 *
 * Deliberately NOT added to DashboardCorsConfig, for the same reason /health/secrets is
 * not: no browser page on an allowed origin needs to read the platform's operating state.
 * Read it with curl, or in the CloudWatch dashboard built from the AI_CALL log metric.
 */
@RestController
public class AiUsageController {

    private final AiUsageMeter meter;

    public AiUsageController(AiUsageMeter meter) {
        this.meter = meter;
    }

    @GetMapping(value = "/health/ai", produces = MediaType.APPLICATION_JSON_VALUE)
    public AiUsageMeter.Snapshot ai() {
        return meter.snapshot();
    }
}