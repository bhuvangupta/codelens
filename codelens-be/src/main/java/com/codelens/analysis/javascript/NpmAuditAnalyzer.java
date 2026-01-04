package com.codelens.analysis.javascript;

import com.codelens.analysis.AnalysisIssue;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * NPM Audit analyzer for detecting CVEs in JavaScript dependencies.
 */
@Slf4j
@Component
public class NpmAuditAnalyzer {

    private final ObjectMapper objectMapper = new ObjectMapper();

    public String getName() {
        return "npm-audit";
    }

    /**
     * Run npm audit on a directory containing package.json
     */
    public List<AnalysisIssue> analyzeDirectory(String projectDir) {
        List<AnalysisIssue> issues = new ArrayList<>();
        Process process = null;

        try {
            ProcessBuilder pb = new ProcessBuilder(
                "npm", "audit", "--json"
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

            // Parse JSON output
            if (!output.isEmpty() && output.startsWith("{")) {
                issues = parseAuditOutput(output, projectDir);
            }
        } catch (IOException e) {
            log.warn("NPM audit I/O error: {}", e.getMessage());
        } catch (InterruptedException e) {
            log.warn("NPM audit interrupted: {}", e.getMessage());
            Thread.currentThread().interrupt();
        } finally {
            if (process != null) {
                process.destroyForcibly();
            }
        }

        return issues;
    }

    private List<AnalysisIssue> parseAuditOutput(String output, String projectDir) {
        List<AnalysisIssue> issues = new ArrayList<>();

        try {
            JsonNode root = objectMapper.readTree(output);

            // NPM 7+ format
            if (root.has("vulnerabilities")) {
                JsonNode vulnerabilities = root.get("vulnerabilities");
                vulnerabilities.fields().forEachRemaining(entry -> {
                    String packageName = entry.getKey();
                    JsonNode vuln = entry.getValue();
                    issues.add(parseVulnerability(packageName, vuln, projectDir));
                });
            }
            // NPM 6 format
            else if (root.has("advisories")) {
                JsonNode advisories = root.get("advisories");
                advisories.fields().forEachRemaining(entry -> {
                    JsonNode advisory = entry.getValue();
                    issues.add(parseAdvisory(advisory, projectDir));
                });
            }
        } catch (Exception e) {
            log.warn("Failed to parse npm audit output: {}", e.getMessage());
        }

        return issues;
    }

    private AnalysisIssue parseVulnerability(String packageName, JsonNode vuln, String projectDir) {
        String severity = vuln.has("severity") ? vuln.get("severity").asText() : "moderate";
        String title = vuln.has("name") ? vuln.get("name").asText() : packageName;

        StringBuilder message = new StringBuilder();
        message.append("Vulnerable package: ").append(packageName);

        if (vuln.has("range")) {
            message.append(" (").append(vuln.get("range").asText()).append(")");
        }

        String cveId = null;
        Double cvssScore = null;

        if (vuln.has("via") && vuln.get("via").isArray()) {
            for (JsonNode via : vuln.get("via")) {
                if (via.isObject()) {
                    if (via.has("title")) {
                        message.append(" - ").append(via.get("title").asText());
                    }
                    if (via.has("cve")) {
                        cveId = via.get("cve").asText();
                    }
                    if (via.has("cvss") && via.get("cvss").has("score")) {
                        cvssScore = via.get("cvss").get("score").asDouble();
                    }
                }
            }
        }

        String suggestion = null;
        if (vuln.has("fixAvailable")) {
            JsonNode fix = vuln.get("fixAvailable");
            if (fix.isBoolean() && fix.asBoolean()) {
                suggestion = "Run 'npm audit fix' to fix this vulnerability";
            } else if (fix.isObject() && fix.has("name")) {
                suggestion = "Update " + fix.get("name").asText() +
                    " to version " + fix.get("version").asText();
            }
        }

        return AnalysisIssue.builder()
            .analyzer(getName())
            .ruleId("npm-vuln-" + packageName)
            .severity(mapSeverity(severity))
            .category("CVE")
            .message(message.toString())
            .filePath(projectDir + "/package.json")
            .line(0)
            .cveId(cveId)
            .cvssScore(cvssScore)
            .suggestion(suggestion)
            .build();
    }

    private AnalysisIssue parseAdvisory(JsonNode advisory, String projectDir) {
        String severity = advisory.has("severity") ? advisory.get("severity").asText() : "moderate";
        String title = advisory.has("title") ? advisory.get("title").asText() : "";
        String module = advisory.has("module_name") ? advisory.get("module_name").asText() : "";

        StringBuilder message = new StringBuilder();
        message.append(title);
        if (!module.isEmpty()) {
            message.append(" in ").append(module);
        }

        String cveId = null;
        if (advisory.has("cves") && advisory.get("cves").isArray() &&
            !advisory.get("cves").isEmpty()) {
            cveId = advisory.get("cves").get(0).asText();
        }

        Double cvssScore = null;
        if (advisory.has("cvss") && advisory.get("cvss").has("score")) {
            cvssScore = advisory.get("cvss").get("score").asDouble();
        }

        String suggestion = null;
        if (advisory.has("recommendation")) {
            suggestion = advisory.get("recommendation").asText();
        } else if (advisory.has("patched_versions")) {
            suggestion = "Update to version: " + advisory.get("patched_versions").asText();
        }

        return AnalysisIssue.builder()
            .analyzer(getName())
            .ruleId("npm-advisory-" + advisory.get("id").asText())
            .severity(mapSeverity(severity))
            .category("CVE")
            .message(message.toString())
            .filePath(projectDir + "/package.json")
            .line(0)
            .cveId(cveId)
            .cvssScore(cvssScore)
            .suggestion(suggestion)
            .build();
    }

    private AnalysisIssue.Severity mapSeverity(String npmSeverity) {
        return switch (npmSeverity.toLowerCase()) {
            case "critical" -> AnalysisIssue.Severity.CRITICAL;
            case "high" -> AnalysisIssue.Severity.HIGH;
            case "moderate" -> AnalysisIssue.Severity.MEDIUM;
            case "low" -> AnalysisIssue.Severity.LOW;
            default -> AnalysisIssue.Severity.INFO;
        };
    }

    public boolean isAvailable() {
        try {
            ProcessBuilder pb = new ProcessBuilder("npm", "--version");
            Process process = pb.start();
            return process.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
