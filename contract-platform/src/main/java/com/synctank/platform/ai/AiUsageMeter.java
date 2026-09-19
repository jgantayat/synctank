package com.synctank.platform.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Day 11 — every call to the model goes through here, and the one that would exceed the
 * daily cap never leaves the process.
 *
 * Why this exists (audit finding F5): the platform calls api.anthropic.com directly, not
 * Bedrock. AWS Budgets, Cost Explorer and every CloudWatch billing alarm are therefore
 * structurally blind to the platform's largest variable cost. The guardrail has to live
 * inside the application because there is nowhere else it can live.
 *
 * FAILS CLOSED, AND CLOSED IS A PATH THAT ALREADY EXISTS. BudgetExceededException is a
 * RuntimeException thrown from inside the supplier's call site, so:
 *   - ChangeReportService's existing catch(Exception) produces the deterministic report,
 *     exactly as it does for a rate limit or a network timeout (Day 05's design);
 *   - ContractAgentService returns a BLOCKED draft naming the cap (Day 07's guardrail
 *     vocabulary), rather than a 500 on stage.
 * Nothing about the deterministic pipeline, the classifier or the Impact Radar depends on
 * the model, so a tripped cap degrades narration and drafting and nothing else.
 *
 * Counters are per-process and per-UTC-day, and reset when the task restarts. That is a
 * real limitation of an in-memory meter and is stated in the guide rather than hidden: it
 * is a spend *guardrail*, not an accounting ledger. The CloudWatch metric filter in §7
 * counts the same calls durably, from the log line below.
 */
@Component
public class AiUsageMeter {



    private static final Logger log = LoggerFactory.getLogger(AiUsageMeter.class);

    /** Public so callers in other packages can catch it by type; see ContractAgentService. */
    public static class BudgetExceededException extends RuntimeException {
        BudgetExceededException(String message) {
            super(message);
        }
    }

    /** What GET /health/ai returns. No credential, no prompt, no response content. */
    public record Snapshot(String utcDay, int callsToday, int failuresToday,
                           int dailyCallLimit, int remainingToday,
                           double estimatedCostPerCallUsd, double estimatedCostUsdToday,
                           Instant lastCallAt) {}

    private final AiProperties props;
    private final Clock clock;

    private LocalDate day;
    private int calls;
    private int failures;
    private Instant lastCallAt;

    @Autowired
    public AiUsageMeter(AiProperties props) {
        this(props, Clock.systemUTC());
    }

    /** Package-private, for the test that has to control the calendar. */
    AiUsageMeter(AiProperties props, Clock clock) {
        this.props = props;
        this.clock = clock;
        this.day = LocalDate.now(clock);
    }

    /**
     * Run one model call under the cap.
     *
     * reserve() increments BEFORE the call rather than after, so two concurrent requests
     * cannot both pass a limit check and then both spend. A failed call still counts
     * against the cap: a retry storm against a failing upstream is precisely the runaway
     * this guard exists to stop, and the tokens for a failed call are not reliably zero.
     */
    public <T> T meter(String operation, Supplier<T> call) {
        reserve(operation);
        long startedNanos = System.nanoTime();
        boolean ok = false;
        try {
            T result = call.get();
            ok = true;
            return result;
        } finally {
            complete(operation, ok, (System.nanoTime() - startedNanos) / 1_000_000L);
        }
    }

    private synchronized void reserve(String operation) {
        rollOverIfNewDay();
        if (calls >= props.dailyCallLimit()) {
            log.warn("AI_CALL op={} outcome=refused reason=daily-limit callsToday={} limit={}",
                    operation, calls, props.dailyCallLimit());
            throw new BudgetExceededException(
                    "The platform's AI daily call limit (" + props.dailyCallLimit()
                            + ") has been reached for " + day + " UTC. Raise platform.ai.daily-call-limit "
                            + "(AI_DAILY_CALL_LIMIT) or wait for the UTC day to roll over. Deterministic "
                            + "classification and the Impact Radar are unaffected by this.");
        }
        calls++;
        lastCallAt = clock.instant();
    }

    /**
     * One line, one call, always emitted — this is what the CloudWatch metric filter in
     * §7 counts. Kept as a flat key=value line on purpose: it is greppable by a human
     * tailing logs AND matchable by a plain-text metric filter pattern, which means the
     * alarm does not depend on the structured-logging format staying exactly as it is.
     */
    private synchronized void complete(String operation, boolean ok, long durationMs) {
        if (!ok) {
            failures++;
        }
        log.info("AI_CALL op={} outcome={} durationMs={} callsToday={} limit={} estCostUsdToday={}",
                operation, ok ? "ok" : "failed", durationMs, calls, props.dailyCallLimit(),
                String.format("%.4f", estimatedCostUsd()));
    }

    public synchronized Snapshot snapshot() {
        rollOverIfNewDay();
        return new Snapshot(
                day.toString(),
                calls,
                failures,
                props.dailyCallLimit(),
                Math.max(0, props.dailyCallLimit() - calls),
                props.estimatedCostPerCallUsd(),
                Math.round(estimatedCostUsd() * 10_000d) / 10_000d,
                lastCallAt);
    }

    private void rollOverIfNewDay() {
        LocalDate today = LocalDate.now(clock);
        if (!today.equals(day)) {
            day = today;
            calls = 0;
            failures = 0;
        }
    }

    private double estimatedCostUsd() {
        return calls * props.estimatedCostPerCallUsd();
    }
}