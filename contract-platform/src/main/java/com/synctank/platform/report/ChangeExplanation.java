package com.synctank.platform.report;

import java.util.List;

public record ChangeExplanation(
        String location,          // e.g. "OrderResponse.amount" — matches Day 03's ChangeRecord.location()
        String severity,
        String plainEnglish,
        List<String> usageHits
) {}