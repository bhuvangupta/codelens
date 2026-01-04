package com.codelens.core;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class DiffParser {

    private static final Pattern DIFF_HEADER = Pattern.compile("^diff --git a/(.+) b/(.+)$");
    private static final Pattern HUNK_HEADER = Pattern.compile("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@(.*)$");

    /**
     * Parse a unified diff into structured file changes
     */
    public List<FileDiff> parse(String diff) {
        List<FileDiff> fileDiffs = new ArrayList<>();
        String[] lines = diff.split("\n");

        FileDiff currentFile = null;
        Hunk currentHunk = null;
        int oldLineNum = 0;
        int newLineNum = 0;

        for (String line : lines) {
            // Check for new file
            Matcher diffMatcher = DIFF_HEADER.matcher(line);
            if (diffMatcher.matches()) {
                if (currentFile != null) {
                    if (currentHunk != null) {
                        currentFile.hunks().add(currentHunk);
                    }
                    fileDiffs.add(currentFile);
                }
                currentFile = new FileDiff(
                    diffMatcher.group(1),
                    diffMatcher.group(2),
                    new ArrayList<>()
                );
                currentHunk = null;
                continue;
            }

            // Check for new hunk
            Matcher hunkMatcher = HUNK_HEADER.matcher(line);
            if (hunkMatcher.matches() && currentFile != null) {
                if (currentHunk != null) {
                    currentFile.hunks().add(currentHunk);
                }
                oldLineNum = Integer.parseInt(hunkMatcher.group(1));
                newLineNum = Integer.parseInt(hunkMatcher.group(3));
                String context = hunkMatcher.group(5).trim();
                currentHunk = new Hunk(
                    oldLineNum,
                    Integer.parseInt(hunkMatcher.group(2) != null ? hunkMatcher.group(2) : "1"),
                    newLineNum,
                    Integer.parseInt(hunkMatcher.group(4) != null ? hunkMatcher.group(4) : "1"),
                    context,
                    new ArrayList<>()
                );
                continue;
            }

            // Parse diff lines
            if (currentHunk != null && !line.startsWith("---") && !line.startsWith("+++")
                && !line.startsWith("index ") && !line.startsWith("new file")
                && !line.startsWith("deleted file")) {

                DiffLine.Type type;
                int lineNumber;

                if (line.startsWith("+")) {
                    type = DiffLine.Type.ADDITION;
                    lineNumber = newLineNum++;
                } else if (line.startsWith("-")) {
                    type = DiffLine.Type.DELETION;
                    lineNumber = oldLineNum++;
                } else if (line.startsWith(" ") || line.isEmpty()) {
                    type = DiffLine.Type.CONTEXT;
                    lineNumber = newLineNum++;
                    oldLineNum++;
                } else {
                    continue;
                }

                String content = line.length() > 1 ? line.substring(1) : "";
                currentHunk.lines().add(new DiffLine(type, lineNumber, content));
            }
        }

        // Add last file and hunk
        if (currentFile != null) {
            if (currentHunk != null) {
                currentFile.hunks().add(currentHunk);
            }
            fileDiffs.add(currentFile);
        }

        return fileDiffs;
    }

    /**
     * Get added lines with their line numbers for a specific file
     */
    public List<DiffLine> getAddedLines(FileDiff fileDiff) {
        List<DiffLine> addedLines = new ArrayList<>();
        for (Hunk hunk : fileDiff.hunks()) {
            for (DiffLine line : hunk.lines()) {
                if (line.type() == DiffLine.Type.ADDITION) {
                    addedLines.add(line);
                }
            }
        }
        return addedLines;
    }

    /**
     * Check if a specific line is within the diff changes and can receive a comment.
     * GitHub only allows comments on lines that are actually in the diff patch
     * (additions or context lines, not deletions).
     */
    public boolean isLineInDiff(FileDiff fileDiff, int lineNumber) {
        for (Hunk hunk : fileDiff.hunks()) {
            // First check if line is in the hunk's new file range
            if (lineNumber >= hunk.newStart() && lineNumber < hunk.newStart() + hunk.newCount()) {
                // Now verify the line actually exists in the parsed diff lines
                // as an ADDITION or CONTEXT line (not DELETION)
                for (DiffLine line : hunk.lines()) {
                    if (line.lineNumber() == lineNumber &&
                        (line.type() == DiffLine.Type.ADDITION || line.type() == DiffLine.Type.CONTEXT)) {
                        log.debug("Line {} found in diff as {} in hunk starting at line {}",
                            lineNumber, line.type(), hunk.newStart());
                        return true;
                    }
                }
                log.debug("Line {} is in hunk range [{}, {}) but not found in parsed diff lines",
                    lineNumber, hunk.newStart(), hunk.newStart() + hunk.newCount());
            }
        }
        log.debug("Line {} not in any hunk range for file {}", lineNumber, fileDiff.getPath());
        return false;
    }

    /**
     * Get all commentable line numbers from the diff (additions and context lines only)
     */
    public List<Integer> getCommentableLines(FileDiff fileDiff) {
        List<Integer> lines = new ArrayList<>();
        for (Hunk hunk : fileDiff.hunks()) {
            for (DiffLine line : hunk.lines()) {
                if (line.type() == DiffLine.Type.ADDITION || line.type() == DiffLine.Type.CONTEXT) {
                    lines.add(line.lineNumber());
                }
            }
        }
        return lines;
    }

    /**
     * Calculate the diff position for a given line number.
     * The position is 1-indexed from the start of the patch.
     * Returns -1 if the line is not in the diff.
     */
    public int getDiffPosition(FileDiff fileDiff, int lineNumber) {
        int position = 0;

        for (Hunk hunk : fileDiff.hunks()) {
            // Count the hunk header line (@@ -x,y +a,b @@)
            position++;

            for (DiffLine line : hunk.lines()) {
                position++;

                // For additions and context lines, check against new file line number
                if ((line.type() == DiffLine.Type.ADDITION || line.type() == DiffLine.Type.CONTEXT)
                    && line.lineNumber() == lineNumber) {
                    log.debug("Found line {} at diff position {} in hunk", lineNumber, position);
                    return position;
                }
            }
        }

        log.debug("Line {} not found in diff, returning -1", lineNumber);
        return -1;
    }

    /**
     * Represents a file in the diff
     */
    public record FileDiff(
        String oldPath,
        String newPath,
        List<Hunk> hunks
    ) {
        public String getPath() {
            return newPath != null ? newPath : oldPath;
        }
    }

    /**
     * Represents a hunk (change block) in a file diff
     */
    public record Hunk(
        int oldStart,
        int oldCount,
        int newStart,
        int newCount,
        String context,
        List<DiffLine> lines
    ) {}

    /**
     * Represents a single line in the diff
     */
    public record DiffLine(
        Type type,
        int lineNumber,
        String content
    ) {
        public enum Type {
            ADDITION,
            DELETION,
            CONTEXT
        }
    }
}
