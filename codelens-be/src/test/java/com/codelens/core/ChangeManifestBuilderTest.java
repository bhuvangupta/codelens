package com.codelens.core;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class ChangeManifestBuilderTest {

    private static final String PATCH = """
        @@ -10,6 +10,8 @@ public FileReviewResult reviewFile(String filename) {
        +        int x = 1;
        @@ -40,3 +42,3 @@ private String buildPrompt(String filename) {
        +        return prompt;
        """;

    @Nested
    class BuildEntry {
        @Test
        void extractsFunctionNamesFromHunkHeaders() {
            var entry = ChangeManifestBuilder.buildEntry("src/A.java", 3, 1, PATCH);
            assertEquals("src/A.java", entry.filename());
            assertTrue(entry.line().contains("src/A.java (+3/-1)"));
            assertTrue(entry.line().contains("public FileReviewResult reviewFile(String filename) {"));
            assertTrue(entry.line().contains("private String buildPrompt(String filename) {"));
        }

        @Test
        void handlesNullPatchAndHunksWithoutContext() {
            var entry = ChangeManifestBuilder.buildEntry("README.md", 5, 0, null);
            assertEquals("- README.md (+5/-0)", entry.line());

            var noContext = ChangeManifestBuilder.buildEntry("a.txt", 1, 0, "@@ -1 +1 @@\n+x");
            assertEquals("- a.txt (+1/-0)", noContext.line());
        }
    }

    @Nested
    class Format {
        @Test
        void excludesCurrentFileAndIsEmptyWhenNoSiblings() {
            var a = ChangeManifestBuilder.buildEntry("a.java", 1, 0, null);
            var b = ChangeManifestBuilder.buildEntry("b.java", 2, 0, null);

            String forA = ChangeManifestBuilder.format(List.of(a, b), "a.java");
            assertFalse(forA.contains("a.java (+1/-0)"));
            assertTrue(forA.contains("b.java"));
            assertTrue(forA.contains("Other changes in this PR"));

            assertEquals("", ChangeManifestBuilder.format(List.of(a), "a.java"));
            assertEquals("", ChangeManifestBuilder.format(List.of(), "a.java"));
        }

        @Test
        void capsSizeWithExplicitOverflowLine() {
            List<ChangeManifestBuilder.Entry> entries = IntStream.range(0, 200)
                .mapToObj(i -> ChangeManifestBuilder.buildEntry(
                    "src/main/java/com/example/very/long/path/SomeFileNumber" + i + ".java", i, i, null))
                .toList();

            String result = ChangeManifestBuilder.format(entries, "none.java");
            assertTrue(result.length() <= ChangeManifestBuilder.MAX_MANIFEST_CHARS + 64);
            assertTrue(result.contains("more changed files"));
        }
    }
}
