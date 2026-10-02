package com.codelens.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * GitHub's legacy review-comment "position": the line just below the FIRST "@@" header is
 * position 1; every later "@@" header counts as a position itself. Getting this wrong posts
 * every inline comment one line low (seen on smeassist #5161: asked for 273/66/26/79,
 * GitHub placed them at 274/67/27/80).
 */
class DiffParserTest {

    private final DiffParser parser = new DiffParser();

    private DiffParser.FileDiff parse(String path, String patch) {
        return parser.parse("diff --git a/" + path + " b/" + path + "\n" + patch).get(0);
    }

    @Test
    void addedFileLineNumberEqualsPosition() {
        DiffParser.FileDiff diff = parse("A.java", "@@ -0,0 +1,3 @@\n+a\n+b\n+c");

        assertEquals(1, parser.getDiffPosition(diff, 1));
        assertEquals(3, parser.getDiffPosition(diff, 3));
    }

    @Test
    void laterHunkHeadersAndDeletionsConsumePositions() {
        DiffParser.FileDiff diff = parse("A.java",
            "@@ -1,2 +1,2 @@\n ctx1\n-old2\n+new2\n@@ -10,2 +10,3 @@\n ctx10\n+add11\n ctx11");

        assertEquals(1, parser.getDiffPosition(diff, 1));  // ctx1
        assertEquals(3, parser.getDiffPosition(diff, 2));  // -old2 is position 2
        assertEquals(5, parser.getDiffPosition(diff, 10)); // second "@@" is position 4
        assertEquals(7, parser.getDiffPosition(diff, 12));
    }

    @Test
    void lineOutsideTheDiffHasNoPosition() {
        DiffParser.FileDiff diff = parse("A.java", "@@ -0,0 +1,3 @@\n+a\n+b\n+c");

        assertEquals(-1, parser.getDiffPosition(diff, 4));
    }
}
