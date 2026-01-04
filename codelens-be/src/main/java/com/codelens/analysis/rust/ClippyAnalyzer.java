package com.codelens.analysis.rust;

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
 * Clippy analyzer for Rust files.
 * Clippy is a collection of lints to catch common mistakes in Rust code.
 */
@Slf4j
@Component
public class ClippyAnalyzer implements StaticAnalyzer {

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("rs");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String getName() {
        return "clippy";
    }

    @Override
    public Set<String> getSupportedExtensions() {
        return SUPPORTED_EXTENSIONS;
    }

    @Override
    public List<AnalysisIssue> analyze(String filename, String content) {
        // Clippy requires a full Cargo project to work
        // For single file analysis, we'd need to create a temp project
        log.debug("Single file Clippy analysis not supported, skipping: {}", filename);
        return List.of();
    }

    @Override
    public List<AnalysisIssue> analyzeFile(String filePath) {
        // Find the Cargo.toml in parent directories
        Path path = Path.of(filePath);
        Path projectDir = findCargoProject(path.getParent());

        if (projectDir == null) {
            log.debug("No Cargo.toml found for {}, skipping Clippy analysis", filePath);
            return List.of();
        }

        return runClippy(projectDir.toString(), filePath);
    }

    /**
     * Analyze a Rust project directory
     */
    public List<AnalysisIssue> analyzeDirectory(String projectDir) {
        if (!Files.exists(Path.of(projectDir, "Cargo.toml"))) {
            log.debug("No Cargo.toml found in {}, skipping Clippy analysis", projectDir);
            return List.of();
        }
        return runClippy(projectDir, projectDir);
    }

    private Path findCargoProject(Path directory) {
        Path current = directory;
        while (current != null) {
            if (Files.exists(current.resolve("Cargo.toml"))) {
                return current;
            }
            current = current.getParent();
        }
        return null;
    }

    private List<AnalysisIssue> runClippy(String projectDir, String filterPath) {
        List<AnalysisIssue> issues = new ArrayList<>();
        Process process = null;

        try {
            ProcessBuilder pb = new ProcessBuilder(
                "cargo", "clippy",
                "--message-format", "json",
                "--quiet",
                "--",
                "-W", "clippy::all",
                "-W", "clippy::pedantic",
                "-W", "clippy::nursery"
            );
            pb.directory(Path.of(projectDir).toFile());
            pb.redirectErrorStream(true);

            process = pb.start();
            String output;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                output = reader.lines().collect(Collectors.joining("\n"));
            }

            process.waitFor();

            // Parse JSON lines output (cargo outputs one JSON object per line)
            for (String line : output.split("\n")) {
                if (!line.isEmpty() && line.startsWith("{")) {
                    try {
                        JsonNode result = objectMapper.readTree(line);
                        // Only process compiler-message type
                        if (result.has("reason") &&
                            "compiler-message".equals(result.get("reason").asText())) {
                            AnalysisIssue issue = parseResult(result, filterPath);
                            if (issue != null) {
                                issues.add(issue);
                            }
                        }
                    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                        // Skip malformed lines
                    }
                }
            }
        } catch (IOException e) {
            log.warn("Clippy I/O error: {}", e.getMessage());
        } catch (InterruptedException e) {
            log.warn("Clippy interrupted: {}", e.getMessage());
            Thread.currentThread().interrupt();
        } finally {
            if (process != null) {
                process.destroyForcibly();
            }
        }

