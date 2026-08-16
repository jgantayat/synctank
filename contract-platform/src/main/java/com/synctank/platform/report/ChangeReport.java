package com.synctank.platform.report;

import java.util.List;

public record ChangeReport(
        String summary,                    // 1-3 sentence plain-English overview for the PR comment header
        List<ChangeExplanation> changes,    // one entry per classified diff item
        String suggestedMigrationPatch,     // unified-diff-style TypeScript snippet, or null if not applicable
        List<String> openQuestions          // things the AI wasn't confident enough to assert (can be empty)
) {}