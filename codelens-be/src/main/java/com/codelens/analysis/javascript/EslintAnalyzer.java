package com.codelens.analysis.javascript;

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
 * ESLint analyzer for JavaScript/TypeScript files.
 * Runs ESLint via Node.js and parses JSON output.
 * Automatically uses project's .eslintrc config when available.
 */
@Slf4j
@Component
public class EslintAnalyzer implements StaticAnalyzer {

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(
        "js", "jsx", "ts", "tsx", "mjs", "cjs"
    );

    // ESLint config file patterns to look for
    private static final List<String> ESLINT_CONFIG_FILES = List.of(
        ".eslintrc",
        ".eslintrc.js",
        ".eslintrc.cjs",
        ".eslintrc.json",
        ".eslintrc.yaml",
        ".eslintrc.yml",
        "eslint.config.js",      // ESLint flat config
        "eslint.config.mjs",
        "eslint.config.cjs"
    );

    private final ObjectMapper objectMapper = new ObjectMapper();

    // Thread-local to store ESLint config for current analysis
    private final ThreadLocal<EslintConfig> eslintConfigHolder = new ThreadLocal<>();

    // Thread-local temp directory - reused across files in same thread to reduce I/O
    private final ThreadLocal<Path> tempDirHolder = new ThreadLocal<>();

    @Override
    public String getName() {
        return "eslint";
    }

    @Override
    public Set<String> getSupportedExtensions() {
        return SUPPORTED_EXTENSIONS;
    }

    /**
     * Holds ESLint configuration fetched from the repository.
     */
    public record EslintConfig(String configFilename, String configContent) {}

    /**
     * Get list of ESLint config file names to search for in a repo.
     */
    public static List<String> getConfigFileNames() {
        return ESLINT_CONFIG_FILES;
    }

    /**
     * Set the ESLint config for current thread (fetched from repo).
     * Call this before analyze() to use project's config.
     */
    public void setEslintConfig(EslintConfig config) {
        this.eslintConfigHolder.set(config);
    }

    /**
     * Clear the ESLint config after analysis.
     * @deprecated Use {@link #cleanupSession()} to also clean up temp directories
     */
    @Deprecated
    public void clearEslintConfig() {
        this.eslintConfigHolder.remove();
    }

