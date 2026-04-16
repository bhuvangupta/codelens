package com.codelens.analysis.javascript;

import com.codelens.analysis.AnalysisIssue;
import com.codelens.analysis.StaticAnalyzer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Component
public class BiomeAnalyzer implements StaticAnalyzer {

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("js", "jsx", "ts", "tsx", "mjs", "cjs");
    private static final List<String> BIOME_CONFIG_FILES = List.of("biome.json", "biome.jsonc");
    private static final String SECURITY_FLOOR_RESOURCE = "security-floor/biome-security-floor.json";
    private static final long PROCESS_TIMEOUT_MINUTES = 10;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public record BiomeConfig(String configFilename, String configContent) {}

    public static List<String> getConfigFileNames() { return BIOME_CONFIG_FILES; }

    @Override public String getName() { return "biome"; }
    @Override public Set<String> getSupportedExtensions() { return SUPPORTED_EXTENSIONS; }

    @Override
    public List<AnalysisIssue> analyze(String filename, String content) {
        return analyzeWithConfig(filename, content, null);
    }

    public List<AnalysisIssue> analyzeWithConfig(String filename, String content, BiomeConfig config) {
        List<AnalysisIssue> issues = new ArrayList<>();
        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("biome-session-");
            String extension = getExtension(filename);
            Path sourceFile = tempDir.resolve("source." + extension);
            Files.writeString(sourceFile, content);

            if (config != null && config.configContent() != null && !config.configContent().isBlank()) {
                // Pass 1: project config
                try {
                    Path configPath = tempDir.resolve(config.configFilename());
                    Files.writeString(configPath, config.configContent());
                    issues.addAll(runBiome(sourceFile, filename, tempDir));
                    log.info("Biome: Using project config from {}", config.configFilename());
                } catch (Exception e) {
                    log.warn("Biome project config {} failed, falling back to default: {}",
                        config.configFilename(), e.getMessage());
                }
                // Pass 2: security floor — always runs
                try {
                    Path floorDir = Files.createTempDirectory("biome-floor-");
                    Path floorConfig = floorDir.resolve("biome.json");
                    Files.writeString(floorConfig, loadSecurityFloor());
                    Path floorSource = floorDir.resolve("source." + extension);
                    Files.writeString(floorSource, content);
                    try {
                        issues.addAll(runBiome(floorSource, filename, floorDir));
                    } finally {
                        deleteRecursively(floorDir);
                    }
                } catch (Exception e) {
                    log.warn("Biome security floor failed: {}", e.getMessage());
                }
                issues = deduplicateIssues(issues);
            } else {
                // No project config — use security floor only (as default)
                String floor = loadSecurityFloor();
                if (floor != null) {
                    Path configPath = tempDir.resolve("biome.json");
                    Files.writeString(configPath, floor);
                }
                try {
                    issues.addAll(runBiome(sourceFile, filename, tempDir));
                    log.debug("Biome: Using bundled CodeLens default");
                } catch (Exception e) {
                    log.warn("Biome default analysis failed: {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("Biome analysis failed for {}: {}", filename, e.getMessage());
        } finally {
            if (tempDir != null) {
                deleteRecursively(tempDir);
            }
        }
        return issues;
    }

    @Override
    public List<AnalysisIssue> analyzeFile(String filePath) {
        return runBiomeFromPath(filePath, filePath);
    }

    private List<AnalysisIssue> runBiomeFromPath(String actualPath, String reportedPath) {
        return runBiome(Path.of(actualPath), reportedPath, null);
    }

    private List<AnalysisIssue> runBiome(Path sourceFile, String reportedPath, Path workingDirectory) {
        List<AnalysisIssue> issues = new ArrayList<>();
        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(
                "npx", "@biomejs/biome", "check",
                "--reporter=json",
                "--formatter-enabled=false",
                sourceFile.toString()
            );
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
            boolean finished = process.waitFor(PROCESS_TIMEOUT_MINUTES, TimeUnit.MINUTES);
            if (!finished) {
                log.warn("Biome timed out after {} minutes for {}", PROCESS_TIMEOUT_MINUTES, reportedPath);
                process.destroyForcibly();
                return issues;
            }
            // Parse JSON diagnostics
            JsonNode root = objectMapper.readTree(output);
            JsonNode diagnostics = root.path("diagnostics");
            if (diagnostics.isArray()) {
                for (JsonNode diag : diagnostics) {
                    issues.add(parseDiagnostic(diag, reportedPath));
                }
            }
        } catch (IOException e) {
            log.warn("Biome I/O error: {}", e.getMessage());
        } catch (InterruptedException e) {
            log.warn("Biome execution interrupted: {}", e.getMessage());
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("Biome output parse error: {}", e.getMessage());
        } finally {
            if (process != null) {
                process.destroyForcibly();
            }
        }
        return issues;
    }

    private AnalysisIssue parseDiagnostic(JsonNode diag, String filePath) {
        String category = diag.path("category").asText("unknown");
        String severity = diag.path("severity").asText("warning");
        String message = diag.path("description").asText("");

        int line = 1, column = 1;
        JsonNode locationSpan = diag.path("location").path("span");
        if (locationSpan.isArray() && locationSpan.size() > 0) {
            // Biome gives byte offsets; default to line 1 when we can't map — the source
            // resolver in the LLM review prompt will surface the message with the file path.
            line = diag.path("location").path("sourceCode").path("line").asInt(1);
        } else if (locationSpan.has("start")) {
            line = locationSpan.path("start").asInt(1);
        }

        return AnalysisIssue.builder()
            .analyzer(getName())
            .ruleId(category)
            .severity(mapSeverity(severity, category))
            .category(categorizeRule(category))
            .message(message)
            .filePath(filePath)
            .line(line)
            .column(column)
            .build();
    }

    private AnalysisIssue.Severity mapSeverity(String severity, String category) {
        if (category.startsWith("lint/security/")) return AnalysisIssue.Severity.HIGH;
        return switch (severity.toLowerCase()) {
            case "error" -> AnalysisIssue.Severity.HIGH;
            case "warning" -> AnalysisIssue.Severity.MEDIUM;
            default -> AnalysisIssue.Severity.LOW;
        };
    }

    private String categorizeRule(String category) {
        if (category.startsWith("lint/security/")) return "Security";
        if (category.startsWith("lint/suspicious/")) return "Bug";
        if (category.startsWith("lint/correctness/")) return "Correctness";
        if (category.startsWith("lint/performance/")) return "Performance";
        if (category.startsWith("lint/complexity/")) return "Complexity";
        if (category.startsWith("lint/style/")) return "Style";
        return "Code Quality";
    }

    private String loadSecurityFloor() {
        try (InputStream is = new ClassPathResource(SECURITY_FLOOR_RESOURCE).getInputStream()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Failed to load Biome security floor: {}", e.getMessage());
            return null;
        }
    }

    private List<AnalysisIssue> deduplicateIssues(List<AnalysisIssue> issues) {
        Set<String> seen = new HashSet<>();
        List<AnalysisIssue> deduped = new ArrayList<>();
        for (AnalysisIssue issue : issues) {
            String key = issue.filePath() + ":" + issue.line() + ":" + issue.ruleId();
            if (seen.add(key)) deduped.add(issue);
        }
        return deduped;
    }

    private void deleteRecursively(Path dir) {
        try {
            Files.walk(dir)
                .sorted((a, b) -> b.compareTo(a))
                .forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                });
        } catch (IOException e) {
            log.debug("Failed to clean up Biome temp dir: {}", dir);
        }
    }

    private String getExtension(String filename) {
        int lastDot = filename.lastIndexOf('.');
        return lastDot > 0 ? filename.substring(lastDot + 1) : "ts";
    }

    @Override
    public boolean isAvailable() {
        try {
            ProcessBuilder pb = new ProcessBuilder("npx", "@biomejs/biome", "--version");
            Process process = pb.start();
            return process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
