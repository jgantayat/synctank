package com.synctank.platform.radar;

import java.util.List;

/** One affected consumer of one contract location — the "12K calls/day" row. */
public record ConsumerImpact(
        String appName,
        String team,
        List<String> screens,
        List<String> useSites,     // "path/to/file.ts:34"
        long callsPerDay
) {}