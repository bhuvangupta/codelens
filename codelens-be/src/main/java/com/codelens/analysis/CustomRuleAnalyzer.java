package com.codelens.analysis;

import com.codelens.model.entity.ReviewRule;
import com.codelens.service.RulesService;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Analyzer that applies custom user-defined regex rules to code.
 * Includes protection against ReDoS (Regular Expression Denial of Service) attacks.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CustomRuleAnalyzer {

    private final RulesService rulesService;

    // ReDoS protection settings
    private static final int REGEX_TIMEOUT_MS = 100; // Max time per line match
    private static final int MAX_LINE_LENGTH = 2000; // Skip very long lines
    private static final Pattern DANGEROUS_PATTERN = Pattern.compile(
        // Detect nested quantifiers that can cause catastrophic backtracking
        "\\([^)]*[+*][^)]*\\)[+*]|" +  // (a+)+ or (a*)*
        "\\([^)]*[+*][^)]*\\)\\{|" +   // (a+){n}
        "\\([^)]*\\|[^)]*\\)[+*]"      // (a|b)+  with overlapping alternatives
    );

    private final ExecutorService regexExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "regex-matcher");
        t.setDaemon(true);
        return t;
    });

    /**
     * Analyze a file against custom rules for an organization.
     *
     * @param filename       The file path/name
     * @param content        The file content
     * @param organizationId The organization ID (can be null for no org rules)
     * @return List of issues found by custom rules
     */
    public List<AnalysisIssue> analyze(String filename, String content, UUID organizationId) {
        List<AnalysisIssue> issues = new ArrayList<>();

        List<ReviewRule> rules = rulesService.getEnabledRulesForOrganization(organizationId);
        if (rules.isEmpty()) {
            return issues;
        }

        String language = detectLanguage(filename);
        String[] lines = content.split("\n");

        for (ReviewRule rule : rules) {
            // Check if rule applies to this language
            if (rule.getLanguages() != null && !rule.getLanguages().isEmpty()) {
                boolean languageMatch = rule.getLanguages().stream()
                        .anyMatch(lang -> lang.equalsIgnoreCase(language));
                if (!languageMatch) {
                    continue;
                }
            }

            try {
                // Validate pattern for dangerous constructs (ReDoS prevention)
                if (isDangerousPattern(rule.getPattern())) {
                    log.warn("Skipping potentially dangerous regex pattern in rule '{}': {}",
                            rule.getName(), rule.getPattern());
                    continue;
                }

                Pattern pattern = Pattern.compile(rule.getPattern(), Pattern.MULTILINE);

                for (int i = 0; i < lines.length; i++) {
                    String line = lines[i];

                    // Skip very long lines to prevent ReDoS
                    if (line.length() > MAX_LINE_LENGTH) {
                        log.trace("Skipping long line {} in {} for custom rules", i + 1, filename);
                        continue;
                    }

                    // Use timeout-protected regex matching
                    MatchResult matchResult = matchWithTimeout(pattern, line);
                    if (matchResult != null && matchResult.found) {
                        issues.add(AnalysisIssue.builder()
                                .analyzer("custom-rules")
                                .ruleId(rule.getName())
                                .severity(mapSeverity(rule.getSeverity()))
                                .category(rule.getCategory())
                                .message(rule.getDescription() != null
                                        ? rule.getDescription()
                                        : "Custom rule violation: " + rule.getName())
                                .filePath(filename)
                                .line(i + 1)
                                .column(matchResult.start + 1)
                                .endLine(i + 1)
                                .endColumn(matchResult.end + 1)
                                .suggestion(rule.getSuggestion())
                                .codeSnippet(line.trim())
                                .build());
                    }
                }
            } catch (PatternSyntaxException e) {
                log.warn("Invalid regex pattern in rule '{}': {}", rule.getName(), e.getMessage());
            } catch (Exception e) {
                log.warn("Error applying custom rule '{}' to file '{}': {}",
                        rule.getName(), filename, e.getMessage());
            }
        }

        if (!issues.isEmpty()) {
            log.debug("Custom rules found {} issues in {}", issues.size(), filename);
        }

        return issues;
    }

    private AnalysisIssue.Severity mapSeverity(ReviewRule.Severity severity) {
        return switch (severity) {
            case CRITICAL -> AnalysisIssue.Severity.CRITICAL;
            case HIGH -> AnalysisIssue.Severity.HIGH;
            case MEDIUM -> AnalysisIssue.Severity.MEDIUM;
            case LOW -> AnalysisIssue.Severity.LOW;
        };
    }

    private String detectLanguage(String filename) {
        String lower = filename.toLowerCase();
        if (lower.endsWith(".java")) return "Java";
        if (lower.endsWith(".kt") || lower.endsWith(".kts")) return "Kotlin";
        if (lower.endsWith(".js") || lower.endsWith(".jsx") || lower.endsWith(".mjs")) return "JavaScript";
        if (lower.endsWith(".ts") || lower.endsWith(".tsx")) return "TypeScript";
        if (lower.endsWith(".py") || lower.endsWith(".pyw")) return "Python";
        if (lower.endsWith(".go")) return "Go";
        if (lower.endsWith(".rs")) return "Rust";
        if (lower.endsWith(".rb")) return "Ruby";
        if (lower.endsWith(".php")) return "PHP";
        if (lower.endsWith(".cs")) return "C#";
        if (lower.endsWith(".cpp") || lower.endsWith(".cc") || lower.endsWith(".cxx")) return "C++";
        if (lower.endsWith(".c") || lower.endsWith(".h")) return "C";
        if (lower.endsWith(".swift")) return "Swift";
        if (lower.endsWith(".scala")) return "Scala";
        return "Unknown";
    }

    public boolean isAvailable() {
        return true; // Always available
    }

    /**
     * Check if a regex pattern contains potentially dangerous constructs
     * that could cause catastrophic backtracking (ReDoS).
     */
    private boolean isDangerousPattern(String pattern) {
        if (pattern == null || pattern.isEmpty()) {
            return false;
        }
        return DANGEROUS_PATTERN.matcher(pattern).find();
    }

    /**
     * Execute regex matching with a timeout to prevent ReDoS attacks.
     * Returns null if matching times out or fails.
     */
    private MatchResult matchWithTimeout(Pattern pattern, String line) {
        Future<MatchResult> future = regexExecutor.submit(() -> {
            Matcher matcher = pattern.matcher(line);
            if (matcher.find()) {
                return new MatchResult(true, matcher.start(), matcher.end());
            }
            return new MatchResult(false, -1, -1);
        });

        try {
            return future.get(REGEX_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("Regex matching timed out after {}ms for pattern: {}",
                    REGEX_TIMEOUT_MS, pattern.pattern());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return null;
        } catch (ExecutionException e) {
            log.warn("Regex matching failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Result of a regex match operation.
     */
    private record MatchResult(boolean found, int start, int end) {}

    /**
     * Shutdown executor service on bean destruction.
     */
    @PreDestroy
    public void shutdown() {
        regexExecutor.shutdown();
        try {
            if (!regexExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                regexExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            regexExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
