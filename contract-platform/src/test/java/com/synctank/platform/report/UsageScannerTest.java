package com.synctank.platform.report;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Day 10 (F1) — what the scanner does when ripgrep cannot run.
 *
 * Uses a binary name that cannot exist rather than mocking ProcessBuilder: the failure under
 * test IS the operating system refusing to start the process, so the test lets it happen.
 */
class UsageScannerTest {

    private static final String MISSING_BINARY = "synctank-no-such-binary-7f3a";

    @Test
    void aMissingBinaryIsReportedAsUnavailable() {
        UsageScanner scanner = new UsageScanner(MISSING_BINARY);

        assertThat(scanner.isAvailable()).isFalse();
        assertThat(scanner.version()).isEmpty();
    }

    @Test
    void aMissingBinaryInventsNoConsumerDirsAndDoesNotLeakTheInterruptFlag() {
        UsageScanner scanner = new UsageScanner(MISSING_BINARY);
        Thread.interrupted();   // start from a clean flag, whatever ran before on this thread

        List<Path> dirs = scanner.findClientConsumerDirs(Path.of(System.getProperty("java.io.tmpdir")));
        List<String> hits = scanner.findUsages(Path.of(System.getProperty("java.io.tmpdir")), "customerName");

        assertThat(dirs).isEmpty();
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0)).startsWith(UsageScanner.SCAN_FAILED_PREFIX);

        // The pre-Day-10 catch block called interrupt() for an IOException too. On a pooled
        // Tomcat worker that flag outlives the request and ambushes the next one.
        assertThat(Thread.currentThread().isInterrupted())
                .as("interrupt flag after a missing-binary IOException")
                .isFalse();
    }

    // ---------- Pre-Day-13 (B2, B3) — need a real ripgrep; skipped where there is none ----------
    // The Docker build stage has no rg, so these are skipped inside `docker build` and run on
    // any machine that has it (your laptop, and the spec-pipeline runner).

    @Test
    void registryScansAreOneLevelDeepReportScansAreRecursive(@TempDir Path src) throws IOException {
        UsageScanner scanner = new UsageScanner("rg");
        assumeTrue(scanner.isAvailable(), "ripgrep not installed");

        Files.writeString(src.resolve("app.config.ts"), "const status = 1;\n");
        Path feature = Files.createDirectories(src.resolve("dashboard"));
        Files.writeString(feature.resolve("dashboard.ts"), "const status = 2;\n");

        List<String> registry = scanner.findUsages(List.of(src), "status");
        List<String> report = scanner.findUsages(src, "status");

        assertThat(registry).hasSize(1).allMatch(hit -> hit.contains("app.config.ts"));
        assertThat(report).hasSize(2);
    }

    @Test
    void ripgrepErrorsAreNotReportedAsUseSites(@TempDir Path src) {
        UsageScanner scanner = new UsageScanner("rg");
        assumeTrue(scanner.isAvailable(), "ripgrep not installed");

        List<String> hits = scanner.findUsages(src.resolve("does-not-exist"), "status");

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0)).startsWith(UsageScanner.SCAN_FAILED_PREFIX);
    }
}