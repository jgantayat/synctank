package com.synctank.platform.report;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
public class UsageScanner {

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
                "rg", "-n", "--no-heading",
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
                "rg", "-l", "--no-heading",
                "-g", "*.ts",
                "from\\s+['\"][^'\"]*generated",
                frontendSrcRoot.toString()));

        LinkedHashSet<Path> dirs = new LinkedHashSet<>();
        for (String file : files) {
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
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            hits.add("(usage scan failed: " + e.getMessage() + ")");
        }
        return hits;
    }
}