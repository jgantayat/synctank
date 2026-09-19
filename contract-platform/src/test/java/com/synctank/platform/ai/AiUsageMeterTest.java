package com.synctank.platform.ai;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Day 11 — the cap is the only thing standing between a retry loop and an uncapped bill on
 * a card AWS cannot see, so it gets tested rather than trusted.
 */
class AiUsageMeterTest {

    @Test
    void refusesTheCallThatWouldExceedTheDailyLimit() {
        AiUsageMeter meter = new AiUsageMeter(new AiProperties(2, 0.005));

        assertThat(meter.meter("test", () -> "one")).isEqualTo("one");
        assertThat(meter.meter("test", () -> "two")).isEqualTo("two");

        assertThatThrownBy(() -> meter.meter("test", () -> "three"))
                .isInstanceOf(AiUsageMeter.BudgetExceededException.class)
                .hasMessageContaining("daily call limit (2)");

        AiUsageMeter.Snapshot snapshot = meter.snapshot();
        assertThat(snapshot.callsToday()).isEqualTo(2);
        assertThat(snapshot.remainingToday()).isZero();
        assertThat(snapshot.estimatedCostUsdToday()).isEqualTo(0.01);
    }

    @Test
    void countsAFailedCallAgainstTheCapAndStillRethrows() {
        AiUsageMeter meter = new AiUsageMeter(new AiProperties(5, 0.005));

        assertThatThrownBy(() -> meter.meter("test", () -> {
            throw new IllegalStateException("upstream exploded");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(meter.snapshot().callsToday()).isEqualTo(1);
        assertThat(meter.snapshot().failuresToday()).isEqualTo(1);
    }

    @Test
    void countersResetWhenTheUtcDayRollsOver() {
        // 23:59:30 UTC, then 00:00:30 the next day — the meter reads the clock, so the
        // rollover is testable without waiting for midnight.
        Clock justBeforeMidnight = Clock.fixed(Instant.parse("2026-09-13T23:59:30Z"), ZoneOffset.UTC);
        AiUsageMeter meter = new AiUsageMeter(new AiProperties(1, 0.005), justBeforeMidnight);

        meter.meter("test", () -> "spent the day's only call");
        assertThat(meter.snapshot().remainingToday()).isZero();

        AiUsageMeter tomorrow = new AiUsageMeter(new AiProperties(1, 0.005),
                Clock.fixed(Instant.parse("2026-09-14T00:00:30Z"), ZoneOffset.UTC));
        assertThat(tomorrow.snapshot().callsToday()).isZero();
        assertThat(tomorrow.snapshot().utcDay()).isEqualTo("2026-09-14");
    }
}