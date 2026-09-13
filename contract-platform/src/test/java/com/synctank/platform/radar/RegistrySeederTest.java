package com.synctank.platform.radar;

import com.synctank.platform.report.UsageScanner;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Day 10 (F1) — an unavailable scanner must produce a FAILED seed, never an empty one.
 *
 * verifyNoInteractions is the important assertion: the refusal happens before the app row is
 * touched and before deleteByApp wipes the previous usage rows, so a failed re-seed leaves the
 * last good registry exactly as it was.
 */
class RegistrySeederTest {

    @Test
    void refusesToSeedWhenTheScannerCannotRunAndLeavesTheRegistryUntouched() {
        ClientAppRepository appRepo = mock(ClientAppRepository.class);
        ClientUsageRepository usageRepo = mock(ClientUsageRepository.class);
        SpecUsageExtractor extractor = mock(SpecUsageExtractor.class);
        UsageScanner scanner = new UsageScanner("synctank-no-such-binary-7f3a");

        RegistrySeeder seeder = new RegistrySeeder(appRepo, usageRepo, extractor, scanner);

        RegistrySeeder.SeedRequest request = new RegistrySeeder.SeedRequest(
                "customer-portal", "Team Checkout", "jgantayat/synctank", "test-sha",
                System.getProperty("java.io.tmpdir"), "{}");

        assertThatThrownBy(() -> seeder.seed(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Usage scanner unavailable");

        verifyNoInteractions(appRepo, usageRepo, extractor);
    }
}