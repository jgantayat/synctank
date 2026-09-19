package com.synctank.platform.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Day 11 — the platform's AI spend guardrail, as configuration.
 *
 * Picked up by @ConfigurationPropertiesScan on ContractPlatformApplication, same as
 * AgentProperties and S3Props. @DefaultValue on every component means the record binds
 * even where the properties are absent entirely — which is what keeps a bare `mvn test`
 * and any future test-classpath yaml working without an edit.
 *
 * WHY A CALL CAP AND NOT A TOKEN BUDGET: Spring AI's .call().entity(Class) returns the
 * mapped object and discards the ChatResponse, so token usage is not reachable without
 * changing how ChangeReportService and ContractAgentService invoke the model. Both are
 * working, demo-critical paths. A call cap gives the same thing that actually matters
 * operationally — a hard stop — and the cost figure derived from it is labelled an
 * estimate everywhere it is shown.
 */
@ConfigurationProperties(prefix = "platform.ai")
public record AiProperties(

        /** Hard stop. The call that would exceed this is refused, not queued. */
        @DefaultValue("200") int dailyCallLimit,

        /**
         * Estimate only, and always presented as one. Claude Haiku 4.5 at roughly 2k input
         * and 800 output tokens per change report lands near half a US cent per call.
         * Adjust after you have a real invoice; nothing in the platform depends on it
         * being exact, and no decision is made from it.
         */
        @DefaultValue("0.005") double estimatedCostPerCallUsd) {
}