        return issues;
    }

    private AnalysisIssue parseResult(JsonNode result, String filterPath) {
        JsonNode message = result.get("message");
        if (message == null) {
            return null;
        }

        String level = message.has("level") ? message.get("level").asText() : "warning";
        String text = message.has("message") ? message.get("message").asText() : "";

        // Get code/lint name
        String code = "unknown";
        if (message.has("code") && !message.get("code").isNull()) {
            JsonNode codeNode = message.get("code");
            if (codeNode.has("code")) {
                code = codeNode.get("code").asText();
            }
        }

        // Skip internal compiler messages
        if ("error".equals(level) && !code.startsWith("clippy::")) {
            // Could be a compile error, not a lint
            if (!text.contains("clippy")) {
                return null;
            }
        }

        // Get primary span (location)
        String filePath = filterPath;
        int line = 0;
        int column = 0;
        int endLine = 0;
        int endColumn = 0;
        String codeSnippet = null;

        JsonNode spans = message.get("spans");
        if (spans != null && spans.isArray() && !spans.isEmpty()) {
            // Find primary span
            for (JsonNode span : spans) {
                boolean isPrimary = span.has("is_primary") && span.get("is_primary").asBoolean();
                if (isPrimary || spans.size() == 1) {
                    if (span.has("file_name")) {
                        filePath = span.get("file_name").asText();
                    }
                    if (span.has("line_start")) {
                        line = span.get("line_start").asInt();
                    }
                    if (span.has("column_start")) {
                        column = span.get("column_start").asInt();
                    }
                    if (span.has("line_end")) {
                        endLine = span.get("line_end").asInt();
                    }
                    if (span.has("column_end")) {
                        endColumn = span.get("column_end").asInt();
                    }
                    if (span.has("text") && span.get("text").isArray()) {
                        StringBuilder sb = new StringBuilder();
                        for (JsonNode t : span.get("text")) {
                            if (t.has("text")) {
                                sb.append(t.get("text").asText()).append("\n");
                            }
                        }
                        codeSnippet = sb.toString().trim();
                    }
                    break;
                }
            }
        }

        // Get suggestion if available
        String suggestion = null;
        JsonNode children = message.get("children");
        if (children != null && children.isArray()) {
            for (JsonNode child : children) {
                if (child.has("message")) {
                    String childMsg = child.get("message").asText();
                    if (childMsg.startsWith("help:") || childMsg.contains("consider")) {
                        suggestion = childMsg;
                        break;
                    }
                }
            }
        }

        return AnalysisIssue.builder()
            .analyzer(getName())
            .ruleId(code)
            .severity(mapSeverity(level, code))
            .category(categorizeRule(code))
            .message(text)
            .filePath(filePath)
            .line(line)
            .column(column)
            .endLine(endLine)
            .endColumn(endColumn)
            .codeSnippet(codeSnippet)
            .suggestion(suggestion)
            .build();
    }

    private AnalysisIssue.Severity mapSeverity(String level, String code) {
        if ("error".equalsIgnoreCase(level)) {
            return AnalysisIssue.Severity.HIGH;
        }

        // Security-related lints
        if (code.contains("unsafe") || code.contains("security")) {
            return AnalysisIssue.Severity.HIGH;
        }

        // Correctness lints
        if (code.contains("correctness") || code.contains("suspicious")) {
            return AnalysisIssue.Severity.MEDIUM;
        }

        // Pedantic and style
        if (code.contains("pedantic") || code.contains("style")) {
            return AnalysisIssue.Severity.LOW;
        }

        return AnalysisIssue.Severity.MEDIUM;
    }

    private String categorizeRule(String code) {
        if (code.contains("unsafe")) return "Safety";
        if (code.contains("correctness")) return "Correctness";
        if (code.contains("suspicious")) return "Suspicious";
        if (code.contains("complexity")) return "Complexity";
        if (code.contains("perf")) return "Performance";
        if (code.contains("style")) return "Style";
        if (code.contains("pedantic")) return "Pedantic";
        if (code.contains("nursery")) return "Nursery";
        if (code.contains("cargo")) return "Cargo";
        return "Clippy";
    }

    @Override
    public boolean isAvailable() {
        try {
            ProcessBuilder pb = new ProcessBuilder("cargo", "clippy", "--version");
            Process process = pb.start();
            return process.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
