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
public class RuffAnalyzer implements StaticAnalyzer {

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("py", "pyw", "pyi");

    private static final List<String> RUFF_CONFIG_FILES = List.of(
        "ruff.toml",
        ".ruff.toml",
        "pyproject.toml"
    );

    private static final String DEFAULT_SELECT =
        "E,F,W,C,B,S,N,UP,ANN,ASYNC,A,COM,DTZ,DJ,EXE,FA,ISC,ICN,G,INP,PIE,T20,PYI,PT,Q,RSE,RET,SLF,SLOT,SIM,TID,TCH,INT,ARG,PTH,TD,FIX,ERA,PD,PGH,PL,TRY,FLY,NPY,AIR,PERF,FURB,LOG,RUF";

    private static final String SECURITY_FLOOR_SELECT = "S,B";

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Holds Ruff configuration fetched from the repository.
     */
    public record RuffConfig(String configFilename, String configContent) {}

    /**
     * Get list of Ruff config file names to search for in a repo.
     * Note: pyproject.toml only counts if it contains a [tool.ruff] section
     * (caller should verify before constructing RuffConfig).
     */
    public static List<String> getConfigFileNames() {
        return RUFF_CONFIG_FILES;
    }

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
        return analyzeWithConfig(filename, content, null);
    }

    /**
     * Analyze with optional project config. If config is non-null, writes the config
     * to a temp dir alongside the source file, drops the default --select (lets project
     * config decide rule selection), and adds --extend-select S,B (security floor).
     * If config is null, uses the hardcoded default --select.
     */
    public List<AnalysisIssue> analyzeWithConfig(String filename, String content, RuffConfig config) {
        List<AnalysisIssue> issues = new ArrayList<>();
        Path tempDir = null;

        try {
            if (config != null && config.configContent() != null && !config.configContent().isBlank()) {
                tempDir = Files.createTempDirectory("ruff-session-");
                Path configPath = tempDir.resolve(config.configFilename());
                Files.writeString(configPath, config.configContent());
                Path sourceFile = tempDir.resolve("source.py");
                Files.writeString(sourceFile, content);
                issues = runRuff(sourceFile.toString(), filename, true, tempDir);
                log.info("Ruff: Using project config from {} + security floor (--extend-select {})",
                    config.configFilename(), SECURITY_FLOOR_SELECT);
            } else {
                Path tempFile = Files.createTempFile("ruff", ".py");
                Files.writeString(tempFile, content);
                try {
                    issues = runRuff(tempFile.toString(), filename, false, null);
                    log.debug("Ruff: Using bundled CodeLens default --select");
                } finally {
                    Files.deleteIfExists(tempFile);
                }
            }
        } catch (Exception e) {
            log.warn("Ruff analysis failed for {}: {}", filename, e.getMessage());
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
                    log.debug("Failed to clean up Ruff temp dir: {}", tempDir);
                }
            }
        }

        return issues;
    }

    @Override
    public List<AnalysisIssue> analyzeFile(String filePath) {
        return runRuff(filePath, filePath, false, null);
    }

    private List<AnalysisIssue> runRuff(String actualPath, String reportedPath,
                                        boolean useProjectConfig, Path workingDirectory) {
        List<AnalysisIssue> issues = new ArrayList<>();
        Process process = null;

        try {
            List<String> command = new ArrayList<>();
            command.add("ruff");
            command.add("check");
            command.add("--output-format");
            command.add("json");
            if (useProjectConfig) {
                command.add("--extend-select");
                command.add(SECURITY_FLOOR_SELECT);
            } else {
                command.add("--select");
                command.add(DEFAULT_SELECT);
            }
            command.add(actualPath);

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            if (workingDirectory != null) {
                pb.directory(workingDirectory.toFile());
            }

            process = pb.start();
            String output;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                output = reader.lines().collect(Collectors.joining("\n"));
            }

            boolean finished = process.waitFor(10, java.util.concurrent.TimeUnit.MINUTES);
            if (!finished) {
                log.warn("Ruff timed out after 10 minutes for {}", reportedPath);
                process.destroyForcibly();
                return issues;
            }
            int exitCode = process.exitValue();

            if (!output.isEmpty() && output.startsWith("[")) {
                JsonNode results = objectMapper.readTree(output);
                for (JsonNode result : results) {
                    issues.add(parseResult(result, reportedPath));
                }
            } else if (exitCode != 0 && !output.isEmpty()) {
                // Ruff exits non-zero by design when it finds lint issues, so only treat
                // non-JSON output as an error condition (e.g. malformed config).
                String truncated = output.length() > 500 ? output.substring(0, 500) + "..." : output;
                log.warn("Ruff exited {} with non-JSON output for {}: {}", exitCode, reportedPath, truncated);
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

        JsonNode location = result.get("location");
        int line = location != null && location.has("row") ? location.get("row").asInt() : 0;
        int column = location != null && location.has("column") ? location.get("column").asInt() : 0;

        JsonNode endLocation = result.get("end_location");
        int endLine = endLocation != null && endLocation.has("row") ? endLocation.get("row").asInt() : line;
        int endColumn = endLocation != null && endLocation.has("column") ? endLocation.get("column").asInt() : column;

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
        if (code.startsWith("S")) {
            return AnalysisIssue.Severity.HIGH;
        }
        if (code.startsWith("E") || code.startsWith("F")) {
            return AnalysisIssue.Severity.MEDIUM;
        }
        if (code.startsWith("B")) {
            return AnalysisIssue.Severity.MEDIUM;
        }
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
