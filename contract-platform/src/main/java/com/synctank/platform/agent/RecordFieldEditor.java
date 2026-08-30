package com.synctank.platform.agent;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Adds one component to a Java record, and patches every canonical-constructor call site.
 *
 * WHY CALL SITES MATTER (Day 07 audit Finding B): a record's canonical constructor arity is
 * fixed. OrderController calls `new OrderResponse(...)` in four places. Adding a fifth
 * component without patching them makes orders-backend fail to compile, which means
 * `mvn verify` fails, which means no spec, no diff, no PR comment — the agent's own pull
 * request would go red for a reason unrelated to the contract.
 *
 * WHY NOT JavaPoet: JavaPoet emits a whole file from a model. Round-tripping existing source
 * through it would reformat everything the agent touches, turning a one-line PR into an
 * unreviewable reformat. Targeted textual insertion keeps the diff to the lines that changed.
 *
 * TYPE ALLOWLIST — two independent reasons, both load-bearing:
 *   1. all five are java.lang, so no import ever needs inserting;
 *   2. all five are reference types, so `null` is legal at every patched call site.
 * A primitive breaks (2); BigDecimal breaks (1).
 */
@Component
public class RecordFieldEditor {

    public static final List<String> ALLOWED_TYPES =
            List.of("String", "Long", "Integer", "Boolean", "Double");

    private static final Pattern IDENTIFIER = Pattern.compile("^[a-z][A-Za-z0-9]*$");

