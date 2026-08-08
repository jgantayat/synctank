package com.synctank.platform.diff;


/**
 * One classified change. category is a machine-readable tag (e.g. "ENUM_NARROWED"),
 * used by tests and, later, by the AI report layer. description is the plain-English
 * explanation a developer reads on the PR.
 */
public record ChangeRecord(
        Severity severity,
        String category,
        String location,
        String description
) {}