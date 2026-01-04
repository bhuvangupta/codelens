package com.codelens.analysis.go;

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
 * Gosec analyzer for Go security issues.
 * Gosec inspects Go source code for security problems.
 */
@Slf4j
@Component
public class GosecAnalyzer implements StaticAnalyzer {

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("go");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String getName() {
        return "gosec";
    }

    @Override
    public Set<String> getSupportedExtensions() {
        return SUPPORTED_EXTENSIONS;
    }

    @Override
    public List<AnalysisIssue> analyze(String filename, String content) {
        List<AnalysisIssue> issues = new ArrayList<>();

        try {
            Path tempDir = Files.createTempDirectory("gosec");
            Path tempFile = tempDir.resolve("main.go");
            Files.writeString(tempFile, content);

            try {
                issues = runGosec(tempDir.toString(), filename);
            } finally {
                Files.deleteIfExists(tempFile);
                Files.deleteIfExists(tempDir);
            }
        } catch (Exception e) {
            log.warn("Gosec analysis failed for {}: {}", filename, e.getMessage());
        }

        return issues;
    }

    @Override
    public List<AnalysisIssue> analyzeFile(String filePath) {
        Path path = Path.of(filePath);
        return runGosec(path.getParent().toString(), filePath);
    }

    /**
     * Analyze a Go package/directory for security issues
     */
    public List<AnalysisIssue> analyzeDirectory(String projectDir) {
        return runGosec(projectDir, projectDir);
    }

    private List<AnalysisIssue> runGosec(String directory, String reportedPath) {
        List<AnalysisIssue> issues = new ArrayList<>();
        Process process = null;

        try {
            ProcessBuilder pb = new ProcessBuilder(
                "gosec",
                "-fmt", "json",
                "-quiet",
                "./..."
            );
            pb.directory(Path.of(directory).toFile());
            pb.redirectErrorStream(true);

            process = pb.start();
            String output;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                output = reader.lines().collect(Collectors.joining("\n"));
            }

            process.waitFor();

            // Parse JSON output
            if (!output.isEmpty() && output.contains("\"Issues\"")) {
                JsonNode root = objectMapper.readTree(output);
                JsonNode issuesNode = root.get("Issues");

                if (issuesNode != null && issuesNode.isArray()) {
                    for (JsonNode issue : issuesNode) {
                        issues.add(parseIssue(issue, reportedPath));
                    }
                }
            }
        } catch (IOException e) {
            log.warn("Gosec I/O error: {}", e.getMessage());
        } catch (InterruptedException e) {
            log.warn("Gosec interrupted: {}", e.getMessage());
            Thread.currentThread().interrupt();
        } finally {
            if (process != null) {
                process.destroyForcibly();
            }
        }

        return issues;
    }

    private AnalysisIssue parseIssue(JsonNode issue, String defaultPath) {
        String ruleId = issue.has("rule_id") ? issue.get("rule_id").asText() : "unknown";
        String details = issue.has("details") ? issue.get("details").asText() : "";
        String severity = issue.has("severity") ? issue.get("severity").asText() : "MEDIUM";
        String confidence = issue.has("confidence") ? issue.get("confidence").asText() : "MEDIUM";

        String filePath = defaultPath;
        int line = 0;
        int column = 0;

        if (issue.has("file")) {
            filePath = issue.get("file").asText();
        }
        if (issue.has("line")) {
            String lineStr = issue.get("line").asText();
            try {
                // gosec returns line as string, sometimes with range "10-12"
                line = Integer.parseInt(lineStr.split("-")[0]);
            } catch (NumberFormatException e) {
                line = 0;
            }
        }
        if (issue.has("column")) {
            String colStr = issue.get("column").asText();
            try {
                column = Integer.parseInt(colStr.split("-")[0]);
            } catch (NumberFormatException e) {
                column = 0;
            }
        }

        // Get code snippet
        String codeSnippet = null;
        if (issue.has("code") && !issue.get("code").isNull()) {
            codeSnippet = issue.get("code").asText();
        }

        // CWE reference
        String cwe = null;
        if (issue.has("cwe") && issue.get("cwe").has("id")) {
            cwe = "CWE-" + issue.get("cwe").get("id").asText();
        }

        String message = details;
        if (cwe != null) {
            message = "[" + cwe + "] " + details;
        }

        return AnalysisIssue.builder()
            .analyzer(getName())
            .ruleId(ruleId)
            .severity(mapSeverity(severity, confidence))
            .category("Security")
            .message(message)
            .filePath(filePath)
            .line(line)
            .column(column)
            .endLine(line)
            .endColumn(column)
            .codeSnippet(codeSnippet)
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
            ProcessBuilder pb = new ProcessBuilder("gosec", "-version");
            Process process = pb.start();
            return process.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
