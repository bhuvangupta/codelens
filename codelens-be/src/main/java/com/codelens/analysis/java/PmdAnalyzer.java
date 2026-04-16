package com.codelens.analysis.java;

import com.codelens.analysis.AnalysisIssue;
import com.codelens.analysis.StaticAnalyzer;
import lombok.extern.slf4j.Slf4j;
import net.sourceforge.pmd.*;
import net.sourceforge.pmd.lang.LanguageRegistry;
import net.sourceforge.pmd.lang.document.FileId;
import net.sourceforge.pmd.lang.document.TextFile;
import net.sourceforge.pmd.lang.rule.RulePriority;
import net.sourceforge.pmd.reporting.Report;
import net.sourceforge.pmd.reporting.RuleViolation;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Slf4j
@Component
public class PmdAnalyzer implements StaticAnalyzer {

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("java");

    private static final List<String> PMD_CONFIG_FILES = List.of(
        "pmd-ruleset.xml",
        ".pmd-ruleset.xml",
        "config/pmd/ruleset.xml"
    );

    private static final String SECURITY_FLOOR_RULESET = "security-floor/pmd-security-floor.xml";
    private static final String DEFAULT_RULESET = "pmd/codelens-ruleset.xml";

    /**
     * Holds PMD configuration fetched from the repository.
     */
    public record PmdConfig(String configFilename, String configContent) {}

    /**
     * Get list of PMD config file names to search for in a repo.
     */
    public static List<String> getConfigFileNames() {
        return PMD_CONFIG_FILES;
    }

