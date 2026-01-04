package com.codelens.analysis;

import com.codelens.analysis.java.CheckstyleAnalyzer;
import com.codelens.analysis.java.PmdAnalyzer;
import com.codelens.analysis.java.SpotBugsAnalyzer;
import com.codelens.analysis.javascript.EslintAnalyzer;
import com.codelens.analysis.javascript.EslintAnalyzer.EslintConfig;
import com.codelens.analysis.javascript.NpmAuditAnalyzer;
import com.codelens.analysis.python.RuffAnalyzer;
import com.codelens.analysis.python.BanditAnalyzer;
import com.codelens.analysis.python.PipAuditAnalyzer;
import com.codelens.analysis.go.StaticcheckAnalyzer;
import com.codelens.analysis.go.GosecAnalyzer;
import com.codelens.analysis.rust.ClippyAnalyzer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * Orchestrates multiple static analyzers to provide comprehensive code analysis.
 */
@Slf4j
@Service
public class CombinedAnalysisService {

    private final AnalysisLanguageDetector languageDetector;
    // Java analyzers
    private final PmdAnalyzer pmdAnalyzer;
    private final SpotBugsAnalyzer spotBugsAnalyzer;
    private final CheckstyleAnalyzer checkstyleAnalyzer;
    // JavaScript/TypeScript analyzers
    private final EslintAnalyzer eslintAnalyzer;
    private final NpmAuditAnalyzer npmAuditAnalyzer;
    // Python analyzers
    private final RuffAnalyzer ruffAnalyzer;
    private final BanditAnalyzer banditAnalyzer;
    private final PipAuditAnalyzer pipAuditAnalyzer;
    // Go analyzers
    private final StaticcheckAnalyzer staticcheckAnalyzer;
    private final GosecAnalyzer gosecAnalyzer;
    // Rust analyzers
    private final ClippyAnalyzer clippyAnalyzer;
    // Custom rules analyzer
    private final CustomRuleAnalyzer customRuleAnalyzer;

    @Value("${codelens.analysis.parallel:true}")
    private boolean runInParallel;

    @Value("${codelens.analysis.timeout-seconds:60}")
    private int timeoutSeconds;

    private final ExecutorService executor = Executors.newFixedThreadPool(4);

    /**
     * Clean up temp resources after analysis session.
     * Call this after all files are analyzed to clean up EslintAnalyzer temp directories.
     */
    public void cleanupAnalysisSession() {
        if (eslintAnalyzer != null) {
            eslintAnalyzer.cleanupSession();
        }
    }

    // ========== DEPRECATED ThreadLocal methods (removed due to async incompatibility) ==========

    /**
     * @deprecated ThreadLocal doesn't work with async execution. Pass config as parameter instead.
     * @see #analyzeFile(String, String, UUID, EslintConfig)
     */
    @Deprecated
    public void setEslintConfig(EslintConfig config) {
        // No-op - config should be passed as parameter
        log.warn("setEslintConfig() is deprecated. Pass config as parameter to analyzeFile() instead.");
    }

    /**
     * @deprecated Use cleanupAnalysisSession() instead.
     */
    @Deprecated
    public void clearEslintConfig() {
        cleanupAnalysisSession();
    }

    public CombinedAnalysisService(
            AnalysisLanguageDetector languageDetector,
            PmdAnalyzer pmdAnalyzer,
            SpotBugsAnalyzer spotBugsAnalyzer,
            CheckstyleAnalyzer checkstyleAnalyzer,
            EslintAnalyzer eslintAnalyzer,
            NpmAuditAnalyzer npmAuditAnalyzer,
            RuffAnalyzer ruffAnalyzer,
            BanditAnalyzer banditAnalyzer,
            PipAuditAnalyzer pipAuditAnalyzer,
            StaticcheckAnalyzer staticcheckAnalyzer,
            GosecAnalyzer gosecAnalyzer,
            ClippyAnalyzer clippyAnalyzer,
            CustomRuleAnalyzer customRuleAnalyzer) {
        this.languageDetector = languageDetector;
        this.pmdAnalyzer = pmdAnalyzer;
        this.spotBugsAnalyzer = spotBugsAnalyzer;
        this.checkstyleAnalyzer = checkstyleAnalyzer;
        this.eslintAnalyzer = eslintAnalyzer;
        this.npmAuditAnalyzer = npmAuditAnalyzer;
        this.ruffAnalyzer = ruffAnalyzer;
        this.banditAnalyzer = banditAnalyzer;
        this.pipAuditAnalyzer = pipAuditAnalyzer;
        this.staticcheckAnalyzer = staticcheckAnalyzer;
        this.gosecAnalyzer = gosecAnalyzer;
        this.clippyAnalyzer = clippyAnalyzer;
        this.customRuleAnalyzer = customRuleAnalyzer;
    }

