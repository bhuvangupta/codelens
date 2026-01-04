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
 * Staticcheck analyzer for Go files.
 * Staticcheck is a state-of-the-art linter for Go.
 */
@Slf4j
@Component
public class StaticcheckAnalyzer implements StaticAnalyzer {

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("go");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String getName() {
        return "staticcheck";
    }

    @Override
    public Set<String> getSupportedExtensions() {
        return SUPPORTED_EXTENSIONS;
    }

    @Override
    public List<AnalysisIssue> analyze(String filename, String content) {
        List<AnalysisIssue> issues = new ArrayList<>();

        try {
            // Create temp directory with proper Go structure
            Path tempDir = Files.createTempDirectory("staticcheck");
            Path tempFile = tempDir.resolve("main.go");
            Files.writeString(tempFile, content);

            try {
                issues = runStaticcheck(tempDir.toString(), filename);
            } finally {
                Files.deleteIfExists(tempFile);
                Files.deleteIfExists(tempDir);
            }
        } catch (Exception e) {
            log.warn("Staticcheck analysis failed for {}: {}", filename, e.getMessage());
        }

        return issues;
    }

    @Override
    public List<AnalysisIssue> analyzeFile(String filePath) {
        Path path = Path.of(filePath);
        return runStaticcheck(path.getParent().toString(), filePath);
    }

    /**
     * Analyze a Go package/directory
     */
    public List<AnalysisIssue> analyzeDirectory(String projectDir) {
        return runStaticcheck(projectDir, projectDir);
    }

    private List<AnalysisIssue> runStaticcheck(String directory, String reportedPath) {
        List<AnalysisIssue> issues = new ArrayList<>();
        Process process = null;

        try {
            ProcessBuilder pb = new ProcessBuilder(
                "staticcheck",
                "-f", "json",
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

            // Parse JSON lines output (one JSON object per line)
            for (String line : output.split("\n")) {
                if (!line.isEmpty() && line.startsWith("{")) {
                    try {
                        JsonNode result = objectMapper.readTree(line);
                        issues.add(parseResult(result, reportedPath));
                    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                        // Skip malformed lines
                    }
                }
            }
        } catch (IOException e) {
            log.warn("Staticcheck I/O error: {}", e.getMessage());
        } catch (InterruptedException e) {
            log.warn("Staticcheck interrupted: {}", e.getMessage());
            Thread.currentThread().interrupt();
        } finally {
            if (process != null) {
                process.destroyForcibly();
            }
        }

        return issues;
    }

    private AnalysisIssue parseResult(JsonNode result, String defaultPath) {
        String code = result.has("code") ? result.get("code").asText() : "unknown";
        String message = result.has("message") ? result.get("message").asText() : "";
        String severity = result.has("severity") ? result.get("severity").asText() : "warning";

        // Location info
        JsonNode location = result.get("location");
        String filePath = defaultPath;
        int line = 0;
        int column = 0;

        if (location != null) {
            if (location.has("file")) {
                filePath = location.get("file").asText();
            }
            if (location.has("line")) {
                line = location.get("line").asInt();
            }
            if (location.has("column")) {
                column = location.get("column").asInt();
            }
        }

        // End location
        int endLine = line;
        int endColumn = column;
        JsonNode end = result.get("end");
        if (end != null) {
            if (end.has("line")) endLine = end.get("line").asInt();
            if (end.has("column")) endColumn = end.get("column").asInt();
        }

        return AnalysisIssue.builder()
            .analyzer(getName())
            .ruleId(code)
            .severity(mapSeverity(code, severity))
            .category(categorizeRule(code))
            .message(message)
            .filePath(filePath)
            .line(line)
            .column(column)
            .endLine(endLine)
            .endColumn(endColumn)
            .build();
    }

    private AnalysisIssue.Severity mapSeverity(String code, String severity) {
        // SA* rules are often bugs/security issues
        if (code.startsWith("SA")) {
            return AnalysisIssue.Severity.HIGH;
        }
        // S* rules are simple code issues
        if (code.startsWith("S1")) {
            return AnalysisIssue.Severity.LOW;
        }
        // ST* rules are style issues
        if (code.startsWith("ST")) {
            return AnalysisIssue.Severity.LOW;
        }
        // QF* rules are quickfixes
        if (code.startsWith("QF")) {
            return AnalysisIssue.Severity.LOW;
        }

        if ("error".equalsIgnoreCase(severity)) {
            return AnalysisIssue.Severity.HIGH;
        }
        return AnalysisIssue.Severity.MEDIUM;
    }

    private String categorizeRule(String code) {
        if (code.startsWith("SA1")) return "Bugs";
        if (code.startsWith("SA2")) return "Concurrency";
        if (code.startsWith("SA3")) return "Testing";
        if (code.startsWith("SA4")) return "Code Quality";
        if (code.startsWith("SA5")) return "Correctness";
        if (code.startsWith("SA6")) return "Performance";
        if (code.startsWith("SA9")) return "Dubious";
        if (code.startsWith("S1")) return "Simplification";
        if (code.startsWith("ST")) return "Style";
        if (code.startsWith("QF")) return "Quick Fix";
        return "Analysis";
    }

    @Override
    public boolean isAvailable() {
        try {
            ProcessBuilder pb = new ProcessBuilder("staticcheck", "-version");
            Process process = pb.start();
            return process.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
