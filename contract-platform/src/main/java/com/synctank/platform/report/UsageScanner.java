package com.synctank.platform.report;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
public class UsageScanner {

    /**
     * Searches the Angular frontend source for references to a changed field.
     * Returns lines like "order-detail.component.ts:34:    return order.amount;"
     */
    public List<String> findUsages(Path frontendSrcRoot, String fieldName) {
        List<String> hits = new ArrayList<>();
        try {
            // -n: line numbers, --no-heading: one match per line, -g: only .ts files,
            // word boundary avoids matching "amountDue" when we mean "amount"
            ProcessBuilder pb = new ProcessBuilder(
                    "rg", "-n", "--no-heading", "-g", "*.ts",
                    "\\b" + fieldName + "\\b",
                    frontendSrcRoot.toString()
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();
            try (var reader = process.inputReader()) {
                reader.lines().forEach(hits::add);
            }
            boolean finished = process.waitFor(10, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
            }
            // ripgrep exits 1 when there are zero matches — that's a valid "no usages found" result, not an error
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            hits.add("(usage scan failed: " + e.getMessage() + ")");
        }
        return hits;
    }
}