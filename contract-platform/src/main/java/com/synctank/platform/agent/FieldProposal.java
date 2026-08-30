package com.synctank.platform.agent;

/**
 * Everything the LLM is allowed to decide. Note what is absent: no file path, no code, no
 * severity, no branch name, no commit message. The model names a field; deterministic Java
 * does the rest (Decision 1).
 *
 * This is Spring AI's structured-output target — the same ChatClient.entity(Class) pattern
 * Day 05 proved on this exact BOM and model.
 */
public record FieldProposal(

        /** Component schema name from the baseline spec, e.g. "OrderResponse". */
        String targetSchema,

        /** Lower camel case Java field name, e.g. "customerEmail". */
        String fieldName,

        /** One of String, Long, Integer, Boolean, Double. Validated, never trusted. */
        String javaType,

        /** One line for the PR body: what this field carries. */
        String description,

        /** Why the model believes this is what the request asked for. */
        String rationale,

        /** HIGH, MEDIUM or LOW. */
        String confidence,

        /** Non-empty when the request was ambiguous. The UI shows it instead of a draft. */
        String clarifyingQuestion
) {}