    /**
     * Analyze a single file (without custom rules or ESLint config)
     */
    public List<AnalysisIssue> analyzeFile(String filename, String content) {
        return analyzeFile(filename, content, null, null);
    }

    /**
     * Analyze a single file with custom rules for an organization
     *
     * @param filename       The file path/name
     * @param content        The file content
     * @param organizationId The organization ID for custom rules (can be null)
     */
    public List<AnalysisIssue> analyzeFile(String filename, String content, UUID organizationId) {
        return analyzeFile(filename, content, organizationId, null);
    }

    /**
     * Analyze a single file with custom rules and ESLint config.
     * This method is safe to call from async contexts (thread pools).
     *
     * @param filename       The file path/name
     * @param content        The file content
     * @param organizationId The organization ID for custom rules (can be null)
     * @param eslintConfig   The ESLint config from the repo (can be null)
     */
    public List<AnalysisIssue> analyzeFile(String filename, String content, UUID organizationId, EslintConfig eslintConfig) {
        List<AnalysisIssue> allIssues = new ArrayList<>();

        if (!languageDetector.shouldAnalyze(filename)) {
            log.debug("Skipping analysis for unsupported file: {}", filename);
            return allIssues;
        }

        AnalysisLanguageDetector.Language language = languageDetector.detect(filename);
        log.debug("Analyzing {} as {}", filename, language);

        if (runInParallel) {
            allIssues = analyzeInParallel(filename, content, language, eslintConfig);
        } else {
            allIssues = analyzeSequentially(filename, content, language, eslintConfig);
        }

        // Apply custom rules if available
        if (customRuleAnalyzer != null && customRuleAnalyzer.isAvailable()) {
            List<AnalysisIssue> customIssues = customRuleAnalyzer.analyze(filename, content, organizationId);
            allIssues.addAll(customIssues);
        }

        log.info("Found {} issues in {}", allIssues.size(), filename);
        return allIssues;
    }

    /**
     * Analyze multiple files
     */
    public List<AnalysisIssue> analyzeFiles(List<FileContent> files) {
        if (runInParallel) {
            List<CompletableFuture<List<AnalysisIssue>>> futures = files.stream()
                .map(file -> CompletableFuture.supplyAsync(
                    () -> analyzeFile(file.filename(), file.content()),
                    executor
                ))
                .toList();

            return futures.stream()
                .map(CompletableFuture::join)
                .flatMap(List::stream)
                .collect(Collectors.toList());
        } else {
            return files.stream()
                .flatMap(file -> analyzeFile(file.filename(), file.content()).stream())
                .collect(Collectors.toList());
        }
    }