    @Override
    public String getName() {
        return "pmd";
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
     * Detect if a PMD ruleset references external files (rule refs that aren't PMD built-ins).
     * Built-in rule refs start with "category/" (e.g., "category/java/security.xml").
     * Anything else is likely a sibling file in the repo that we can't fetch.
     */
    private static boolean referencesExternalFiles(String configContent) {
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
            "<rule\\s+ref=\"(?!category/|rulesets/)([^\"]+\\.xml)[\"#]"
        );
        return p.matcher(configContent).find();
    }

    /**
     * Analyze with optional project ruleset. If config is non-null, runs TWO separate
     * analysis passes so that a malformed repo ruleset cannot silently disable the
     * security floor:
     *   Pass 1 – project ruleset only (inner try/catch: on failure, falls back to
     *             the bundled DEFAULT_RULESET).
     *   Pass 2 – security floor only (always runs, independent of pass 1).
     * If config is null, runs a single pass with DEFAULT_RULESET.
     */
    public List<AnalysisIssue> analyzeWithConfig(String filename, String content, PmdConfig config) {
        List<AnalysisIssue> issues = new ArrayList<>();
        Path tempRulesetFile = null;

        try {
            if (config != null && config.configContent() != null && !config.configContent().isBlank()) {
                if (referencesExternalFiles(config.configContent())) {
                    log.warn("PMD project ruleset {} references external rulesets in the repo. " +
                             "CodeLens cannot fetch sibling files — falling back to bundled default. " +
                             "To use a project ruleset, inline all <rule ref> references.",
                             config.configFilename());
                    // Skip pass 1 entirely; go straight to default
                    try {
                        PMDConfiguration fallbackConfig = new PMDConfiguration();
                        fallbackConfig.setDefaultLanguageVersion(
                            LanguageRegistry.PMD.getLanguageById("java").getDefaultVersion()
                        );
                        fallbackConfig.addRuleSet(DEFAULT_RULESET);
                        issues.addAll(runPmdAnalysis(fallbackConfig, filename, content));
                    } catch (Exception ex) {
                        log.warn("PMD default ruleset failed: {}", ex.getMessage());
                    }
                } else {
                    tempRulesetFile = Files.createTempFile("pmd-ruleset-", ".xml");
                    Files.writeString(tempRulesetFile, config.configContent());

                    // Pass 1: project ruleset (best-effort; fall back to default on failure)
                    try {
                        PMDConfiguration pmdConfig = new PMDConfiguration();
                        pmdConfig.setDefaultLanguageVersion(
                            LanguageRegistry.PMD.getLanguageById("java").getDefaultVersion()
                        );
                        pmdConfig.addRuleSet(tempRulesetFile.toString());
                        issues.addAll(runPmdAnalysis(pmdConfig, filename, content));
                        log.info("PMD: Using project ruleset from {}", config.configFilename());
                    } catch (Exception e) {
                        log.warn("PMD project ruleset {} failed, falling back to default: {}",
                            config.configFilename(), e.getMessage());
                        try {
                            PMDConfiguration fallbackConfig = new PMDConfiguration();
                            fallbackConfig.setDefaultLanguageVersion(
                                LanguageRegistry.PMD.getLanguageById("java").getDefaultVersion()
                            );
                            fallbackConfig.addRuleSet(DEFAULT_RULESET);
                            issues.addAll(runPmdAnalysis(fallbackConfig, filename, content));
                        } catch (Exception ex) {
                            log.warn("PMD default ruleset also failed: {}", ex.getMessage());
                        }
                    }
                }

                // Pass 2: security floor — always runs independently
                try {
                    PMDConfiguration securityConfig = new PMDConfiguration();
                    securityConfig.setDefaultLanguageVersion(
                        LanguageRegistry.PMD.getLanguageById("java").getDefaultVersion()
                    );
                    securityConfig.addRuleSet(SECURITY_FLOOR_RULESET);
                    issues.addAll(runPmdAnalysis(securityConfig, filename, content));
                    log.debug("PMD: Security floor pass complete");
                } catch (Exception e) {
                    log.warn("PMD security floor pass failed: {}", e.getMessage());
                }
            } else {
                PMDConfiguration pmdConfig = new PMDConfiguration();
                pmdConfig.setDefaultLanguageVersion(
                    LanguageRegistry.PMD.getLanguageById("java").getDefaultVersion()
                );
                pmdConfig.addRuleSet(DEFAULT_RULESET);
                try {
                    issues.addAll(runPmdAnalysis(pmdConfig, filename, content));
                    log.debug("PMD: Using bundled CodeLens default ruleset");
                } catch (Exception e) {
                    log.warn("PMD analysis failed for {}: {}", filename, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("PMD analysis failed for {}: {}", filename, e.getMessage());
        } finally {
            if (tempRulesetFile != null) {
                try {
                    Files.deleteIfExists(tempRulesetFile);
                } catch (IOException e) {
                    log.debug("Failed to delete temp PMD ruleset: {}", tempRulesetFile);
                }
            }
        }

        return issues;
    }

    private List<AnalysisIssue> runPmdAnalysis(PMDConfiguration pmdConfig, String filename, String content)
            throws Exception {
        List<AnalysisIssue> issues = new ArrayList<>();
        try (PmdAnalysis pmd = PmdAnalysis.create(pmdConfig)) {
            TextFile textFile = TextFile.forCharSeq(
                content,
                FileId.fromPathLikeString(filename),
                LanguageRegistry.PMD.getLanguageById("java").getDefaultVersion()
            );
            pmd.files().addFile(textFile);

            Report report = pmd.performAnalysisAndCollectReport();

            for (RuleViolation violation : report.getViolations()) {
                issues.add(AnalysisIssue.builder()
                    .analyzer(getName())
                    .ruleId(violation.getRule().getName())
                    .severity(mapPriority(violation.getRule().getPriority()))
                    .category(violation.getRule().getRuleSetName())
                    .message(violation.getDescription())
                    .filePath(filename)
                    .line(violation.getBeginLine())
                    .column(violation.getBeginColumn())
                    .endLine(violation.getEndLine())
                    .endColumn(violation.getEndColumn())
                    .build());
            }
        }
        return issues;
    }

    @Override
    public List<AnalysisIssue> analyzeFile(String filePath) {
        try {
            String content = Files.readString(Path.of(filePath));
            return analyze(filePath, content);
        } catch (Exception e) {
            log.error("Failed to analyze file: {}", filePath, e);
            return List.of();
        }
    }

    @Override
    public boolean isAvailable() {
        try {
            Class.forName("net.sourceforge.pmd.PMDConfiguration");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private AnalysisIssue.Severity mapPriority(RulePriority priority) {
        return switch (priority) {
            case HIGH -> AnalysisIssue.Severity.HIGH;
            case MEDIUM_HIGH -> AnalysisIssue.Severity.MEDIUM;
            case MEDIUM -> AnalysisIssue.Severity.MEDIUM;
            case MEDIUM_LOW -> AnalysisIssue.Severity.LOW;
            case LOW -> AnalysisIssue.Severity.LOW;
        };
    }
}