    /**
     * Clean up thread-local resources after analysis session.
     * Call this in a finally block after analyzing all files.
     * Clears ESLint config and deletes temp directory.
     */
    public void cleanupSession() {
        // Clear config
        eslintConfigHolder.remove();

        // Clean up temp directory
        Path tempDir = tempDirHolder.get();
        if (tempDir != null) {
            try {
                Files.walk(tempDir)
                    .sorted((a, b) -> b.compareTo(a)) // Delete files before directories
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException e) {
                            log.debug("Failed to delete temp file: {}", path);
                        }
                    });
            } catch (IOException e) {
                log.debug("Failed to clean up temp directory: {}", tempDir);
            } finally {
                tempDirHolder.remove();
            }
        }
    }

    /**
     * Get or create thread-local temp directory for ESLint analysis.
     * Reuses the same directory across multiple file analyses to reduce I/O.
     */
    private Path getOrCreateTempDir() throws IOException {
        Path dir = tempDirHolder.get();
        if (dir == null || !Files.exists(dir)) {
            dir = Files.createTempDirectory("eslint-session-");
            tempDirHolder.set(dir);
            log.debug("Created ESLint temp directory: {}", dir);
        }
        return dir;
    }

    /**
     * Analyze with explicit ESLint config content from the repository.
     * Note: Call {@link #cleanupSession()} after all files are analyzed.
     */
    public List<AnalysisIssue> analyzeWithConfig(String filename, String content,
                                                   String configFilename, String configContent) {
        // Set config if not already set (allows reuse across files)
        if (eslintConfigHolder.get() == null) {
            setEslintConfig(new EslintConfig(configFilename, configContent));
        }
        return analyze(filename, content);
        // Note: cleanup is now handled by cleanupSession() at end of review
    }

    @Override
    public List<AnalysisIssue> analyze(String filename, String content) {
        List<AnalysisIssue> issues = new ArrayList<>();
        EslintConfig config = eslintConfigHolder.get();

        try {
            if (config != null && config.configContent() != null) {
                // Use project config - create temp directory with config and source
                issues = analyzeWithProjectConfig(filename, content, config);
            } else {
                // No project config - use default behavior
                String extension = getExtension(filename);
                Path tempFile = Files.createTempFile("eslint", "." + extension);
                Files.writeString(tempFile, content);

                try {
                    issues = runEslint(tempFile.toString(), filename, null);
                } finally {
                    Files.deleteIfExists(tempFile);
                }
            }
        } catch (Exception e) {
            log.warn("ESLint analysis failed for {}: {}", filename, e.getMessage());
        }

        return issues;
    }

    /**
     * Analyze file using project's ESLint config.
     * Reuses thread-local temp directory for efficiency in parallel processing.
     * The temp directory is cleaned up by calling {@link #cleanupSession()}.
     */
    private List<AnalysisIssue> analyzeWithProjectConfig(String filename, String content,
                                                          EslintConfig config) throws IOException {
        // Get or create reusable temp directory for this thread
        Path tempDir = getOrCreateTempDir();

        // Write ESLint config file (only if not already present or content changed)
        Path configPath = tempDir.resolve(config.configFilename());
        if (!Files.exists(configPath)) {
            Files.writeString(configPath, config.configContent());
            log.debug("Wrote ESLint config: {} to {}", config.configFilename(), tempDir);
        }

        // Write source file (overwrites previous file)
        String extension = getExtension(filename);
        Path sourceFile = tempDir.resolve("source." + extension);
        Files.writeString(sourceFile, content);

        // Run ESLint from temp directory
        // Note: temp directory cleanup is handled by cleanupSession()
        return runEslint(sourceFile.toString(), filename, tempDir);
    }

    @Override
    public List<AnalysisIssue> analyzeFile(String filePath) {
        return runEslint(filePath, filePath, null);
    }

    private List<AnalysisIssue> runEslint(String actualPath, String reportedPath, Path workingDirectory) {
        List<AnalysisIssue> issues = new ArrayList<>();
        Process process = null;

        try {
            ProcessBuilder pb = new ProcessBuilder(
                "npx", "eslint",
                "--format", "json",
                "--no-error-on-unmatched-pattern",
                actualPath
            );
            pb.redirectErrorStream(true);

            // Use project directory for ESLint to find local config
            if (workingDirectory != null) {
                pb.directory(workingDirectory.toFile());
            }

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
                    JsonNode messages = result.get("messages");
                    if (messages != null && messages.isArray()) {
                        for (JsonNode msg : messages) {
                            issues.add(parseMessage(msg, reportedPath));
                        }
                    }
                }
            }
        } catch (IOException e) {
            log.warn("ESLint I/O error: {}", e.getMessage());
        } catch (InterruptedException e) {
            log.warn("ESLint execution interrupted: {}", e.getMessage());
            Thread.currentThread().interrupt();
        } finally {
            if (process != null) {
                process.destroyForcibly();
            }
        }

        return issues;
    }

    private AnalysisIssue parseMessage(JsonNode msg, String filePath) {
        String ruleId = msg.has("ruleId") && !msg.get("ruleId").isNull() ?
            msg.get("ruleId").asText() : "unknown";
        int severity = msg.has("severity") ? msg.get("severity").asInt() : 1;
        String message = msg.has("message") ? msg.get("message").asText() : "";
        int line = msg.has("line") ? msg.get("line").asInt() : 0;
        int column = msg.has("column") ? msg.get("column").asInt() : 0;
        int endLine = msg.has("endLine") ? msg.get("endLine").asInt() : line;
        int endColumn = msg.has("endColumn") ? msg.get("endColumn").asInt() : column;

        String suggestion = null;
        if (msg.has("suggestions") && msg.get("suggestions").isArray() &&
            !msg.get("suggestions").isEmpty()) {
            suggestion = msg.get("suggestions").get(0).get("desc").asText();
        }

        return AnalysisIssue.builder()
            .analyzer(getName())
            .ruleId(ruleId)
            .severity(mapSeverity(severity, ruleId))
            .category(categorizeRule(ruleId))
            .message(message)
            .filePath(filePath)
            .line(line)
            .column(column)
            .endLine(endLine)
            .endColumn(endColumn)
            .suggestion(suggestion)
            .build();
    }

    private AnalysisIssue.Severity mapSeverity(int eslintSeverity, String ruleId) {
        // Security rules are always high/critical
        if (isSecurityRule(ruleId)) {
            return AnalysisIssue.Severity.HIGH;
        }

        // ESLint severity: 1 = warning, 2 = error
        return eslintSeverity == 2 ?
            AnalysisIssue.Severity.MEDIUM :
            AnalysisIssue.Severity.LOW;
    }

    private boolean isSecurityRule(String ruleId) {
        return ruleId.contains("security") ||
               ruleId.contains("xss") ||
               ruleId.contains("injection") ||
               ruleId.startsWith("@typescript-eslint/no-unsafe") ||
               "no-eval".equals(ruleId) ||
               "no-implied-eval".equals(ruleId) ||
               "no-new-func".equals(ruleId);
    }

    private String categorizeRule(String ruleId) {
        if (isSecurityRule(ruleId)) {
            return "Security";
        }
        if (ruleId.startsWith("@typescript-eslint/")) {
            return "TypeScript";
        }
        if (ruleId.startsWith("react/") || ruleId.startsWith("react-hooks/")) {
            return "React";
        }
        if (ruleId.startsWith("import/")) {
            return "Imports";
        }
        if (ruleId.contains("prefer-") || ruleId.contains("no-unused")) {
            return "Best Practices";
        }
        return "Code Quality";
    }

    @Override
    public boolean isAvailable() {
        try {
            ProcessBuilder pb = new ProcessBuilder("npx", "eslint", "--version");
            Process process = pb.start();
            return process.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private String getExtension(String filename) {
        int lastDot = filename.lastIndexOf('.');
        return lastDot > 0 ? filename.substring(lastDot + 1) : "js";
    }
}
