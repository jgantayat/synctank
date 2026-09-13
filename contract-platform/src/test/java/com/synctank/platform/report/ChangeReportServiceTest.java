package com.synctank.platform.report;

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
 */
class ChangeReportServiceTest {

    @Test
    void duplicateChangeRecordsFallBackToTheDeterministicReportInsteadOfThrowing() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        ChangeReportService service = new ChangeReportService(
                builder, new UsageScanner("synctank-no-such-binary-7f3a"));

        ChangeRecord record = new ChangeRecord(Severity.ADDITIVE, "FIELD_ADDED",
                "OrderResponse.customerEmail", "Optional field added.");
        ContractImpactReport diff = new ContractImpactReport(true, Severity.ADDITIVE,
                List.of(record, record), "", EffectiveSeverity.ADDITIVE, List.of());

        ContractChangeReport report = service.generateReport(
                diff, Path.of(System.getProperty("java.io.tmpdir")));

        assertThat(report.summary()).startsWith("Automated AI narration failed");
        assertThat(report.changes()).hasSize(2);
    }
}