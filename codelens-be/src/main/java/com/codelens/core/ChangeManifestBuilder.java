package com.codelens.core;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds a compact PR-wide change manifest so each per-file review prompt can
 * see what else changed in the same PR (paths and changed functions only —
 * file bodies are never included).
 */
public final class ChangeManifestBuilder {

    private static final Pattern HUNK_CONTEXT =
        Pattern.compile("^@@ -\\d+(?:,\\d+)? \\+\\d+(?:,\\d+)? @@ (.+)$");
    private static final int MAX_FUNCTIONS_PER_FILE = 5;
    private static final int MAX_FUNCTION_NAME_CHARS = 60;
    public static final int MAX_MANIFEST_CHARS = 2048;

    private ChangeManifestBuilder() {}

    public record Entry(String filename, String line) {}

    public static Entry buildEntry(String filename, int additions, int deletions, String patch) {
        Set<String> functions = new LinkedHashSet<>();
        if (patch != null) {
            for (String line : patch.split("\n")) {
                Matcher m = HUNK_CONTEXT.matcher(line);
                if (m.find()) {
                    String fn = m.group(1).trim();
                    if (fn.length() > MAX_FUNCTION_NAME_CHARS) {
                        fn = fn.substring(0, MAX_FUNCTION_NAME_CHARS);
                    }
                    if (!fn.isEmpty()) {
                        functions.add(fn);
                    }
                    if (functions.size() >= MAX_FUNCTIONS_PER_FILE) {
                        break;
                    }
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("- ").append(filename)
          .append(" (+").append(additions).append("/-").append(deletions).append(")");
        if (!functions.isEmpty()) {
            sb.append(": ").append(String.join(", ", functions));
        }
        return new Entry(filename, sb.toString());
    }

    /**
     * Formats the manifest for one file's review prompt, excluding that file's
     * own entry. Returns "" when there are no sibling changes (single-file PR).
     */
    public static String format(List<Entry> entries, String currentFilename) {
        List<Entry> siblings = entries.stream()
            .filter(e -> !e.filename().equals(currentFilename))
            .toList();
        if (siblings.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("## Other changes in this PR\n");
        sb.append("These sibling files changed in the same PR. You cannot see their bodies. ");
        sb.append("Use this ONLY to spot cross-file mismatches (changed signatures, contracts, renamed config keys). ");
        sb.append("Flag a suspected mismatch at the relevant changed line in THIS file with confidence MEDIUM.\n\n");

        int included = 0;
        for (Entry e : siblings) {
            if (sb.length() + e.line().length() + 1 > MAX_MANIFEST_CHARS) {
                break;
            }
            sb.append(e.line()).append("\n");
            included++;
        }
        int omitted = siblings.size() - included;
        if (omitted > 0) {
            sb.append("...and ").append(omitted).append(" more changed files\n");
        }
        return sb.toString();
    }
}
