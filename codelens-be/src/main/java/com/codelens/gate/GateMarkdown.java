package com.codelens.gate;

/** Helpers for the findings comments the gate posts on pull requests. */
final class GateMarkdown {

    private GateMarkdown() {}

    /**
     * Make a value safe for a markdown table cell. File names, package names and the
     * like come from the PR, so they must not be able to break out of the table,
     * inject links or mention users.
     */
    static String cell(String value) {
        if (value == null || value.isBlank()) {
            return "-";
        }
        String cleaned = value.replaceAll("[\\r\\n|`<>\\[\\]@]", "_");
        return cleaned.length() > 120 ? cleaned.substring(0, 120) + "…" : cleaned;
    }

    static String plural(int count, String noun) {
        return count + " " + noun + (count == 1 ? "" : "s");
    }
}
