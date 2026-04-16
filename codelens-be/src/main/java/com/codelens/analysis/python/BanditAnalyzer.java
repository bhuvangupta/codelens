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

@Slf4j
@Component
public class BanditAnalyzer implements StaticAnalyzer {

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("py", "pyw", "pyi");

    private static final List<String> BANDIT_CONFIG_FILES = List.of(
        ".bandit",
        "bandit.yaml",
        "pyproject.toml"
    );

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Holds Bandit configuration fetched from the repository.
     */
    public record BanditConfig(String configFilename, String configContent) {}

    /**
     * Get list of Bandit config file names to search for in a repo.
     * Note: pyproject.toml only counts if it contains a [tool.bandit] section
     * (caller should verify before constructing BanditConfig).
     */
    public static List<String> getConfigFileNames() {
        return BANDIT_CONFIG_FILES;
    }

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
        return analyzeWithConfig(filename, content, null);
    }

    /**
     * Analyze with optional project config. If config is non-null, writes the config
     * to a temp dir, runs bandit with -c pointing at it, AND overrides severity to
     * --severity-level low --confidence-level medium (security floor — repo can
     * customize which tests run but cannot raise the severity threshold).
     * If config is null, runs with -ll (medium+ severity, default behavior).
     */
    public List<AnalysisIssue> analyzeWithConfig(String filename, String content, BanditConfig config) {
        List<AnalysisIssue> issues = new ArrayList<>();
        Path tempDir = null;

        try {
            if (config != null && config.configContent() != null && !config.configContent().isBlank()) {
                tempDir = Files.createTempDirectory("bandit-session-");
                Path configPath = tempDir.resolve(config.configFilename());
                Files.writeString(configPath, config.configContent());
                Path sourceFile = tempDir.resolve("source.py");
                Files.writeString(sourceFile, content);
                issues = runBandit(sourceFile.toString(), filename, configPath.toString());
                log.info("Bandit: Using project config from {} + severity floor (--severity-level low)",
                    config.configFilename());
            } else {
                Path tempFile = Files.createTempFile("bandit", ".py");
                Files.writeString(tempFile, content);
                try {
                    issues = runBandit(tempFile.toString(), filename, null);
                    log.debug("Bandit: Using bundled CodeLens default (-ll)");
                } finally {
                    Files.deleteIfExists(tempFile);
                }
            }
        } catch (Exception e) {
            log.warn("Bandit analysis failed for {}: {}", filename, e.getMessage());
        } finally {
            if (tempDir != null) {
                try {
                    Files.walk(tempDir)
                        .sorted((a, b) -> b.compareTo(a))
                        .forEach(p -> {
                            try {
                                Files.deleteIfExists(p);
                            } catch (IOException e) {
                                log.debug("Failed to delete temp file: {}", p);
                            }
                        });
                } catch (IOException e) {
                    log.debug("Failed to clean up Bandit temp dir: {}", tempDir);
                }
            }
        }

        return issues;
    }

    @Override
    public List<AnalysisIssue> analyzeFile(String filePath) {
        return runBandit(filePath, filePath, null);
    }

    private List<AnalysisIssue> runBandit(String actualPath, String reportedPath, String configPath) {
        List<AnalysisIssue> issues = new ArrayList<>();
        Process process = null;

        try {
            List<String> command = new ArrayList<>();
            command.add("bandit");
            command.add("-f");
            command.add("json");
            if (configPath != null) {
                command.add("-c");
                command.add(configPath);
                command.add("--severity-level");
                command.add("low");
                command.add("--confidence-level");
                command.add("medium");
            } else {
                command.add("-ll");
            }
            command.add(actualPath);

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);

            process = pb.start();
            String output;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                output = reader.lines().collect(Collectors.joining("\n"));
            }

            boolean finished = process.waitFor(10, java.util.concurrent.TimeUnit.MINUTES);
            if (!finished) {
                log.warn("Bandit timed out after 10 minutes for {}", reportedPath);
                process.destroyForcibly();
                return issues;
            }
            int exitCode = process.exitValue();

            if (!output.isEmpty() && output.contains("\"results\"")) {
                JsonNode root = objectMapper.readTree(output);
                JsonNode results = root.get("results");
                if (results != null && results.isArray()) {
                    for (JsonNode result : results) {
                        issues.add(parseResult(result, reportedPath));
                    }
                }
            } else if (!output.isEmpty() && !output.contains("\"results\"")) {
                // Output is non-empty but not valid Bandit JSON — likely a config error.
                String truncated = output.length() > 500 ? output.substring(0, 500) + "..." : output;
                log.warn("Bandit exited {} with non-JSON output for {}: {}", exitCode, reportedPath, truncated);
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

        String codeSnippet = null;
        if (result.has("code") && !result.get("code").isNull()) {
            codeSnippet = result.get("code").asText();
        }

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
