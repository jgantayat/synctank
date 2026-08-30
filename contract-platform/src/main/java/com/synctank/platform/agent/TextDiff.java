package com.synctank.platform.agent;

import java.util.List;

/**
 * A deliberately minimal unified diff, for DISPLAY ONLY.
 *
 * The agent's edits are single-line insertions and single-line argument appends, so a
 * common-prefix / common-suffix comparison is exact for every case it is used on and needs no
 * LCS machinery. If a future edit type produces interleaved changes this renders one large
 * hunk rather than several small ones — ugly, never wrong.
 *
 * This output is never applied by anything. The PR carries real file contents.
 */
public final class TextDiff {

    private TextDiff() {}

    public static String unified(String path, String before, String after) {
        List<String> a = List.of(before.split("\n", -1));
        List<String> b = List.of(after.split("\n", -1));

        int prefix = 0;
        while (prefix < a.size() && prefix < b.size() && a.get(prefix).equals(b.get(prefix))) {
            prefix++;
        }
        int suffix = 0;
        while (suffix < a.size() - prefix && suffix < b.size() - prefix
                && a.get(a.size() - 1 - suffix).equals(b.get(b.size() - 1 - suffix))) {
            suffix++;
        }

        StringBuilder out = new StringBuilder();
        out.append("--- a/").append(path).append('\n');
        out.append("+++ b/").append(path).append('\n');
        out.append("@@ -").append(prefix + 1).append(" +").append(prefix + 1).append(" @@\n");

        int context = 2;
        for (int i = Math.max(0, prefix - context); i < prefix; i++) {
            out.append(' ').append(a.get(i)).append('\n');
        }
        for (int i = prefix; i < a.size() - suffix; i++) {
            out.append('-').append(a.get(i)).append('\n');
        }
        for (int i = prefix; i < b.size() - suffix; i++) {
            out.append('+').append(b.get(i)).append('\n');
        }
        for (int i = a.size() - suffix; i < Math.min(a.size(), a.size() - suffix + context); i++) {
            out.append(' ').append(a.get(i)).append('\n');
        }
        return out.toString();
    }
}