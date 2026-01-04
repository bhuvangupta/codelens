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
 * Ruff analyzer for Python files.
 * Ruff is an extremely fast Python linter that replaces flake8, isort, and others.
 */
@Slf4j
@Component
public class RuffAnalyzer implements StaticAnalyzer {

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("py", "pyw", "pyi");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String getName() {
        return "ruff";
    }

    @Override
    public Set<String> getSupportedExtensions() {
        return SUPPORTED_EXTENSIONS;
    }

    @Override
    public List<AnalysisIssue> analyze(String filename, String content) {
        List<AnalysisIssue> issues = new ArrayList<>();

        try {
            // Write content to temp file
            Path tempFile = Files.createTempFile("ruff", ".py");
            Files.writeString(tempFile, content);

            try {
                issues = runRuff(tempFile.toString(), filename);
            } finally {
                Files.deleteIfExists(tempFile);
            }
        } catch (Exception e) {
            log.warn("Ruff analysis failed for {}: {}", filename, e.getMessage());
        }

        return issues;
    }

    @Override
    public List<AnalysisIssue> analyzeFile(String filePath) {
        return runRuff(filePath, filePath);
    }

    private List<AnalysisIssue> runRuff(String actualPath, String reportedPath) {
        List<AnalysisIssue> issues = new ArrayList<>();
        Process process = null;

        try {
            ProcessBuilder pb = new ProcessBuilder(
                "ruff", "check",
                "--output-format", "json",
                "--select", "E,F,W,C,B,S,N,UP,ANN,ASYNC,A,COM,DTZ,DJ,EXE,FA,ISC,ICN,G,INP,PIE,T20,PYI,PT,Q,RSE,RET,SLF,SLOT,SIM,TID,TCH,INT,ARG,PTH,TD,FIX,ERA,PD,PGH,PL,TRY,FLY,NPY,AIR,PERF,FURB,LOG,RUF",
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
            if (!output.isEmpty() && output.startsWith("[")) {
                JsonNode results = objectMapper.readTree(output);
                for (JsonNode result : results) {
                    issues.add(parseResult(result, reportedPath));
                }
            }
        } catch (IOException e) {
            log.warn("Ruff I/O error: {}", e.getMessage());
        } catch (InterruptedException e) {
            log.warn("Ruff execution interrupted: {}", e.getMessage());
            Thread.currentThread().interrupt();
        } finally {
            if (process != null) {
                process.destroyForcibly();
            }
        }

        return issues;
    }

    private AnalysisIssue parseResult(JsonNode result, String filePath) {
        String code = result.has("code") ? result.get("code").asText() : "unknown";
        String message = result.has("message") ? result.get("message").asText() : "";

        // Location info
        JsonNode location = result.get("location");
        int line = location != null && location.has("row") ? location.get("row").asInt() : 0;
        int column = location != null && location.has("column") ? location.get("column").asInt() : 0;

        JsonNode endLocation = result.get("end_location");
        int endLine = endLocation != null && endLocation.has("row") ? endLocation.get("row").asInt() : line;
        int endColumn = endLocation != null && endLocation.has("column") ? endLocation.get("column").asInt() : column;

        // Check for fix suggestion
        String suggestion = null;
        if (result.has("fix") && !result.get("fix").isNull()) {
            JsonNode fix = result.get("fix");
            if (fix.has("message")) {
                suggestion = fix.get("message").asText();
            }
        }

        return AnalysisIssue.builder()
            .analyzer(getName())
            .ruleId(code)
            .severity(mapSeverity(code))
            .category(categorizeRule(code))
            .message(message)
            .filePath(filePath)
            .line(line)
            .column(column)
            .endLine(endLine)
            .endColumn(endColumn)
            .suggestion(suggestion)
            .build();
    }

    private AnalysisIssue.Severity mapSeverity(String code) {
        // Security rules (S prefix - bandit rules)
        if (code.startsWith("S")) {
            return AnalysisIssue.Severity.HIGH;
        }
        // Errors
        if (code.startsWith("E") || code.startsWith("F")) {
            return AnalysisIssue.Severity.MEDIUM;
        }
        // Bugbear (B prefix) - likely bugs
        if (code.startsWith("B")) {
            return AnalysisIssue.Severity.MEDIUM;
        }
        // Warnings
        if (code.startsWith("W")) {
            return AnalysisIssue.Severity.LOW;
        }
        return AnalysisIssue.Severity.LOW;
    }

    private String categorizeRule(String code) {
        if (code.startsWith("S")) return "Security";
        if (code.startsWith("E")) return "Error";
        if (code.startsWith("F")) return "PyFlakes";
        if (code.startsWith("W")) return "Warning";
        if (code.startsWith("C")) return "Complexity";
        if (code.startsWith("B")) return "Bugbear";
        if (code.startsWith("N")) return "Naming";
        if (code.startsWith("UP")) return "Upgrade";
        if (code.startsWith("ANN")) return "Annotations";
        if (code.startsWith("ASYNC")) return "Async";
        if (code.startsWith("PL")) return "Pylint";
        if (code.startsWith("TRY")) return "Exception";
        if (code.startsWith("PERF")) return "Performance";
        if (code.startsWith("RUF")) return "Ruff";
        return "Code Quality";
    }

    @Override
    public boolean isAvailable() {
        try {
            ProcessBuilder pb = new ProcessBuilder("ruff", "--version");
            Process process = pb.start();
            return process.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
