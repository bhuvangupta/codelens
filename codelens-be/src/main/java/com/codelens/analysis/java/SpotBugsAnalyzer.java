package com.codelens.analysis.java;

import com.codelens.analysis.AnalysisIssue;
import com.codelens.analysis.StaticAnalyzer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SpotBugs analyzer for Java bytecode analysis.
 * Note: SpotBugs requires compiled .class files, so this analyzer
 * works on compiled projects rather than source files directly.
 */
@Slf4j
@Component
public class SpotBugsAnalyzer implements StaticAnalyzer {

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("java", "class");
    private static final Pattern BUG_PATTERN = Pattern.compile(
        "^([A-Z_]+)\\s+(.+?):(\\d+)\\s+-\\s+(.+)$"
    );

    @Override
    public String getName() {
        return "spotbugs";
    }

    @Override
    public Set<String> getSupportedExtensions() {
        return SUPPORTED_EXTENSIONS;
    }

    @Override
    public List<AnalysisIssue> analyze(String filename, String content) {
        // SpotBugs analyzes bytecode, not source
        // For source analysis, we'd need to compile first
        log.debug("SpotBugs requires compiled bytecode, skipping source analysis");
        return List.of();
    }

    @Override
    public List<AnalysisIssue> analyzeFile(String filePath) {
        List<AnalysisIssue> issues = new ArrayList<>();

        // Check if it's a class file or find corresponding class file
        if (!filePath.endsWith(".class")) {
            log.debug("SpotBugs analyzes .class files, skipping {}", filePath);
            return issues;
        }

        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(
                "spotbugs",
                "-textui",
                "-low",
                filePath
            );
            pb.redirectErrorStream(true);

            process = pb.start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {

                String line;
                while ((line = reader.readLine()) != null) {
                    Matcher matcher = BUG_PATTERN.matcher(line);
                    if (matcher.matches()) {
                        String bugType = matcher.group(1);
                        String file = matcher.group(2);
                        int lineNum = Integer.parseInt(matcher.group(3));
                        String message = matcher.group(4);

                        issues.add(AnalysisIssue.builder()
                            .analyzer(getName())
                            .ruleId(bugType)
                            .severity(mapBugCategory(bugType))
                            .category(categorize(bugType))
                            .message(message)
                            .filePath(file)
                            .line(lineNum)
                            .build());
                    }
                }
            }

            process.waitFor();
        } catch (IOException e) {
            log.warn("SpotBugs I/O error: {}", e.getMessage());
        } catch (InterruptedException e) {
            log.warn("SpotBugs interrupted: {}", e.getMessage());
            Thread.currentThread().interrupt();
        } finally {
            if (process != null) {
                process.destroyForcibly();
            }
        }

        return issues;
    }

    /**
     * Analyze a directory containing compiled classes
     */
    public List<AnalysisIssue> analyzeDirectory(String classesDir) {
        List<AnalysisIssue> issues = new ArrayList<>();
        Process process = null;

        try {
            ProcessBuilder pb = new ProcessBuilder(
                "spotbugs",
                "-textui",
                "-low",
                "-include", "findbugs-security-include.xml",
                classesDir
            );
            pb.redirectErrorStream(true);

            process = pb.start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {

                String line;
                while ((line = reader.readLine()) != null) {
                    Matcher matcher = BUG_PATTERN.matcher(line);
                    if (matcher.matches()) {
                        String bugType = matcher.group(1);
                        String file = matcher.group(2);
                        int lineNum = Integer.parseInt(matcher.group(3));
                        String message = matcher.group(4);

                        issues.add(AnalysisIssue.builder()
                            .analyzer(getName())
                            .ruleId(bugType)
                            .severity(mapBugCategory(bugType))
                            .category(categorize(bugType))
                            .message(message)
                            .filePath(file)
                            .line(lineNum)
                            .build());
                    }
                }
            }

            process.waitFor();
        } catch (IOException e) {
            log.warn("SpotBugs I/O error: {}", e.getMessage());
        } catch (InterruptedException e) {
            log.warn("SpotBugs interrupted: {}", e.getMessage());
            Thread.currentThread().interrupt();
        } finally {
            if (process != null) {
                process.destroyForcibly();
            }
        }

        return issues;
    }

    @Override
    public boolean isAvailable() {
        try {
            ProcessBuilder pb = new ProcessBuilder("spotbugs", "-version");
            Process process = pb.start();
            return process.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private AnalysisIssue.Severity mapBugCategory(String bugType) {
        // Security-related bugs are higher severity
        if (bugType.startsWith("SQL_") || bugType.startsWith("XSS_") ||
            bugType.startsWith("PATH_TRAVERSAL") || bugType.startsWith("COMMAND_INJECTION") ||
            bugType.startsWith("LDAP_INJECTION") || bugType.startsWith("XPATH_INJECTION")) {
            return AnalysisIssue.Severity.CRITICAL;
        }
        if (bugType.startsWith("SECURITY") || bugType.contains("INJECTION")) {
            return AnalysisIssue.Severity.HIGH;
        }
        if (bugType.startsWith("NP_") || bugType.startsWith("NULL_")) {
            return AnalysisIssue.Severity.MEDIUM;
        }
        return AnalysisIssue.Severity.LOW;
    }

    private String categorize(String bugType) {
        if (bugType.startsWith("SQL_") || bugType.startsWith("XSS_") ||
            bugType.contains("INJECTION") || bugType.startsWith("SECURITY")) {
            return "Security";
        }
        if (bugType.startsWith("NP_") || bugType.startsWith("NULL_")) {
            return "Null Pointer";
        }
        if (bugType.startsWith("RCN_") || bugType.startsWith("BC_")) {
            return "Bad Practice";
        }
        if (bugType.startsWith("DM_") || bugType.startsWith("EQ_")) {
            return "Performance";
        }
        return "Bug";
    }
}
