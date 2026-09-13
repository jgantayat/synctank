package com.synctank.platform.report;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Component
public class UsageScanner {

    /** Prefix of the sentinel line run() returns when the scan could not execute at all. */
    static final String SCAN_FAILED_PREFIX = "(usage scan failed";

    /**
     * Day 10 — the executable, configurable so a test (and the §6 negative check) can point it
     * at a binary that does not exist. Production value is always "rg".
     */
    private final String binary;

    public UsageScanner(@Value("${platform.usage-scanner.binary:rg}") String binary) {
        this.binary = (binary == null || binary.isBlank()) ? "rg" : binary;
    }

    /**
     * Day 10 (F1) — whether the scanner can run at all.
     *
     * Before today there was no way to ask. A missing `rg` made every scan return a single
     * "(usage scan failed ...)" line, which findClientConsumerDirs() turned into zero consumer
     * directories, which RegistrySeeder recorded as "no file imports the generated client" —
     * an EMPTY registry, reported as success. The Impact Radar then found no consumer for a
     * removed field and downgraded BREAKING to SAFE_WITH_NOTE: the merge gate opened because a
     * tool was missing. RegistrySeeder now asks this first and refuses to seed if it is false.
     */
    public boolean isAvailable() {
        return version().isPresent();
    }

    /** First line of `rg --version`, e.g. "ripgrep 14.1.0", or empty when it cannot run. */
    public Optional<String> version() {
        try {
            Process process = new ProcessBuilder(binary, "--version")
                    .redirectErrorStream(true)
                    .start();
            String first;
            try (var reader = process.inputReader()) {
                first = reader.readLine();
            }
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return Optional.empty();
            }
            return process.exitValue() == 0 && first != null && !first.isBlank()
                    ? Optional.of(first.trim())
                    : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    /**
     * Day 05 entry point — unchanged signature, called by ChangeReportService.
     * Searches the whole frontend source tree for references to one symbol.
     *
     * Day 06 change: the glob now includes *.html as well as *.ts. Angular templates
     * are real use sites — order-detail.html renders {{ currentOrder.customerName }},
     * which breaks at runtime exactly like a .ts reference would, but Day 05's
     * ts-only glob never saw it. This only ever adds hits, never removes them.
     */
    public List<String> findUsages(Path frontendSrcRoot, String symbol) {
        return findUsages(List.of(frontendSrcRoot), symbol);
    }

    /**
     * Day 06 — the same search, restricted to a specific set of directories.
     * Used by RegistrySeeder so that seeding a field called "id" or "status" doesn't
     * match every unrelated file in the app: we only scan directories that actually
     * compile against the generated client.
     *
     * Returns lines shaped "path:line:matched text".
     */
    public List<String> findUsages(List<Path> roots, String symbol) {
        if (roots.isEmpty()) {
            // Guard, not politeness: ripgrep with zero path arguments searches the
            // process working directory, which for a Spring Boot jar is wherever the
            // service happened to be launched from. Silently scanning the wrong tree
            // is far worse than returning nothing.
            return List.of();
        }
        List<String> command = new ArrayList<>(List.of(
                binary, "-n", "--no-heading",
                "-g", "*.ts", "-g", "*.html",
                "\\b" + symbol + "\\b"));
        roots.forEach(root -> command.add(root.toString()));
        return run(command);
    }

    /**
     * Day 06 — finds the directories that hold the app's client-consuming code:
     * every directory containing at least one .ts file that imports from the
     * generated client barrel (e.g. `import { OrderResponse } from '../generated'`).
     *
     * Directories rather than files, because an Angular component's template lives
     * next to its class — order-detail.html has no import statement of its own, but
     * it is unambiguously part of the same consumer.
     */
    public List<Path> findClientConsumerDirs(Path frontendSrcRoot) {
        List<String> files = run(List.of(
                binary, "-l", "--no-heading",
                "-g", "*.ts",
                "from\\s+['\"][^'\"]*generated",
                frontendSrcRoot.toString()));

        LinkedHashSet<Path> dirs = new LinkedHashSet<>();
        for (String file : files) {
            // Day 10 — the failure sentinel is not a file path. It happened to have no parent
            // (so it was dropped by accident rather than by design); make that explicit.
            if (file.startsWith(SCAN_FAILED_PREFIX)) {
                continue;
            }
            Path parent = Path.of(file.trim()).getParent();
            if (parent != null) {
                dirs.add(parent);
            }
        }
        return new ArrayList<>(dirs);
    }

    /**
     * Shared process runner. The /generated/ filter applies to both -n output
     * ("path:line:text") and -l output ("path"), because in both cases the path
     * is a prefix of the line — one filter serves both call sites.
     *
     * Day 05 lesson preserved: this filtering is done in Java, NOT via an
     * `rg -g '!**\/generated/**'` glob. A real CI run showed that glob silently
     * failing to match when the search root is passed as an absolute path.
     */
    private List<String> run(List<String> command) {
        List<String> hits = new ArrayList<>();
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            try (var reader = process.inputReader()) {
                reader.lines()
                        .filter(line -> !line.contains("/generated/"))
                        .forEach(hits::add);
            }
            boolean finished = process.waitFor(10, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
            }
            // ripgrep exits 1 on zero matches — a valid "nothing found", not an error.
        } catch (IOException e) {
            // Day 10 (F1) — split from the InterruptedException branch below. The old combined
            // catch called Thread.currentThread().interrupt() for an IOException too, i.e. for a
            // missing `rg` — which left the interrupt flag set on a pooled Tomcat worker thread
            // for whatever request it served next. Only a real interruption re-asserts the flag.
            hits.add(SCAN_FAILED_PREFIX + ": " + e.getMessage() + ")");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            hits.add(SCAN_FAILED_PREFIX + ": interrupted)");
        }
        return hits;
    }
}