    private List<AnalysisIssue> analyzeInParallel(String filename, String content,
            AnalysisLanguageDetector.Language language, EslintConfig eslintConfig) {
        List<CompletableFuture<List<AnalysisIssue>>> futures = new ArrayList<>();

        // Java analyzers
        if (language == AnalysisLanguageDetector.Language.JAVA || language == AnalysisLanguageDetector.Language.KOTLIN) {
            if (pmdAnalyzer.isAvailable()) {
                futures.add(CompletableFuture.supplyAsync(
                    () -> pmdAnalyzer.analyze(filename, content), executor
                ));
            }
            if (spotBugsAnalyzer.isAvailable()) {
                futures.add(CompletableFuture.supplyAsync(
                    () -> spotBugsAnalyzer.analyze(filename, content), executor
                ));
            }
            if (checkstyleAnalyzer.isAvailable()) {
                futures.add(CompletableFuture.supplyAsync(
                    () -> checkstyleAnalyzer.analyze(filename, content), executor
                ));
            }
        }

        // JavaScript/TypeScript analyzers
        if (languageDetector.isJavaScriptFamily(filename)) {
            if (eslintAnalyzer.isAvailable()) {
                // Use explicitly passed config (safe for async execution)
                futures.add(CompletableFuture.supplyAsync(() -> {
                    if (eslintConfig != null) {
                        return eslintAnalyzer.analyzeWithConfig(
                            filename, content, eslintConfig.configFilename(), eslintConfig.configContent());
                    } else {
                        return eslintAnalyzer.analyze(filename, content);
                    }
                }, executor));
            }
        }

        // Python analyzers
        if (language == AnalysisLanguageDetector.Language.PYTHON) {
            if (ruffAnalyzer.isAvailable()) {
                futures.add(CompletableFuture.supplyAsync(
                    () -> ruffAnalyzer.analyze(filename, content), executor
                ));
            }
            if (banditAnalyzer.isAvailable()) {
                futures.add(CompletableFuture.supplyAsync(
                    () -> banditAnalyzer.analyze(filename, content), executor
                ));
            }
        }

        // Go analyzers
        if (language == AnalysisLanguageDetector.Language.GO) {
            if (staticcheckAnalyzer.isAvailable()) {
                futures.add(CompletableFuture.supplyAsync(
                    () -> staticcheckAnalyzer.analyze(filename, content), executor
                ));
            }
            if (gosecAnalyzer.isAvailable()) {
                futures.add(CompletableFuture.supplyAsync(
                    () -> gosecAnalyzer.analyze(filename, content), executor
                ));
            }
        }

        // Rust analyzers
        if (language == AnalysisLanguageDetector.Language.RUST) {
            if (clippyAnalyzer.isAvailable()) {
                futures.add(CompletableFuture.supplyAsync(
                    () -> clippyAnalyzer.analyze(filename, content), executor
                ));
            }
        }

        return futures.stream()
            .map(CompletableFuture::join)
            .flatMap(List::stream)
            .collect(Collectors.toList());
    }

    private List<AnalysisIssue> analyzeSequentially(String filename, String content,
            AnalysisLanguageDetector.Language language, EslintConfig eslintConfig) {
        List<AnalysisIssue> allIssues = new ArrayList<>();

        // Java analyzers
        if (language == AnalysisLanguageDetector.Language.JAVA || language == AnalysisLanguageDetector.Language.KOTLIN) {
            if (pmdAnalyzer.isAvailable()) {
                allIssues.addAll(pmdAnalyzer.analyze(filename, content));
            }
            if (spotBugsAnalyzer.isAvailable()) {
                allIssues.addAll(spotBugsAnalyzer.analyze(filename, content));
            }
            if (checkstyleAnalyzer.isAvailable()) {
                allIssues.addAll(checkstyleAnalyzer.analyze(filename, content));
            }
        }

        // JavaScript/TypeScript analyzers
        if (languageDetector.isJavaScriptFamily(filename)) {
            if (eslintAnalyzer.isAvailable()) {
                // Use explicitly passed config (safe for async execution)
                if (eslintConfig != null) {
                    allIssues.addAll(eslintAnalyzer.analyzeWithConfig(
                        filename, content, eslintConfig.configFilename(), eslintConfig.configContent()));
                } else {
                    allIssues.addAll(eslintAnalyzer.analyze(filename, content));
                }
            }
        }

        // Python analyzers
        if (language == AnalysisLanguageDetector.Language.PYTHON) {
            if (ruffAnalyzer.isAvailable()) {
                allIssues.addAll(ruffAnalyzer.analyze(filename, content));
            }
            if (banditAnalyzer.isAvailable()) {
                allIssues.addAll(banditAnalyzer.analyze(filename, content));
            }
        }

        // Go analyzers
        if (language == AnalysisLanguageDetector.Language.GO) {
            if (staticcheckAnalyzer.isAvailable()) {
                allIssues.addAll(staticcheckAnalyzer.analyze(filename, content));
            }
            if (gosecAnalyzer.isAvailable()) {
                allIssues.addAll(gosecAnalyzer.analyze(filename, content));
            }
        }

        // Rust analyzers
        if (language == AnalysisLanguageDetector.Language.RUST) {
            if (clippyAnalyzer.isAvailable()) {
                allIssues.addAll(clippyAnalyzer.analyze(filename, content));
            }
        }

        return allIssues;
    }