    private static final List<String> JAVA_KEYWORDS = List.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
            "const", "continue", "default", "do", "double", "else", "enum", "extends", "final",
            "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int",
            "interface", "long", "native", "new", "package", "private", "protected", "public",
            "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this",
            "throw", "throws", "transient", "try", "void", "volatile", "while", "record", "var",
            "yield", "sealed", "permits", "true", "false", "null");

    // ---------- validation ----------

    public boolean isAllowedType(String javaType) {
        return javaType != null && ALLOWED_TYPES.contains(javaType);
    }

    /**
     * Lower camel case only. Not cosmetic: it rules out keywords, dotted names, generics, and
     * anything that could smuggle syntax into the source through the field name.
     */
    public boolean isValidFieldName(String fieldName) {
        return fieldName != null
                && IDENTIFIER.matcher(fieldName).matches()
                && !JAVA_KEYWORDS.contains(fieldName);
    }

    // ---------- record header ----------

    public boolean declaresRecord(String source, String recordName) {
        return headerOpenParen(source, recordName) >= 0;
    }

    public boolean hasComponent(String source, String recordName, String fieldName) {
        String header = componentHeader(source, recordName);
        if (header == null) {
            return false;
        }
        // A component's name is always the last token before a comma or the closing paren, so
        // anchoring on that suffix avoids matching a TYPE that happens to share the name.
        return Pattern.compile("\\b" + Pattern.quote(fieldName) + "\\s*(,|\\z)", Pattern.DOTALL)
                .matcher(header).find();
    }

    /**
     * Returns the source with one component appended to the record header.
     *
     * Formatting matches the file's existing style: the last component on its own line,
     * indented eight spaces, closing paren on the following line — exactly how
     * OrderResponse.java is written today.
     */
    public String addRecordComponent(String source, String recordName, String javaType, String fieldName) {
        int open = headerOpenParen(source, recordName);
        if (open < 0) {
            throw new IllegalStateException("No top-level record named '" + recordName + "' in this file.");
        }
        int close = matchingParen(source, open);
        if (close < 0) {
            throw new IllegalStateException("Unbalanced parentheses in the header of record '" + recordName + "'.");
        }

        String head = source.substring(0, close);
        String tail = source.substring(close);              // starts at ')'
        String trimmedHead = stripTrailingWhitespace(head);

        boolean empty = trimmedHead.endsWith("(");
        String insertion = empty
                ? "\n        " + javaType + " " + fieldName + "\n"
                : ",\n        " + javaType + " " + fieldName + "\n";

        return trimmedHead + insertion + tail;
    }

    // ---------- constructor call sites ----------

    public boolean callsConstructorOf(String source, String recordName) {
        return constructorPattern(recordName).matcher(source).find();
    }

    /**
     * Appends one argument to every {@code new RecordName(...)} in the source.
     *
     * Processed from the LAST occurrence backwards so each insertion cannot shift the indices
     * of occurrences not yet handled.
     */
    public String appendConstructorArgument(String source, String recordName, String literal) {
        Matcher matcher = constructorPattern(recordName).matcher(source);

        List<Integer> openParens = new ArrayList<>();
        while (matcher.find()) {
            openParens.add(matcher.end() - 1);   // index of the '('
        }

        StringBuilder buffer = new StringBuilder(source);
        for (int i = openParens.size() - 1; i >= 0; i--) {
            int open = openParens.get(i);
            int close = matchingParen(buffer.toString(), open);
            if (close < 0) {
                throw new IllegalStateException(
                        "Unbalanced parentheses in a call to new " + recordName + "(...)");
            }
            boolean noArgs = buffer.substring(open + 1, close).isBlank();
            buffer.insert(close, noArgs ? literal : ", " + literal);
        }
        return buffer.toString();
    }

    /** The value the agent stubs into existing call sites. Always null — see the class javadoc. */
    public String defaultLiteralFor(String javaType) {
        if (!isAllowedType(javaType)) {
            throw new IllegalArgumentException("Type not on the allowlist: " + javaType);
        }
        return "null";
    }

    // ---------- internals ----------

    private Pattern constructorPattern(String recordName) {
        return Pattern.compile("\\bnew\\s+" + Pattern.quote(recordName) + "\\s*\\(");
    }

    private int headerOpenParen(String source, String recordName) {
        Matcher matcher = Pattern
                .compile("\\brecord\\s+" + Pattern.quote(recordName) + "\\s*\\(")
                .matcher(source);
        return matcher.find() ? matcher.end() - 1 : -1;
    }

    private String componentHeader(String source, String recordName) {
        int open = headerOpenParen(source, recordName);
        if (open < 0) return null;
        int close = matchingParen(source, open);
        if (close < 0) return null;
        return source.substring(open + 1, close);
    }

    /**
     * Index of the ')' matching the '(' at {@code open}.
     *
     * Skips string literals, char literals, line comments and block comments. Not paranoia:
     * OrderResponse.java carries
     *     {@code @Schema(allowableValues = {"PENDING", "SHIPPED", "DELIVERED", "CANCELLED"})}
     * inside its component list, and a naive depth counter that walked into a string literal
     * containing a parenthesis would mis-locate the end of the header and corrupt the file.
     */
    private int matchingParen(String s, int open) {
        int depth = 0;
        boolean inString = false, inChar = false, inLine = false, inBlock = false;

        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            char next = (i + 1 < s.length()) ? s.charAt(i + 1) : '\0';

            if (inLine) {
                if (c == '\n') inLine = false;
                continue;
            }
            if (inBlock) {
                if (c == '*' && next == '/') { inBlock = false; i++; }
                continue;
            }
            if (inString) {
                if (c == '\\') i++;
                else if (c == '"') inString = false;
                continue;
            }
            if (inChar) {
                if (c == '\\') i++;
                else if (c == '\'') inChar = false;
                continue;
            }

            if (c == '/' && next == '/') { inLine = true; i++; continue; }
            if (c == '/' && next == '*') { inBlock = true; i++; continue; }
            if (c == '"') { inString = true; continue; }
            if (c == '\'') { inChar = true; continue; }

            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    private String stripTrailingWhitespace(String s) {
        int end = s.length();
        while (end > 0 && Character.isWhitespace(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(0, end);
    }
}