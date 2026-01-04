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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Slf4j
@Component
public class PmdAnalyzer implements StaticAnalyzer {

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("java");

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
        List<AnalysisIssue> issues = new ArrayList<>();

        try {
            PMDConfiguration config = new PMDConfiguration();
            config.setDefaultLanguageVersion(
                LanguageRegistry.PMD.getLanguageById("java").getDefaultVersion()
            );

            // Use custom ruleset that excludes noisy rules (e.g., GuardLogStatement)
            config.addRuleSet("pmd/codelens-ruleset.xml");

            try (PmdAnalysis pmd = PmdAnalysis.create(config)) {
                // Use TextFile for in-memory content analysis
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