    /**
     * Run dependency vulnerability scans on a project directory
     */
    public List<AnalysisIssue> scanDependencies(String projectDir, boolean hasPackageJson,
                                                  boolean hasRequirementsTxt, boolean hasPyprojectToml) {
        List<AnalysisIssue> issues = new ArrayList<>();

        // JavaScript/TypeScript dependencies
        if (hasPackageJson && npmAuditAnalyzer.isAvailable()) {
            log.info("Running npm audit on {}", projectDir);
            issues.addAll(npmAuditAnalyzer.analyzeDirectory(projectDir));
        }

        // Python dependencies
        if ((hasRequirementsTxt || hasPyprojectToml) && pipAuditAnalyzer.isAvailable()) {
            log.info("Running pip-audit on {}", projectDir);
            issues.addAll(pipAuditAnalyzer.analyzeDirectory(projectDir));
        }

        // Could add Maven/Gradle dependency check here for Java projects

        return issues;
    }

    /**
     * Convenience overload for backward compatibility
     */
    public List<AnalysisIssue> scanDependencies(String projectDir, boolean hasPackageJson) {
        return scanDependencies(projectDir, hasPackageJson, false, false);
    }

    /**
     * Get list of available analyzers
     */
    public List<AnalyzerInfo> getAvailableAnalyzers() {
        List<AnalyzerInfo> analyzers = new ArrayList<>();

        // Java analyzers
        analyzers.add(new AnalyzerInfo("pmd", "PMD", "Java", pmdAnalyzer.isAvailable()));
        analyzers.add(new AnalyzerInfo("spotbugs", "SpotBugs", "Java", spotBugsAnalyzer.isAvailable()));
        analyzers.add(new AnalyzerInfo("checkstyle", "Checkstyle", "Java", checkstyleAnalyzer.isAvailable()));

        // JavaScript/TypeScript analyzers
        analyzers.add(new AnalyzerInfo("eslint", "ESLint", "JavaScript/TypeScript", eslintAnalyzer.isAvailable()));
        analyzers.add(new AnalyzerInfo("npm-audit", "NPM Audit", "JavaScript/TypeScript", npmAuditAnalyzer.isAvailable()));

        // Python analyzers
        analyzers.add(new AnalyzerInfo("ruff", "Ruff", "Python", ruffAnalyzer.isAvailable()));
        analyzers.add(new AnalyzerInfo("bandit", "Bandit", "Python", banditAnalyzer.isAvailable()));
        analyzers.add(new AnalyzerInfo("pip-audit", "pip-audit", "Python", pipAuditAnalyzer.isAvailable()));

        // Go analyzers
        analyzers.add(new AnalyzerInfo("staticcheck", "Staticcheck", "Go", staticcheckAnalyzer.isAvailable()));
        analyzers.add(new AnalyzerInfo("gosec", "Gosec", "Go", gosecAnalyzer.isAvailable()));

        // Rust analyzers
        analyzers.add(new AnalyzerInfo("clippy", "Clippy", "Rust", clippyAnalyzer.isAvailable()));

        return analyzers;
    }

    /**
     * Filter issues by severity threshold
     */
    public List<AnalysisIssue> filterBySeverity(List<AnalysisIssue> issues, AnalysisIssue.Severity minSeverity) {
        int minOrdinal = minSeverity.ordinal();
        return issues.stream()
            .filter(issue -> issue.severity().ordinal() <= minOrdinal)
            .collect(Collectors.toList());
    }

    /**
     * Filter issues to only include those on changed lines.
     * This ensures static analysis only reports issues in the diff, not in unchanged code.
     *
     * @param issues       The list of issues from static analysis
     * @param changedLines Set of line numbers that were changed (added/modified)
     * @return Filtered list containing only issues on changed lines
     */
    public List<AnalysisIssue> filterToChangedLines(List<AnalysisIssue> issues, java.util.Set<Integer> changedLines) {
        if (changedLines == null || changedLines.isEmpty()) {
            return issues; // If no diff info, return all issues
        }
        return issues.stream()
            .filter(issue -> changedLines.contains(issue.line()))
            .collect(Collectors.toList());
    }

    /**
     * Group issues by file
     */
    public java.util.Map<String, List<AnalysisIssue>> groupByFile(List<AnalysisIssue> issues) {
        return issues.stream()
            .collect(Collectors.groupingBy(AnalysisIssue::filePath));
    }

    /**
     * Group issues by analyzer
     */
    public java.util.Map<String, List<AnalysisIssue>> groupByAnalyzer(List<AnalysisIssue> issues) {
        return issues.stream()
            .collect(Collectors.groupingBy(AnalysisIssue::analyzer));
    }

    // DTOs

    public record FileContent(String filename, String content) {}

    public record AnalyzerInfo(String id, String name, String languages, boolean available) {}
}
