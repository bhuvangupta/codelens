package com.codelens.core;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class IgnoreCommentParser {

    // Pattern to match ignore comments: // @codelens-ignore or /* @codelens-ignore */ or # @codelens-ignore
    private static final Pattern IGNORE_LINE_PATTERN = Pattern.compile(
        "(?://|/\\*|#)\\s*@codelens-ignore(?:\\s+(.+?))?(?:\\s*\\*/)?$",
        Pattern.MULTILINE
    );

    // Pattern to match block ignore: // @codelens-ignore-start ... // @codelens-ignore-end
    private static final Pattern IGNORE_BLOCK_START = Pattern.compile(
        "(?://|/\\*|#)\\s*@codelens-ignore-start(?:\\s*\\*/)?",
        Pattern.MULTILINE
    );

    private static final Pattern IGNORE_BLOCK_END = Pattern.compile(
        "(?://|/\\*|#)\\s*@codelens-ignore-end(?:\\s*\\*/)?",
        Pattern.MULTILINE
    );

    // Pattern to match file-level ignore: // @codelens-ignore-file
    private static final Pattern IGNORE_FILE_PATTERN = Pattern.compile(
        "(?://|/\\*|#)\\s*@codelens-ignore-file(?:\\s*\\*/)?",
        Pattern.MULTILINE
    );

    /**
     * Check if a file should be completely ignored
     */
    public boolean shouldIgnoreFile(String fileContent) {
        Matcher matcher = IGNORE_FILE_PATTERN.matcher(fileContent);
        return matcher.find();
    }

    /**
     * Get set of line numbers that should be ignored
     */
    public Set<Integer> getIgnoredLines(String fileContent) {
        Set<Integer> ignoredLines = new HashSet<>();
        String[] lines = fileContent.split("\n");

        boolean inIgnoreBlock = false;
        int lineNum = 1;

        for (String line : lines) {
            // Check for block start
            if (IGNORE_BLOCK_START.matcher(line).find()) {
                inIgnoreBlock = true;
                ignoredLines.add(lineNum);
            }
            // Check for block end
            else if (IGNORE_BLOCK_END.matcher(line).find()) {
                inIgnoreBlock = false;
                ignoredLines.add(lineNum);
            }
            // Add lines within block
            else if (inIgnoreBlock) {
                ignoredLines.add(lineNum);
            }
            // Check for single line ignore (ignores the next line)
            else if (IGNORE_LINE_PATTERN.matcher(line).find()) {
                ignoredLines.add(lineNum);
                ignoredLines.add(lineNum + 1); // Also ignore the next line
            }

            lineNum++;
        }

        return ignoredLines;
    }

    /**
     * Get the ignore reason if specified
     */
    public String getIgnoreReason(String line) {
        Matcher matcher = IGNORE_LINE_PATTERN.matcher(line);
        if (matcher.find()) {
            String reason = matcher.group(1);
            return reason != null ? reason.trim() : null;
        }
        return null;
    }

    /**
     * Check if a specific line should be ignored
     */
    public boolean isLineIgnored(String fileContent, int lineNumber) {
        return getIgnoredLines(fileContent).contains(lineNumber);
    }

    /**
     * Filter out ignored issues from review
     */
    public <T> java.util.List<T> filterIgnoredIssues(
            java.util.List<T> issues,
            java.util.function.Function<T, Integer> lineExtractor,
            Set<Integer> ignoredLines) {
        return issues.stream()
            .filter(issue -> {
                Integer line = lineExtractor.apply(issue);
                return line == null || !ignoredLines.contains(line);
            })
            .toList();
    }
}
