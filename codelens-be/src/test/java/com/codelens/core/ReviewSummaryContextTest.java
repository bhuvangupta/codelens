package com.codelens.core;

import com.codelens.model.entity.ReviewIssue;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The PR summary used to be generated from filenames, +/- counts and an issue
 * <em>count</em>, so its "Risk Assessment" and "Review Notes" sections were guesses.
 * These tests cover the data now handed to the summary model, and the caps that keep
 * that data from growing without bound on a large or noisy pull request.
 */
class ReviewSummaryContextTest {

    private static ReviewIssue issue(ReviewIssue.Severity severity, String path, int line,
            String rule, String description) {
        ReviewIssue i = new ReviewIssue();
        i.setSeverity(severity);
        i.setFilePath(path);
        i.setLineNumber(line);
        i.setRule(rule);
        i.setDescription(description);
        return i;
    }

    @Nested
    class Findings {

        @Test
        void rendersSeverityPathLineRuleAndMessage() {
            String out = ReviewEngine.formatFindingsForSummary(List.of(
                issue(ReviewIssue.Severity.HIGH, "src/Auth.java", 42, "missing-authz",
                    "Endpoint does not check tenant ownership")));

            assertTrue(out.contains("HIGH"), out);
            assertTrue(out.contains("src/Auth.java:42"), out);
            assertTrue(out.contains("[missing-authz]"), out);
            assertTrue(out.contains("Endpoint does not check tenant ownership"), out);
        }

        @Test
        void ordersMostSevereFirst() {
            String out = ReviewEngine.formatFindingsForSummary(List.of(
                issue(ReviewIssue.Severity.LOW, "src/A.java", 1, "low-rule", "low finding"),
                issue(ReviewIssue.Severity.CRITICAL, "src/B.java", 2, "crit-rule", "critical finding"),
                issue(ReviewIssue.Severity.MEDIUM, "src/C.java", 3, "med-rule", "medium finding")));

            assertTrue(out.indexOf("critical finding") < out.indexOf("medium finding"), out);
            assertTrue(out.indexOf("medium finding") < out.indexOf("low finding"), out);
        }

        @Test
        void reportsNoneWhenThereAreNoFindings() {
            assertEquals("(none)", ReviewEngine.formatFindingsForSummary(List.of()));
            assertEquals("(none)", ReviewEngine.formatFindingsForSummary(null));
        }

        @Test
        void capsCountAndSaysHowManyWereOmitted() {
            List<ReviewIssue> many = IntStream.range(0, 100)
                .mapToObj(n -> issue(ReviewIssue.Severity.MEDIUM, "src/F" + n + ".java", n,
                    "rule-" + n, "finding number " + n))
                .toList();

            String out = ReviewEngine.formatFindingsForSummary(many);

            long rendered = out.lines().filter(l -> l.startsWith("- ")).count();
            assertTrue(rendered <= 40, "expected at most 40 findings, got " + rendered);
            assertTrue(out.contains("more findings"), "omission must be disclosed:\n" + out);
        }

        @Test
        void survivesNullSeverityWithoutThrowing() {
            // Severity is nullable on the entity; the comparator must not NPE.
            List<ReviewIssue> issues = new ArrayList<>();
            issues.add(issue(null, "src/A.java", 1, "r", "no severity"));
            issues.add(issue(ReviewIssue.Severity.HIGH, "src/B.java", 2, "r2", "has severity"));

            String out = assertDoesNotThrow(() -> ReviewEngine.formatFindingsForSummary(issues));
            assertTrue(out.contains("has severity"), out);
            assertTrue(out.indexOf("has severity") < out.indexOf("no severity"),
                "null severity should sort last:\n" + out);
        }

        @Test
        void flattensAndTruncatesLongDescriptions() {
            String sprawling = "line one\nline two " + "x".repeat(400);
            String out = ReviewEngine.formatFindingsForSummary(List.of(
                issue(ReviewIssue.Severity.LOW, "src/A.java", 1, "r", sprawling)));

            assertEquals(1, out.lines().filter(l -> !l.isBlank()).count(),
                "a finding must stay on one line:\n" + out);
            assertTrue(out.contains("..."), out);
        }
    }

    @Nested
    class FileList {

        @Test
        void carriesChangedFunctionNamesNotJustPaths() {
            String patch = """
                @@ -10,6 +10,8 @@ public void transferFunds(Account from, Account to) {
                +        int x = 1;
                """;
            var entry = ChangeManifestBuilder.buildEntry("src/Bank.java", 3, 1, patch);

            String out = ReviewEngine.formatFileListForSummary(List.of(entry));

            assertTrue(out.contains("src/Bank.java (+3/-1)"), out);
            assertTrue(out.contains("public void transferFunds(Account from, Account to) {"), out);
        }

        @Test
        void capsSizeAndSaysHowManyWereOmitted() {
            List<ChangeManifestBuilder.Entry> entries = IntStream.range(0, 400)
                .mapToObj(n -> ChangeManifestBuilder.buildEntry(
                    "src/very/deeply/nested/package/File" + n + ".java", n, n, null))
                .toList();

            String out = ReviewEngine.formatFileListForSummary(entries);

            assertTrue(out.length() < 4500, "file list should stay bounded, was " + out.length());
            assertTrue(out.contains("more changed files"), "omission must be disclosed");
        }

        @Test
        void handlesEmptyInput() {
            assertEquals("(no files)", ReviewEngine.formatFileListForSummary(List.of()));
            assertEquals("(no files)", ReviewEngine.formatFileListForSummary(null));
        }
    }

    @Nested
    class ContextBlocks {

        @Test
        void projectContextHoldsRulesAndHints() {
            String out = ReviewEngine.buildProjectContextBlock(
                "Never log PII.", List.of("Team dislikes nitpicks about naming"));

            assertTrue(out.contains("Additional Project-Specific Rules"), out);
            assertTrue(out.contains("Never log PII."), out);
            assertTrue(out.contains("Learned Context"), out);
            assertTrue(out.contains("Team dislikes nitpicks about naming"), out);
        }

        @Test
        void projectContextIsEmptyWhenNothingConfigured() {
            assertEquals("", ReviewEngine.buildProjectContextBlock(null, null));
            assertEquals("", ReviewEngine.buildProjectContextBlock("  ", List.of()));
        }

        @Test
        void fileContextHoldsGraphThenManifest() {
            String out = ReviewEngine.buildFileContextBlock(
                "## Codebase Context\ncallers: A, B", "## Other changes in this PR\n- src/X.java");

            assertTrue(out.indexOf("Codebase Context") < out.indexOf("Other changes in this PR"), out);
        }

        @Test
        void fileContextIsEmptyWhenNothingAvailable() {
            assertEquals("", ReviewEngine.buildFileContextBlock(null, null));
            assertEquals("", ReviewEngine.buildFileContextBlock("", "  "));
        }
    }
}
