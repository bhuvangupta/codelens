package com.codelens.analysis.python;

import com.codelens.analysis.AnalysisIssue;
import com.codelens.analysis.StaticAnalyzer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.stream.Collectors;

/**
 * Bandit analyzer for Python security issues.
 * Bandit finds common security issues in Python code.
 */
@Slf4j
@Component
public class BanditAnalyzer implements StaticAnalyzer {

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("py", "pyw", "pyi");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String getName() {
        return "bandit";
    }

    @Override
    public Set<String> getSupportedExtensions() {
        return SUPPORTED_EXTENSIONS;
    }

    @Override
    public List<AnalysisIssue> analyze(String filename, String content) {
        List<AnalysisIssue> issues = new ArrayList<>();

        try {
            Path tempFile = Files.createTempFile("bandit", ".py");
            Files.writeString(tempFile, content);

            try {
                issues = runBandit(tempFile.toString(), filename);
            } finally {
                Files.deleteIfExists(tempFile);
            }
        } catch (Exception e) {
            log.warn("Bandit analysis failed for {}: {}", filename, e.getMessage());
        }

        return issues;
    }

    @Override
    public List<AnalysisIssue> analyzeFile(String filePath) {
        return runBandit(filePath, filePath);
    }

    private List<AnalysisIssue> runBandit(String actualPath, String reportedPath) {
        List<AnalysisIssue> issues = new ArrayList<>();
        Process process = null;

        try {
            ProcessBuilder pb = new ProcessBuilder(
                "bandit",
                "-f", "json",
                "-ll",  // Only medium and high severity
                actualPath
            );
            pb.redirectErrorStream(true);

            process = pb.start();
            String output;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                output = reader.lines().collect(Collectors.joining("\n"));
            }

            process.waitFor();

            // Parse JSON output
            if (!output.isEmpty() && output.contains("\"results\"")) {
                JsonNode root = objectMapper.readTree(output);
                JsonNode results = root.get("results");
                if (results != null && results.isArray()) {
                    for (JsonNode result : results) {
                        issues.add(parseResult(result, reportedPath));
                    }
                }
            }
        } catch (IOException e) {
            log.warn("Bandit I/O error: {}", e.getMessage());
        } catch (InterruptedException e) {
            log.warn("Bandit execution interrupted: {}", e.getMessage());
            Thread.currentThread().interrupt();
        } finally {
            if (process != null) {
                process.destroyForcibly();
            }
        }

        return issues;
    }

    private AnalysisIssue parseResult(JsonNode result, String filePath) {
        String testId = result.has("test_id") ? result.get("test_id").asText() : "unknown";
        String testName = result.has("test_name") ? result.get("test_name").asText() : "";
        String message = result.has("issue_text") ? result.get("issue_text").asText() : "";
        String severity = result.has("issue_severity") ? result.get("issue_severity").asText() : "MEDIUM";
        String confidence = result.has("issue_confidence") ? result.get("issue_confidence").asText() : "MEDIUM";
        int line = result.has("line_number") ? result.get("line_number").asInt() : 0;

        // Get code snippet for context
        String codeSnippet = null;
        if (result.has("code") && !result.get("code").isNull()) {
            codeSnippet = result.get("code").asText();
        }

        // More info URL
        String suggestion = null;
        if (result.has("more_info") && !result.get("more_info").isNull()) {
            suggestion = "See: " + result.get("more_info").asText();
        }

        return AnalysisIssue.builder()
            .analyzer(getName())
            .ruleId(testId)
            .severity(mapSeverity(severity, confidence))
            .category("Security")
            .message(testName + ": " + message)
            .filePath(filePath)
            .line(line)
            .column(0)
            .endLine(line)
            .endColumn(0)
            .codeSnippet(codeSnippet)
            .suggestion(suggestion)
            .build();
    }

    private AnalysisIssue.Severity mapSeverity(String severity, String confidence) {
        // High severity + High confidence = CRITICAL
        if ("HIGH".equalsIgnoreCase(severity) && "HIGH".equalsIgnoreCase(confidence)) {
            return AnalysisIssue.Severity.CRITICAL;
        }
        if ("HIGH".equalsIgnoreCase(severity)) {
            return AnalysisIssue.Severity.HIGH;
        }
        if ("MEDIUM".equalsIgnoreCase(severity)) {
            return AnalysisIssue.Severity.MEDIUM;
        }
        return AnalysisIssue.Severity.LOW;
    }

    @Override
    public boolean isAvailable() {
        try {
            ProcessBuilder pb = new ProcessBuilder("bandit", "--version");
            Process process = pb.start();
            return process.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
