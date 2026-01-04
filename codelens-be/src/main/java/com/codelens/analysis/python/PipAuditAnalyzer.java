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
 * pip-audit analyzer for Python dependency vulnerabilities.
 * Scans Python dependencies for known security vulnerabilities.
 */
@Slf4j
@Component
public class PipAuditAnalyzer implements StaticAnalyzer {

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("txt", "toml");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String getName() {
        return "pip-audit";
    }

    @Override
    public Set<String> getSupportedExtensions() {
        return SUPPORTED_EXTENSIONS;
    }

    @Override
    public List<AnalysisIssue> analyze(String filename, String content) {
        // pip-audit works on requirements files or directories, not individual files
        return List.of();
    }

    @Override
    public List<AnalysisIssue> analyzeFile(String filePath) {
        // Only analyze requirements.txt or pyproject.toml
        if (!filePath.endsWith("requirements.txt") && !filePath.endsWith("pyproject.toml")) {
            return List.of();
        }
        return runPipAudit(filePath);
    }

    /**
     * Analyze a directory for Python dependency vulnerabilities
     */
    public List<AnalysisIssue> analyzeDirectory(String projectDir) {
        List<AnalysisIssue> issues = new ArrayList<>();

        // Check for requirements.txt
        Path requirementsTxt = Path.of(projectDir, "requirements.txt");
        if (Files.exists(requirementsTxt)) {
            issues.addAll(runPipAuditWithRequirements(requirementsTxt.toString()));
        }

        // Check for pyproject.toml
        Path pyprojectToml = Path.of(projectDir, "pyproject.toml");
        if (Files.exists(pyprojectToml)) {
            issues.addAll(runPipAuditWithPyproject(projectDir));
        }

        return issues;
    }

    private List<AnalysisIssue> runPipAudit(String filePath) {
        if (filePath.endsWith("requirements.txt")) {
            return runPipAuditWithRequirements(filePath);
        } else if (filePath.endsWith("pyproject.toml")) {
            return runPipAuditWithPyproject(Path.of(filePath).getParent().toString());
        }
        return List.of();
    }

    private List<AnalysisIssue> runPipAuditWithRequirements(String requirementsPath) {
        List<AnalysisIssue> issues = new ArrayList<>();
        Process process = null;

        try {
            ProcessBuilder pb = new ProcessBuilder(
                "pip-audit",
                "-r", requirementsPath,
                "-f", "json",
                "--progress-spinner", "off"
            );
            pb.redirectErrorStream(true);

            process = pb.start();
            String output;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                output = reader.lines().collect(Collectors.joining("\n"));
            }

            process.waitFor();
            issues = parseOutput(output, requirementsPath);
        } catch (IOException e) {
            log.warn("pip-audit I/O error: {}", e.getMessage());
        } catch (InterruptedException e) {
            log.warn("pip-audit interrupted: {}", e.getMessage());
            Thread.currentThread().interrupt();
        } finally {
            if (process != null) {
                process.destroyForcibly();
            }
        }

        return issues;
    }

    private List<AnalysisIssue> runPipAuditWithPyproject(String projectDir) {
        List<AnalysisIssue> issues = new ArrayList<>();
        Process process = null;

        try {
            ProcessBuilder pb = new ProcessBuilder(
                "pip-audit",
                "-f", "json",
                "--progress-spinner", "off"
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
            issues = parseOutput(output, projectDir + "/pyproject.toml");
        } catch (IOException e) {
            log.warn("pip-audit I/O error: {}", e.getMessage());
        } catch (InterruptedException e) {
            log.warn("pip-audit interrupted: {}", e.getMessage());
            Thread.currentThread().interrupt();
        } finally {
            if (process != null) {
                process.destroyForcibly();
            }
        }

        return issues;
    }

    private List<AnalysisIssue> parseOutput(String output, String filePath) {
        List<AnalysisIssue> issues = new ArrayList<>();

        try {
            if (!output.isEmpty() && output.contains("\"dependencies\"")) {
                JsonNode root = objectMapper.readTree(output);
                JsonNode dependencies = root.get("dependencies");

                if (dependencies != null && dependencies.isArray()) {
                    for (JsonNode dep : dependencies) {
                        JsonNode vulns = dep.get("vulns");
                        if (vulns != null && vulns.isArray() && !vulns.isEmpty()) {
                            String name = dep.has("name") ? dep.get("name").asText() : "unknown";
                            String version = dep.has("version") ? dep.get("version").asText() : "";

                            for (JsonNode vuln : vulns) {
                                issues.add(parseVulnerability(vuln, name, version, filePath));
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse pip-audit output: {}", e.getMessage());
        }

        return issues;
    }

    private AnalysisIssue parseVulnerability(JsonNode vuln, String packageName, String version, String filePath) {
        String vulnId = vuln.has("id") ? vuln.get("id").asText() : "unknown";
        String description = vuln.has("description") ? vuln.get("description").asText() : "";
        String fixVersions = "";

        if (vuln.has("fix_versions") && vuln.get("fix_versions").isArray()) {
            List<String> fixes = new ArrayList<>();
            for (JsonNode fix : vuln.get("fix_versions")) {
                fixes.add(fix.asText());
            }
            fixVersions = String.join(", ", fixes);
        }

        String message = String.format("Vulnerability in %s==%s: %s", packageName, version, description);
        String suggestion = fixVersions.isEmpty() ? null :
            String.format("Upgrade to version: %s", fixVersions);

        // Determine severity from vulnerability ID patterns
        AnalysisIssue.Severity severity = AnalysisIssue.Severity.HIGH;
        if (vulnId.startsWith("GHSA-") || vulnId.startsWith("CVE-")) {
            severity = AnalysisIssue.Severity.CRITICAL;
        }

        return AnalysisIssue.builder()
            .analyzer(getName())
            .ruleId(vulnId)
            .severity(severity)
            .category("Vulnerability")
            .message(message)
            .filePath(filePath)
            .line(0)
            .column(0)
            .suggestion(suggestion)
            .build();
    }

    @Override
    public boolean isAvailable() {
        try {
            ProcessBuilder pb = new ProcessBuilder("pip-audit", "--version");
            Process process = pb.start();
            return process.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
