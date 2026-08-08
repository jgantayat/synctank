package com.synctank.platform.diff;

import java.util.List;

public record DiffReport(
        boolean changed,
        Severity highestSeverity,
        List<ChangeRecord> changes,
        String markdown
) {}
