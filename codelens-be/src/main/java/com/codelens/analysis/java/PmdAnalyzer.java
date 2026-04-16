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
     * Analyze with optional project ruleset. If config is non-null, loads BOTH
     * the project's ruleset and the security floor. If null, loads only the
     * bundled CodeLens default ruleset.
     */
    public List<AnalysisIssue> analyzeWithConfig(String filename, String content, PmdConfig config) {
        List<AnalysisIssue> issues = new ArrayList<>();
        Path tempRulesetFile = null;

        try {
            PMDConfiguration pmdConfig = new PMDConfiguration();
            pmdConfig.setDefaultLanguageVersion(
                LanguageRegistry.PMD.getLanguageById("java").getDefaultVersion()
            );

            if (config != null && config.configContent() != null && !config.configContent().isBlank()) {
                tempRulesetFile = Files.createTempFile("pmd-ruleset-", ".xml");
                Files.writeString(tempRulesetFile, config.configContent());
                pmdConfig.addRuleSet(tempRulesetFile.toString());
                pmdConfig.addRuleSet(SECURITY_FLOOR_RULESET);
                log.info("PMD: Using project ruleset from {} + security floor", config.configFilename());
            } else {
                pmdConfig.addRuleSet(DEFAULT_RULESET);
                log.debug("PMD: Using bundled CodeLens default ruleset");
            }

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
