package com.synctank.platform.report;

import com.synctank.platform.ai.AiProperties;
import com.synctank.platform.ai.AiUsageMeter;
import com.synctank.platform.diff.ChangeRecord;
import com.synctank.platform.diff.Severity;
import com.synctank.platform.radar.ContractImpactReport;
import com.synctank.platform.radar.EffectiveSeverity;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Day 10 (F6) — two identical ChangeRecords must reach the deterministic fallback, not a 500.
 *
 * The ChatClient.Builder mock returns null from build(), so the AI call fails inside
 * generateReport's try block — exactly the "AI outage" path the fallback exists for. Before the
 * fix, the test never got that far: Collectors.toMap threw "Duplicate key" first, outside the try.
 *
 * Day 11 — the same failing call now runs through AiUsageMeter, so this test doubles as proof
 * that a failed model call is counted rather than swallowed.
 */
class ChangeReportServiceTest {

    @Test
    void duplicateChangeRecordsFallBackToTheDeterministicReportInsteadOfThrowing() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        AiUsageMeter meter = new AiUsageMeter(new AiProperties(200, 0.005));
        ChangeReportService service = new ChangeReportService(
                builder, new UsageScanner("synctank-no-such-binary-7f3a"), meter);

        ChangeRecord record = new ChangeRecord(Severity.ADDITIVE, "FIELD_ADDED",
                "OrderResponse.customerEmail", "Optional field added.");
        ContractImpactReport diff = new ContractImpactReport(true, Severity.ADDITIVE,
                List.of(record, record), "", EffectiveSeverity.ADDITIVE, List.of());

        ContractChangeReport report = service.generateReport(
                diff, Path.of(System.getProperty("java.io.tmpdir")));

        assertThat(report.summary()).startsWith("Automated AI narration failed");
        assertThat(report.changes()).hasSize(2);

        // Day 11 — the failed call was metered, not invisible.
        assertThat(meter.snapshot().callsToday()).isEqualTo(1);
        assertThat(meter.snapshot().failuresToday()).isEqualTo(1);
    }

    // ---------- Pre-Day-13 (A2) — no key means no call, and says so ----------

    @Test
    void withoutAnApiKeyNoModelCallIsMadeAndTheSummarySaysWhy() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        AiUsageMeter meter = new AiUsageMeter(new AiProperties(200, 0.005));
        ChangeReportService service = new ChangeReportService(
                builder, new UsageScanner("synctank-no-such-binary-7f3a"), meter, false);

        ChangeRecord record = new ChangeRecord(Severity.BREAKING, "FIELD_REMOVED",
                "Payment.currency", "'Payment.currency' was removed.");
        ContractImpactReport diff = new ContractImpactReport(true, Severity.BREAKING,
                List.of(record), "", EffectiveSeverity.BREAKING, List.of());

        ContractChangeReport report = service.generateReport(
                diff, Path.of(System.getProperty("java.io.tmpdir")));

        assertThat(report.summary()).isEqualTo(ChangeReportService.NOT_CONFIGURED_SUMMARY);
        assertThat(report.changes()).hasSize(1);
        assertThat(report.openQuestions()).isEmpty();
        // The Day 11 cap is untouched: nothing was attempted.
        assertThat(meter.snapshot().callsToday()).isZero();
    }

    @Test
    void thePlaceholderKeyIsNotAConfiguredKey() {
        assertThat(ChangeReportService.isConfigured("not-configured")).isFalse();
        assertThat(ChangeReportService.isConfigured(" ")).isFalse();
        assertThat(ChangeReportService.isConfigured(null)).isFalse();
        assertThat(ChangeReportService.isConfigured("any-real-looking-key")).isTrue();    }

    // ---------- Pre-Day-13 (B3) — endpoint locations are never grepped ----------

    @Test
    void endpointLevelChangesGetNoUsageScanAndSoNoFakeHits() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        AiUsageMeter meter = new AiUsageMeter(new AiProperties(200, 0.005));
        // A missing binary makes every scan that DOES run return a "(usage scan failed" line —
        // so an empty list below proves the scan was skipped, not that it found nothing.
        ChangeReportService service = new ChangeReportService(
                builder, new UsageScanner("synctank-no-such-binary-7f3a"), meter, false);

        ChangeRecord removed = new ChangeRecord(Severity.BREAKING, "ENDPOINT_REMOVED",
                "GET /api/orders/{id}", "Endpoint removed.");
        ContractImpactReport diff = new ContractImpactReport(true, Severity.BREAKING,
                List.of(removed), "", EffectiveSeverity.BREAKING, List.of());

        ContractChangeReport report = service.generateReport(
                diff, Path.of(System.getProperty("java.io.tmpdir")));

        assertThat(report.changes().get(0).usageHits()).isEmpty();
    }
}