package com.codelens.core;

import com.codelens.analysis.AnalysisIssue;
import com.codelens.analysis.CombinedAnalysisService;
import com.codelens.analysis.javascript.EslintAnalyzer;
import com.codelens.analysis.javascript.EslintAnalyzer.EslintConfig;
import com.codelens.git.GitProvider;
import com.codelens.git.GitProvider.ChangedFile;
import com.codelens.git.GitProvider.PullRequestInfo;
import com.codelens.git.GitProviderFactory;
import com.codelens.llm.LlmProvider;
import com.codelens.llm.LlmRouter;
import com.codelens.model.entity.Review;
import com.codelens.model.entity.ReviewComment;
import com.codelens.model.entity.ReviewIssue;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.regex.Pattern;

@Slf4j
@Component
public class ReviewEngine {

    private final GitProviderFactory gitProviderFactory;
    private final LlmRouter llmRouter;
    private final DiffParser diffParser;
    private final CommentFormatter commentFormatter;
    private final IgnoreCommentParser ignoreCommentParser;
    private final ResourceLoader resourceLoader;
    private final CombinedAnalysisService staticAnalysisService;
    private final LanguageDetector languageDetector;
    private final SmartContextExtractor smartContextExtractor;
    private final SecretRedactor secretRedactor;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Custom review rules file path in repo
    private static final String CUSTOM_RULES_PATH = ".codelens/review-rules.md";
    private static final int MAX_CUSTOM_RULES_CHARS = 5000; // ~1500 tokens limit

    // Note: Custom repo rules and ESLint config are now passed as parameters
    // to avoid ThreadLocal issues with async execution in thread pools.

    // Language to prompt file mapping
    private static final Map<String, String> LANGUAGE_PROMPT_MAP = Map.of(
        "Java", "review-java.txt",
        "Kotlin", "review-java.txt",
        "JavaScript", "review-javascript.txt",
        "TypeScript", "review-javascript.txt",
        "Vue", "review-javascript.txt",
        "Svelte", "review-javascript.txt",
        "Python", "review-python.txt",
        "Go", "review-go.txt",
        "Rust", "review-rust.txt"
    );

    @Value("${codelens.review.max-files:50}")
    private int maxFiles;

    @Value("${codelens.review.max-lines-per-file:1000}")
    private int maxLinesPerFile;

    @Value("${codelens.review.max-diff-lines:3000}")
    private int maxDiffLines;

    @Value("${codelens.review.parallel-threads:5}")
    private int parallelThreads;

    @Value("${codelens.review.skip-tests:true}")
    private boolean skipTests;

    @Value("${codelens.review.skip-generated:true}")
    private boolean skipGenerated;

    // Patterns for files to skip
    private static final List<Pattern> SKIP_PATTERNS = List.of(
        // Test files
        Pattern.compile(".*Test\\.java$"),
        Pattern.compile(".*Tests\\.java$"),
        Pattern.compile(".*Spec\\.java$"),
        Pattern.compile(".*/test/.*"),
        Pattern.compile(".*\\.test\\.(js|ts|jsx|tsx)$"),
        Pattern.compile(".*\\.spec\\.(js|ts|jsx|tsx)$"),
        Pattern.compile(".*/__tests__/.*"),
        // Generated files
        Pattern.compile(".*\\.generated\\.(java|ts|js)$"),
        Pattern.compile(".*/generated/.*"),
        Pattern.compile(".*_pb2?\\.py$"),
        Pattern.compile(".*\\.g\\.dart$"),
        // Config/build files
        Pattern.compile("package-lock\\.json$"),
        Pattern.compile("yarn\\.lock$"),
        Pattern.compile("pnpm-lock\\.yaml$"),
        Pattern.compile("composer\\.lock$"),
        Pattern.compile("Gemfile\\.lock$"),
        Pattern.compile("poetry\\.lock$"),
        // Asset files
        Pattern.compile(".*\\.(png|jpg|jpeg|gif|svg|ico|woff|woff2|ttf|eot)$"),
        Pattern.compile(".*\\.min\\.(js|css)$"),
        // Other
        Pattern.compile(".*\\.d\\.ts$"),
        Pattern.compile(".*\\.map$")
    );

    // High priority patterns (review first)
    private static final List<Pattern> PRIORITY_PATTERNS = List.of(
        Pattern.compile(".*/controller/.*\\.java$", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*/service/.*\\.java$", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*/api/.*\\.(java|ts|js)$", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*Controller\\.java$"),
        Pattern.compile(".*Service\\.java$"),
        Pattern.compile(".*Repository\\.java$")
    );

    public ReviewEngine(
            GitProviderFactory gitProviderFactory,
            LlmRouter llmRouter,
            DiffParser diffParser,
            CommentFormatter commentFormatter,
            IgnoreCommentParser ignoreCommentParser,
            ResourceLoader resourceLoader,
            CombinedAnalysisService staticAnalysisService,
            LanguageDetector languageDetector,
            SmartContextExtractor smartContextExtractor,
            SecretRedactor secretRedactor) {
        this.gitProviderFactory = gitProviderFactory;
        this.llmRouter = llmRouter;
        this.diffParser = diffParser;
        this.commentFormatter = commentFormatter;
        this.ignoreCommentParser = ignoreCommentParser;
        this.languageDetector = languageDetector;
        this.resourceLoader = resourceLoader;
        this.staticAnalysisService = staticAnalysisService;
        this.smartContextExtractor = smartContextExtractor;
        this.secretRedactor = secretRedactor;
    }

    /**
     * Execute a full review for a PR
     */
    public ReviewResult executeReview(ReviewRequest request) {
        return executeReview(request, null);
    }

    /**
     * Execute a full review for a PR with progress callback
     */
    public ReviewResult executeReview(ReviewRequest request, Consumer<ProgressUpdate> progressCallback) {
        log.info("Starting review for {}/{} PR #{}", request.owner(), request.repo(), request.prNumber());
        long startTime = System.currentTimeMillis();

        // Ensure ThreadLocal cleanup happens even on exceptions
        try {
            return executeReviewInternal(request, progressCallback, startTime);
        } finally {
            // Always clean up temp resources (e.g., ESLint temp directories)
            staticAnalysisService.cleanupAnalysisSession();
        }
    }

    /**
     * Internal implementation of executeReview
     */
    private ReviewResult executeReviewInternal(ReviewRequest request, Consumer<ProgressUpdate> progressCallback, long startTime) {
        GitProvider gitProvider = gitProviderFactory.getProvider(request.gitProvider());

        // Fetch PR info
        PullRequestInfo prInfo = gitProvider.getPullRequest(request.owner(), request.repo(), request.prNumber());
        log.info("Reviewing PR: {} by {}", prInfo.title(), prInfo.author());

        // Try to fetch ESLint config from repo for JS/TS analysis
        // Note: Config is passed as parameter to avoid ThreadLocal issues in async execution
        final EslintConfig eslintConfig = fetchEslintConfig(gitProvider, request.owner(), request.repo(), prInfo.headCommitSha());
        if (eslintConfig != null) {
            log.info("Using project ESLint config: {}", eslintConfig.configFilename());
        }

        // Try to fetch custom review rules from repo
        // Note: Rules are passed as parameter to avoid ThreadLocal issues in async execution
        final String repoRules = fetchCustomRepoRules(gitProvider, request.owner(), request.repo(), prInfo.headCommitSha());
        if (repoRules != null) {
            log.info("Using custom repo review rules from {}", CUSTOM_RULES_PATH);
        }

        // Get changed files
        List<ChangedFile> changedFiles = gitProvider.getChangedFiles(request.owner(), request.repo(), request.prNumber());
        log.info("Found {} changed files", changedFiles.size());

        // Calculate total diff lines and enforce limit
        int totalDiffLines = changedFiles.stream()
            .mapToInt(f -> f.additions() + f.deletions())
            .sum();
        log.info("Total diff lines: {} (limit: {})", totalDiffLines, maxDiffLines);

        if (totalDiffLines > maxDiffLines) {
            // Cleanup is handled by finally block in executeReview()
            throw new IllegalArgumentException(String.format(
                "PR too large: %d lines changed exceeds limit of %d lines. Please split into smaller PRs.",
                totalDiffLines, maxDiffLines));
        }

        // Filter out files that don't need review
        List<ChangedFile> filesToReview = filterAndPrioritizeFiles(changedFiles);
        log.info("After filtering: {} files to review (skipped {})",
            filesToReview.size(), changedFiles.size() - filesToReview.size());

        // Limit files if necessary
        if (filesToReview.size() > maxFiles) {
            log.warn("PR has {} reviewable files, limiting to {}", filesToReview.size(), maxFiles);
            filesToReview = filesToReview.subList(0, maxFiles);
        }

        final int totalFiles = filesToReview.size();

        // Report initial progress
        if (progressCallback != null) {
            progressCallback.accept(new ProgressUpdate(0, totalFiles, "Starting review...", ProgressPhase.ANALYZING));
        }

        // Get the diff
        String diff = gitProvider.getDiff(request.owner(), request.repo(), request.prNumber());
        List<DiffParser.FileDiff> parsedDiffs = diffParser.parse(diff);

        // Get the LLM provider that will be used for reviews
        LlmProvider reviewProvider = llmRouter.routeRequest("review");
        String providerName = reviewProvider.getName();
        log.info("Using LLM provider: {}", providerName);

        // Track results - use synchronized lists for thread safety
        List<ReviewIssue> allIssues = java.util.Collections.synchronizedList(new ArrayList<>());
        List<ReviewComment> allComments = java.util.Collections.synchronizedList(new ArrayList<>());
        AtomicInteger totalInputTokens = new AtomicInteger(0);
        AtomicInteger totalOutputTokens = new AtomicInteger(0);
        AtomicInteger filesCompleted = new AtomicInteger(0);

        // Review files in parallel
        ExecutorService executor = Executors.newFixedThreadPool(parallelThreads);
        final List<ChangedFile> finalFilesToReview = filesToReview;
        java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean(false);
        try {
            List<CompletableFuture<Void>> futures = filesToReview.stream()
                .map(file -> CompletableFuture.runAsync(() -> {
                    try {
                        // Check for cancellation before processing each file
                        if (Thread.currentThread().isInterrupted() || cancelled.get()) {
                            log.info("Review cancelled, skipping file: {}", file.filename());
                            return;
                        }

                        // Report current file being reviewed
                        if (progressCallback != null) {
                            progressCallback.accept(new ProgressUpdate(
                                filesCompleted.get(),
                                totalFiles,
                                file.filename(),
                                ProgressPhase.REVIEWING
                            ));
                        }

                        FileReviewResult fileResult = reviewFile(
                            request, gitProvider, prInfo, file, parsedDiffs,
                            request.organizationId(), eslintConfig, repoRules
                        );
                        allIssues.addAll(fileResult.issues());
                        allComments.addAll(fileResult.comments());
                        totalInputTokens.addAndGet(fileResult.inputTokens());
                        totalOutputTokens.addAndGet(fileResult.outputTokens());

                        // Report progress after file completion
                        int completed = filesCompleted.incrementAndGet();
                        if (progressCallback != null) {
                            progressCallback.accept(new ProgressUpdate(
                                completed,
                                totalFiles,
                                file.filename(),
                                ProgressPhase.REVIEWING
                            ));
                        }
                    } catch (java.util.concurrent.CancellationException e) {
                        log.info("Review file task cancelled: {}", file.filename());
                        cancelled.set(true);
                    } catch (Exception e) {
                        if (Thread.currentThread().isInterrupted()) {
                            log.info("Review interrupted during file: {}", file.filename());
                            cancelled.set(true);
                        } else {
                            log.error("Error reviewing file: {}", file.filename(), e);
                        }
                        filesCompleted.incrementAndGet();
                    }
                }, executor))
                .toList();

            // Wait for all files to be reviewed
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        } catch (java.util.concurrent.CancellationException e) {
            log.info("Review execution was cancelled");
            cancelled.set(true);
        } finally {
            executor.shutdownNow(); // Use shutdownNow to interrupt running tasks on cancellation
        }

        // If cancelled, throw an exception to signal cancellation
        // Cleanup is handled by finally block in executeReview()
        if (cancelled.get() || Thread.currentThread().isInterrupted()) {
            throw new ReviewCancelledException("Review was cancelled");
        }

        // Report generating summary
        if (progressCallback != null) {
            progressCallback.accept(new ProgressUpdate(totalFiles, totalFiles, "Generating summary...", ProgressPhase.SUMMARIZING));
        }

        // Generate summary
        String summary = generateSummary(prInfo, finalFilesToReview, new ArrayList<>(allIssues));

        // Calculate stats
        int additions = finalFilesToReview.stream().mapToInt(ChangedFile::additions).sum();
        int deletions = finalFilesToReview.stream().mapToInt(ChangedFile::deletions).sum();

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("Review completed in {}ms for {} files with {} issues",
            elapsed, finalFilesToReview.size(), allIssues.size());

        // Report completion
        if (progressCallback != null) {
            progressCallback.accept(new ProgressUpdate(totalFiles, totalFiles, "Complete", ProgressPhase.COMPLETE));
        }

        // Calculate cost using the actual provider's pricing
        int inputTokens = totalInputTokens.get();
        int outputTokens = totalOutputTokens.get();
        double estimatedCost = reviewProvider.estimateCost(inputTokens, outputTokens);

        // Cleanup is handled by finally block in executeReview()

        return new ReviewResult(
            summary,
            new ArrayList<>(allIssues),
            new ArrayList<>(allComments),
            finalFilesToReview.size(),
            additions,
            deletions,
            inputTokens,
            outputTokens,
            providerName,
            estimatedCost,
            diff,
            parsedDiffs
        );
    }

    /**
     * Progress update for review tracking
     */
    public record ProgressUpdate(
        int filesCompleted,
        int totalFiles,
        String currentFile,
        ProgressPhase phase
    ) {
        public int percentComplete() {
            if (totalFiles == 0) return 0;
            return (int) ((filesCompleted * 100.0) / totalFiles);
        }
    }

    public enum ProgressPhase {
        ANALYZING,    // Fetching PR info and files
        REVIEWING,    // Reviewing files
        SUMMARIZING,  // Generating summary
        COMPLETE      // Done
    }

    /**
     * Filter and prioritize files for review
     */
    private List<ChangedFile> filterAndPrioritizeFiles(List<ChangedFile> files) {
        List<ChangedFile> toReview = new ArrayList<>();
        List<ChangedFile> priorityFiles = new ArrayList<>();
        List<ChangedFile> normalFiles = new ArrayList<>();

        for (ChangedFile file : files) {
            String filename = file.filename();

            // Skip files matching skip patterns
            if (shouldSkipFile(filename)) {
                log.debug("Skipping file: {}", filename);
                continue;
            }

            // Check if high priority
            if (isPriorityFile(filename)) {
                priorityFiles.add(file);
            } else {
                normalFiles.add(file);
            }
        }

        // Priority files first, then normal files
        toReview.addAll(priorityFiles);
        toReview.addAll(normalFiles);

        return toReview;
    }

    /**
     * Check if a file should be skipped
     */
    private boolean shouldSkipFile(String filename) {
        // Always skip certain files
        for (Pattern pattern : SKIP_PATTERNS) {
            if (pattern.matcher(filename).matches()) {
                // But allow tests if skipTests is false
                if (!skipTests && isTestFile(filename)) {
                    return false;
                }
                return true;
            }
        }
        return false;
    }

    private boolean isTestFile(String filename) {
        return filename.contains("/test/") ||
               filename.contains("/__tests__/") ||
               filename.matches(".*Test\\.java$") ||
               filename.matches(".*Tests\\.java$") ||
               filename.matches(".*\\.test\\.(js|ts|jsx|tsx)$") ||
               filename.matches(".*\\.spec\\.(js|ts|jsx|tsx)$");
    }

    private boolean isPriorityFile(String filename) {
        for (Pattern pattern : PRIORITY_PATTERNS) {
            if (pattern.matcher(filename).matches()) {
                return true;
            }
        }
        return false;
    }

    private FileReviewResult reviewFile(
            ReviewRequest request,
            GitProvider gitProvider,
            PullRequestInfo prInfo,
            ChangedFile file,
            List<DiffParser.FileDiff> parsedDiffs,
            java.util.UUID organizationId,
            EslintConfig eslintConfig,
            String customRepoRules) {

        log.debug("Reviewing file: {}", file.filename());

        List<ReviewIssue> issues = new ArrayList<>();
        List<ReviewComment> comments = new ArrayList<>();
        int inputTokens = 0;
        int outputTokens = 0;

        // Get file content
        String fileContent = null;
        try {
            fileContent = gitProvider.getFileContent(
                request.owner(), request.repo(), file.filename(), prInfo.headCommitSha()
            );
        } catch (Exception e) {
            log.debug("Could not get file content for {}, might be deleted", file.filename());
        }

        // Check if file should be ignored
        if (fileContent != null && ignoreCommentParser.shouldIgnoreFile(fileContent)) {
            log.info("Skipping file {} due to @codelens-ignore-file", file.filename());
            return new FileReviewResult(issues, comments, 0, 0);
        }

        // Get ignored lines
        Set<Integer> ignoredLines = fileContent != null ?
            ignoreCommentParser.getIgnoredLines(fileContent) : Set.of();

        // Find the corresponding diff
        DiffParser.FileDiff fileDiff = parsedDiffs.stream()
            .filter(d -> d.getPath().equals(file.filename()))
            .findFirst()
            .orElse(null);

        if (fileDiff == null || file.patch() == null) {
            return new FileReviewResult(issues, comments, 0, 0);
        }

        // Run static analysis and AI review in parallel
        final String finalFileContent = fileContent;
        final Set<Integer> finalIgnoredLines = ignoredLines;

        // Start static analysis in background (including custom rules for organization)
        // Note: eslintConfig is passed explicitly to avoid ThreadLocal issues in async execution
        final java.util.UUID finalOrgId = organizationId;
        final EslintConfig finalEslintConfig = eslintConfig;

        // Get changed line numbers from the diff (only additions, not context lines)
        // Static analysis should only report issues on lines that were actually changed
        final Set<Integer> changedLines = getChangedLineNumbers(file.patch());

        CompletableFuture<List<ReviewIssue>> staticFuture = CompletableFuture.supplyAsync(() -> {
            List<ReviewIssue> staticIssues = new ArrayList<>();
            if (finalFileContent != null) {
                try {
                    List<AnalysisIssue> analysisIssues = staticAnalysisService.analyzeFile(
                        file.filename(), finalFileContent, finalOrgId, finalEslintConfig);

                    // Filter to only issues on changed lines
                    analysisIssues = staticAnalysisService.filterToChangedLines(analysisIssues, changedLines);

                    for (AnalysisIssue staticIssue : analysisIssues) {
                        if (!finalIgnoredLines.contains(staticIssue.line())) {
                            staticIssues.add(convertStaticIssue(staticIssue, file.filename()));
                        }
                    }
                    log.debug("Static analysis found {} issues in {} (filtered to {} changed lines)",
                        analysisIssues.size(), file.filename(), changedLines.size());
                } catch (Exception e) {
                    log.warn("Static analysis failed for {}: {}", file.filename(), e.getMessage());
                }
            }
            return staticIssues;
        });

        // Check if we should skip LLM for this file (config, docs, simple DTOs)
        SmartContextExtractor.ExtractionResult extraction =
            smartContextExtractor.extract(file.filename(), fileContent, file.patch());

        if (extraction.mode() == SmartContextExtractor.ReviewMode.SKIP_LLM) {
            log.info("Skipping LLM review for {} ({}), static analysis only",
                file.filename(), extraction.reason());
            // Still run static analysis, skip LLM
        } else if (extraction.mode() == SmartContextExtractor.ReviewMode.SECURITY_SCAN) {
            // Lightweight security-focused scan for config files
            // Only sends the DIFF, not full file content, to avoid leaking existing secrets
            log.info("Running security scan for config file: {}", file.filename());
            String securityPrompt = buildSecurityScanPrompt(file.filename(), file.patch());
            try {
                // Use fallback-enabled generation for reliability
                LlmProvider.LlmResponse response = llmRouter.generate(securityPrompt, "security");

                inputTokens = response.inputTokens();
                outputTokens = response.outputTokens();

                // Parse security scan response (same format as review)
                parseReviewResponse(response.content(), file.filename(), prInfo.headCommitSha(),
                    issues, comments, ignoredLines);

                log.debug("Security scan for {} used {} input, {} output tokens",
                    file.filename(), inputTokens, outputTokens);

            } catch (Exception e) {
                log.error("Error running security scan for {}", file.filename(), e);
            }
        } else {
            // Run full AI review with automatic fallback
            String prompt = buildReviewPrompt(file.filename(), file.patch(), fileContent, customRepoRules);
            try {
                // Use fallback-enabled generation for reliability
                LlmProvider.LlmResponse response = llmRouter.generate(prompt, "review");

                inputTokens = response.inputTokens();
                outputTokens = response.outputTokens();

                // Parse LLM response into issues and comments
                parseReviewResponse(response.content(), file.filename(), prInfo.headCommitSha(),
                    issues, comments, ignoredLines);

                log.debug("LLM review for {} used {} input, {} output tokens (mode: {})",
                    file.filename(), inputTokens, outputTokens, extraction.mode());

            } catch (Exception e) {
                log.error("Error getting LLM review for {}", file.filename(), e);
            }
        }

        // Wait for static analysis and merge results
        try {
            List<ReviewIssue> staticIssues = staticFuture.join();
            issues.addAll(staticIssues);
        } catch (Exception e) {
            log.warn("Failed to get static analysis results for {}", file.filename(), e);
        }

        return new FileReviewResult(issues, comments, inputTokens, outputTokens);
    }

    /**
     * Convert static analysis issue to ReviewIssue
     */
    private ReviewIssue convertStaticIssue(AnalysisIssue staticIssue, String filename) {
        ReviewIssue issue = new ReviewIssue();
        issue.setFilePath(filename);
        issue.setLineNumber(staticIssue.line());
        issue.setEndLine(staticIssue.endLine() > 0 ? staticIssue.endLine() : null);
        issue.setSeverity(mapStaticSeverity(staticIssue.severity()));
        issue.setCategory(mapStaticCategory(staticIssue.category()));
        issue.setRule(staticIssue.ruleId());
        issue.setDescription(staticIssue.message());
        issue.setSuggestedFix(staticIssue.suggestion());
        issue.setSource(ReviewIssue.Source.STATIC);
        issue.setAnalyzer(staticIssue.analyzer());
        issue.setCveId(staticIssue.cveId());
        issue.setCvssScore(staticIssue.cvssScore());
        return issue;
    }

    /**
     * Extract line numbers of added/modified lines from a unified diff patch.
     * Only returns line numbers for additions (lines starting with '+'),
     * not context lines or deletions.
     */
    private Set<Integer> getChangedLineNumbers(String patch) {
        Set<Integer> changedLines = new java.util.HashSet<>();
        if (patch == null || patch.isEmpty()) {
            return changedLines;
        }

        // Parse the unified diff to extract added line numbers
        java.util.regex.Pattern hunkHeader = java.util.regex.Pattern.compile("^@@ -\\d+(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@");
        int currentNewLine = 0;

        for (String line : patch.split("\n")) {
            java.util.regex.Matcher matcher = hunkHeader.matcher(line);
            if (matcher.find()) {
                // New hunk - reset line counter to the new file start line
                currentNewLine = Integer.parseInt(matcher.group(1));
                continue;
            }

            if (currentNewLine > 0) {
                if (line.startsWith("+") && !line.startsWith("+++")) {
                    // This is an added line
                    changedLines.add(currentNewLine);
                    currentNewLine++;
                } else if (line.startsWith("-") && !line.startsWith("---")) {
                    // Deleted line - don't increment new line counter
                } else if (line.startsWith(" ") || line.isEmpty()) {
                    // Context line
                    currentNewLine++;
                }
            }
        }

        return changedLines;
    }

    private ReviewIssue.Severity mapStaticSeverity(AnalysisIssue.Severity severity) {
        return switch (severity) {
            case CRITICAL -> ReviewIssue.Severity.CRITICAL;
            case HIGH -> ReviewIssue.Severity.HIGH;
            case MEDIUM -> ReviewIssue.Severity.MEDIUM;
            case LOW -> ReviewIssue.Severity.LOW;
            case INFO -> ReviewIssue.Severity.INFO;
        };
    }

    private ReviewIssue.Category mapStaticCategory(String category) {
        if (category == null) return ReviewIssue.Category.SMELL;
        String upper = category.toUpperCase();
        if (upper.contains("CVE")) return ReviewIssue.Category.CVE;
        if (upper.contains("SECURITY") || upper.contains("VULN")) return ReviewIssue.Category.SECURITY;
        if (upper.contains("BUG") || upper.contains("ERROR")) return ReviewIssue.Category.BUG;
        if (upper.contains("PERF")) return ReviewIssue.Category.PERFORMANCE;
        if (upper.contains("STYLE") || upper.contains("FORMAT")) return ReviewIssue.Category.STYLE;
        if (upper.contains("LOGIC")) return ReviewIssue.Category.LOGIC;
        return ReviewIssue.Category.SMELL;
    }

    /**
     * Build a lightweight security-focused prompt for config files.
     * Only sends the DIFF, not full file content, to avoid leaking existing secrets.
     * Additionally redacts any detected secrets before sending to LLM.
     */
    private String buildSecurityScanPrompt(String filename, String patch) {
        String template = loadPromptTemplate("security-config.txt");
        // Redact secrets from patch before sending to LLM
        String safePatch = patch != null ? secretRedactor.redactSecrets(patch) : "";
        return template
            .replace("{{filename}}", filename)
            .replace("{{patch}}", safePatch);
    }

    private String buildReviewPrompt(String filename, String patch, String fileContent,
            String customRepoRules) {
        // Use smart context extraction to reduce token usage
        SmartContextExtractor.ExtractionResult extraction =
            smartContextExtractor.extract(filename, fileContent, patch);

        // Detect language and use language-specific prompt
        String promptFile = getLanguagePromptFile(filename);
        String template = loadPromptTemplate(promptFile);

        String contextToSend;
        switch (extraction.mode()) {
            case SKIP_LLM:
                // This shouldn't happen here - SKIP_LLM should be handled in reviewFile
                log.warn("SKIP_LLM mode in buildReviewPrompt - should be handled earlier");
                contextToSend = "";
                break;
            case DIFF_ONLY:
                contextToSend = "// Mode: Diff-only (no additional context needed)\n";
                log.debug("Using DIFF_ONLY mode for {}: {}", filename, extraction.reason());
                break;
            case SMART_CONTEXT:
                contextToSend = extraction.context() != null ? extraction.context() : "";
                log.debug("Using SMART_CONTEXT mode for {}: {} ({} chars)",
                    filename, extraction.reason(), contextToSend.length());
                break;
            default:
                contextToSend = "";
        }

        // Redact secrets from code before sending to LLM
        String safePatch = patch != null ? secretRedactor.redactSecrets(patch) : "";
        String safeContext = secretRedactor.redactSecrets(contextToSend);

        String prompt = template
            .replace("{{filename}}", filename)
            .replace("{{patch}}", safePatch)
            .replace("{{file_content}}", safeContext);

        // Append custom repo rules if available (passed as parameter for async safety)
        if (customRepoRules != null && !customRepoRules.isBlank()) {
            prompt = prompt + "\n\n---\n\n## Additional Project-Specific Rules\n\n" + customRepoRules;
        }

        return prompt;
    }

    /**
     * Get the appropriate prompt file for a given filename based on its language
     */
    private String getLanguagePromptFile(String filename) {
        String language = languageDetector.detectFromFilename(filename);
        if (language != null) {
            String promptFile = LANGUAGE_PROMPT_MAP.get(language);
            if (promptFile != null) {
                log.debug("Using {} prompt for file: {}", language, filename);
                return promptFile;
            }
        }
        log.debug("Using generic prompt for file: {}", filename);
        return "review.txt";
    }

    private String generateSummary(PullRequestInfo prInfo, List<ChangedFile> files, List<ReviewIssue> issues) {
        String template = loadPromptTemplate("summary.txt");

        StringBuilder fileList = new StringBuilder();
        for (ChangedFile file : files) {
            fileList.append("- ").append(file.filename())
                .append(" (+").append(file.additions())
                .append("/-").append(file.deletions()).append(")\n");
        }

        String prompt = template
            .replace("{{title}}", prInfo.title())
            .replace("{{description}}", prInfo.description() != null ? prInfo.description() : "")
            .replace("{{files}}", fileList.toString())
            .replace("{{issue_count}}", String.valueOf(issues.size()));

        try {
            // Use fallback-enabled generation for reliability
            LlmProvider.LlmResponse response = llmRouter.generate(prompt, "summary");
            return response.content();
        } catch (Exception e) {
            log.error("Error generating summary", e);
            return "Review completed with " + issues.size() + " issues found.";
        }
    }

    private void parseReviewResponse(
            String response,
            String filename,
            String commitSha,
            List<ReviewIssue> issues,
            List<ReviewComment> comments,
            Set<Integer> ignoredLines) {

        // Extract JSON from response (handle markdown code blocks)
        String json = extractJson(response);
        if (json == null || json.isBlank()) {
            log.debug("No JSON found in response for {}", filename);
            return;
        }

        try {
            List<Map<String, Object>> issueList = objectMapper.readValue(
                json, new TypeReference<List<Map<String, Object>>>() {});

            for (Map<String, Object> issueData : issueList) {
                try {
                    int lineNum = ((Number) issueData.get("line")).intValue();

                    // Skip if line is ignored
                    if (ignoredLines.contains(lineNum)) {
                        continue;
                    }

                    String severityStr = (String) issueData.getOrDefault("severity", "LOW");
                    String categoryStr = (String) issueData.getOrDefault("category", "SMELL");
                    String rule = (String) issueData.getOrDefault("rule", "ai-review");
                    String message = (String) issueData.get("message");
                    String suggestion = (String) issueData.get("suggestion");
                    String existingCode = (String) issueData.get("existing_code");
                    String confidence = (String) issueData.getOrDefault("confidence", "MEDIUM");

                    ReviewIssue.Severity severity = parseEnumSafe(severityStr, ReviewIssue.Severity.class, ReviewIssue.Severity.LOW);
                    ReviewIssue.Category category = parseEnumSafe(categoryStr, ReviewIssue.Category.class, ReviewIssue.Category.SMELL);

                    ReviewIssue issue = new ReviewIssue();
                    issue.setFilePath(filename);
                    issue.setLineNumber(lineNum);
                    issue.setSeverity(severity);
                    issue.setCategory(category);
                    issue.setRule(rule);
                    issue.setDescription(message);
                    issue.setSuggestedFix(suggestion);
                    issue.setSource(ReviewIssue.Source.AI);
                    issue.setAnalyzer("ai");
                    // Build AI explanation with existing code and confidence
                    if (existingCode != null || confidence != null) {
                        StringBuilder explanation = new StringBuilder();
                        if (existingCode != null) {
                            explanation.append("**Problematic code:**\n```\n").append(existingCode).append("\n```\n");
                        }
                        if (confidence != null) {
                            explanation.append("**Confidence:** ").append(confidence);
                        }
                        issue.setAiExplanation(explanation.toString());
                    }
                    issues.add(issue);

                    // Also create inline comment
                    ReviewComment comment = new ReviewComment();
                    comment.setFilePath(filename);
                    comment.setLineNumber(lineNum);
                    comment.setBody(message + (suggestion != null ? "\n\n**Suggestion:** " + suggestion : ""));
                    comment.setSeverity(mapSeverity(severity));
                    comment.setCommitSha(commitSha);
                    comments.add(comment);

                } catch (Exception e) {
                    log.debug("Could not parse issue: {}", issueData, e);
                }
            }
        } catch (Exception e) {
            log.debug("JSON parsing needs repair for {}, attempting auto-fix", filename);
            // Try to repair truncated JSON
            String repairedJson = repairTruncatedJson(json);
            if (repairedJson != null) {
                try {
                    List<Map<String, Object>> issueList = objectMapper.readValue(
                        repairedJson, new TypeReference<List<Map<String, Object>>>() {});
                    log.info("Successfully repaired truncated JSON for {}, recovered {} issues", filename, issueList.size());

                    for (Map<String, Object> issueData : issueList) {
                        try {
                            int lineNum = ((Number) issueData.get("line")).intValue();
                            if (ignoredLines.contains(lineNum)) {
                                continue;
                            }

                            String severityStr = (String) issueData.getOrDefault("severity", "LOW");
                            String categoryStr = (String) issueData.getOrDefault("category", "SMELL");
                            String rule = (String) issueData.getOrDefault("rule", "ai-review");
                            String message = (String) issueData.get("message");
                            String suggestion = (String) issueData.get("suggestion");

                            ReviewIssue.Severity severity = parseEnumSafe(severityStr, ReviewIssue.Severity.class, ReviewIssue.Severity.LOW);
                            ReviewIssue.Category category = parseEnumSafe(categoryStr, ReviewIssue.Category.class, ReviewIssue.Category.SMELL);

                            ReviewIssue issue = new ReviewIssue();
                            issue.setFilePath(filename);
                            issue.setLineNumber(lineNum);
                            issue.setSeverity(severity);
                            issue.setCategory(category);
                            issue.setRule(rule);
                            issue.setDescription(message);
                            issue.setSuggestedFix(suggestion);
                            issue.setSource(ReviewIssue.Source.AI);
                            issue.setAnalyzer("ai");
                            issues.add(issue);

                            ReviewComment comment = new ReviewComment();
                            comment.setFilePath(filename);
                            comment.setLineNumber(lineNum);
                            comment.setBody(message + (suggestion != null ? "\n\n**Suggestion:** " + suggestion : ""));
                            comment.setSeverity(mapSeverity(severity));
                            comment.setCommitSha(commitSha);
                            comments.add(comment);
                        } catch (Exception ex) {
                            log.debug("Could not parse repaired issue: {}", issueData);
                        }
                    }
                    return; // Successfully parsed repaired JSON
                } catch (Exception repairEx) {
                    log.debug("JSON repair failed for {}, falling back to text parsing: {}", filename, repairEx.getMessage());
                }
            }
            // Fallback to simple text parsing for backwards compatibility
            parseReviewResponseLegacy(response, filename, commitSha, issues, comments, ignoredLines);
        }
    }

    private String extractJson(String response) {
        // Try to extract JSON from markdown code block
        if (response.contains("```json")) {
            int start = response.indexOf("```json") + 7;
            int end = response.indexOf("```", start);
            if (end > start) {
                return response.substring(start, end).trim();
            }
            // No closing ```, likely truncated - take everything after ```json
            return response.substring(start).trim();
        }
        // Try to extract JSON from plain code block
        if (response.contains("```")) {
            int start = response.indexOf("```") + 3;
            int end = response.indexOf("```", start);
            if (end > start) {
                String content = response.substring(start, end).trim();
                if (content.startsWith("[")) {
                    return content;
                }
            }
        }
        // Try raw JSON array
        if (response.trim().startsWith("[")) {
            return response.trim();
        }
        return null;
    }

    /**
     * Attempts to repair truncated JSON array by finding the last complete object
     * and properly closing the array.
     */
    private String repairTruncatedJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }

        json = json.trim();
        if (!json.startsWith("[")) {
            return null;
        }

        // If it's already valid, return as-is
        if (json.endsWith("]")) {
            return json;
        }

        // Find the last complete JSON object by finding the last "}," or "}" that closes an object
        int lastCompleteObject = -1;
        int braceCount = 0;
        int bracketCount = 0;
        boolean inString = false;
        boolean escape = false;

        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);

            if (escape) {
                escape = false;
                continue;
            }

            if (c == '\\' && inString) {
                escape = true;
                continue;
            }

            if (c == '"') {
                inString = !inString;
                continue;
            }

            if (inString) {
                continue;
            }

            switch (c) {
                case '[':
                    bracketCount++;
                    break;
                case ']':
                    bracketCount--;
                    break;
                case '{':
                    braceCount++;
                    break;
                case '}':
                    braceCount--;
                    // If we're back to array level (bracketCount=1, braceCount=0), this is a complete object
                    if (bracketCount == 1 && braceCount == 0) {
                        lastCompleteObject = i;
                    }
                    break;
            }
        }

        if (lastCompleteObject > 0) {
            // Extract up to and including the last complete object, then close the array
            String repaired = json.substring(0, lastCompleteObject + 1);
            // Remove trailing comma if present
            repaired = repaired.stripTrailing();
            if (repaired.endsWith(",")) {
                repaired = repaired.substring(0, repaired.length() - 1);
            }
            repaired = repaired + "]";
            log.debug("Repaired truncated JSON: extracted {} chars, found {} complete objects",
                repaired.length(), countObjects(repaired));
            return repaired;
        }

        // No complete objects found
        return null;
    }

    private int countObjects(String json) {
        int count = 0;
        int depth = 0;
        boolean inString = false;
        for (char c : json.toCharArray()) {
            if (c == '"' && depth > 0) inString = !inString;
            if (!inString) {
                if (c == '{') depth++;
                if (c == '}') {
                    depth--;
                    if (depth == 0) count++;
                }
            }
        }
        return count;
    }

    private <T extends Enum<T>> T parseEnumSafe(String value, Class<T> enumClass, T defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Enum.valueOf(enumClass, value.toUpperCase().trim());
        } catch (IllegalArgumentException e) {
            return defaultValue;
        }
    }

    private void parseReviewResponseLegacy(
            String response,
            String filename,
            String commitSha,
            List<ReviewIssue> issues,
            List<ReviewComment> comments,
            Set<Integer> ignoredLines) {

        String[] lines = response.split("\n");

        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;

            if (line.matches("^Line \\d+:.*")) {
                try {
                    int colonIdx = line.indexOf(":");
                    int lineNum = Integer.parseInt(line.substring(5, colonIdx).trim());

                    if (ignoredLines.contains(lineNum)) {
                        continue;
                    }

                    String rest = line.substring(colonIdx + 1).trim();
                    ReviewIssue.Severity severity = extractSeverity(rest);
                    String description = rest.replaceAll("\\[(CRITICAL|HIGH|MEDIUM|LOW)\\]\\s*", "");

                    ReviewIssue issue = new ReviewIssue();
                    issue.setFilePath(filename);
                    issue.setLineNumber(lineNum);
                    issue.setSeverity(severity);
                    issue.setDescription(description);
                    issue.setSource(ReviewIssue.Source.AI);
                    issue.setAnalyzer("ai");
                    issue.setCategory(categorizeIssue(description));
                    issue.setRule("ai-review");
                    issues.add(issue);

                    ReviewComment comment = new ReviewComment();
                    comment.setFilePath(filename);
                    comment.setLineNumber(lineNum);
                    comment.setBody(description);
                    comment.setSeverity(mapSeverity(severity));
                    comment.setCommitSha(commitSha);
                    comments.add(comment);

                } catch (Exception e) {
                    log.debug("Could not parse issue line: {}", line);
                }
            }
        }
    }

    private ReviewIssue.Severity extractSeverity(String text) {
        if (text.contains("[CRITICAL]")) return ReviewIssue.Severity.CRITICAL;
        if (text.contains("[HIGH]")) return ReviewIssue.Severity.HIGH;
        if (text.contains("[MEDIUM]")) return ReviewIssue.Severity.MEDIUM;
        return ReviewIssue.Severity.LOW;
    }

    private ReviewIssue.Category categorizeIssue(String description) {
        String lower = description.toLowerCase();
        if (lower.contains("security") || lower.contains("injection") || lower.contains("xss") ||
            lower.contains("vulnerability") || lower.contains("auth") || lower.contains("credential")) {
            return ReviewIssue.Category.SECURITY;
        }
        if (lower.contains("bug") || lower.contains("error") || lower.contains("null") ||
            lower.contains("exception") || lower.contains("crash")) {
            return ReviewIssue.Category.BUG;
        }
        if (lower.contains("performance") || lower.contains("slow") || lower.contains("memory") ||
            lower.contains("optimize")) {
            return ReviewIssue.Category.PERFORMANCE;
        }
        if (lower.contains("style") || lower.contains("naming") || lower.contains("format")) {
            return ReviewIssue.Category.STYLE;
        }
        if (lower.contains("logic") || lower.contains("condition") || lower.contains("branch")) {
            return ReviewIssue.Category.LOGIC;
        }
        return ReviewIssue.Category.SMELL;
    }

    private ReviewComment.Severity mapSeverity(ReviewIssue.Severity severity) {
        return switch (severity) {
            case CRITICAL -> ReviewComment.Severity.CRITICAL;
            case HIGH -> ReviewComment.Severity.HIGH;
            case MEDIUM -> ReviewComment.Severity.MEDIUM;
            case LOW -> ReviewComment.Severity.LOW;
            case INFO -> ReviewComment.Severity.INFO;
        };
    }

    private String truncateContent(String content) {
        String[] lines = content.split("\n");
        if (lines.length <= maxLinesPerFile) {
            return content;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < maxLinesPerFile; i++) {
            sb.append(lines[i]).append("\n");
        }
        sb.append("\n... (truncated)");
        return sb.toString();
    }

    private String loadPromptTemplate(String name) {
        try {
            Resource resource = resourceLoader.getResource("classpath:prompts/" + name);
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Could not load prompt template {}, using default", name);
            return getDefaultPromptTemplate(name);
        }
    }

    private String getDefaultPromptTemplate(String name) {
        if ("review.txt".equals(name)) {
            return """
                You are an expert code reviewer. Review the following code changes.

                File: {{filename}}

                Changes (diff):
                {{patch}}

                Full file (context):
                {{file_content}}

                **Guidelines:**
                - Focus ONLY on new/modified code (lines starting with '+')
                - Do NOT question imports, variables, or functions defined elsewhere
                - IGNORE minor style/formatting issues
                - Be constructive with actionable suggestions

                **Categories:** SECURITY, BUG, LOGIC, PERFORMANCE, SMELL

                Respond with JSON array:
                ```json
                [
                  {
                    "line": 42,
                    "severity": "HIGH",
                    "category": "SECURITY",
                    "rule": "sql-injection",
                    "message": "User input concatenated into SQL query",
                    "suggestion": "Use parameterized queries"
                  }
                ]
                ```

                Severity: CRITICAL, HIGH, MEDIUM, LOW, INFO
                If no issues, return: []
                Return ONLY JSON array.
                """;
        } else if ("summary.txt".equals(name)) {
            return """
                Summarize this pull request in 2-3 sentences.

                Title: {{title}}
                Description: {{description}}

                Changed files:
                {{files}}

                Issues found: {{issue_count}}

                Provide a concise summary of what this PR does and any concerns.
                """;
        }
        return "";
    }

    /**
     * Fetch custom review rules from the repository if they exist.
     * Looks for .codelens/review-rules.md in the repo.
     * Truncates to MAX_CUSTOM_RULES_CHARS to prevent prompt bloat.
     */
    private String fetchCustomRepoRules(GitProvider gitProvider, String owner, String repo, String commitSha) {
        try {
            String content = gitProvider.getFileContent(owner, repo, CUSTOM_RULES_PATH, commitSha);
            if (content != null && !content.isBlank()) {
                if (content.length() > MAX_CUSTOM_RULES_CHARS) {
                    log.warn("Custom review rules too large ({} chars), truncating to {} chars",
                        content.length(), MAX_CUSTOM_RULES_CHARS);
                    content = content.substring(0, MAX_CUSTOM_RULES_CHARS) + "\n\n[Truncated - limit: " + MAX_CUSTOM_RULES_CHARS + " chars]";
                }
                log.debug("Found custom review rules: {} chars", content.length());
                return content;
            }
        } catch (Exception e) {
            log.trace("No custom review rules found at {}", CUSTOM_RULES_PATH);
        }
        return null;
    }

    /**
     * Fetch ESLint config from the repository if it exists.
     * Tries each known config file name until one is found.
     */
    private EslintConfig fetchEslintConfig(GitProvider gitProvider, String owner, String repo, String commitSha) {
        for (String configFilename : EslintAnalyzer.getConfigFileNames()) {
            try {
                String configContent = gitProvider.getFileContent(owner, repo, configFilename, commitSha);
                if (configContent != null && !configContent.isBlank()) {
                    log.debug("Found ESLint config: {}", configFilename);
                    return new EslintConfig(configFilename, configContent);
                }
            } catch (Exception e) {
                // File doesn't exist, try next
                log.trace("ESLint config not found: {}", configFilename);
            }
        }

        // Also check package.json for eslintConfig
        try {
            String packageJson = gitProvider.getFileContent(owner, repo, "package.json", commitSha);
            if (packageJson != null && packageJson.contains("\"eslintConfig\"")) {
                log.debug("Found eslintConfig in package.json");
                return new EslintConfig("package.json", packageJson);
            }
        } catch (Exception e) {
            log.trace("No package.json found");
        }

        return null;
    }

    /**
     * Request to review a PR
     */
    public record ReviewRequest(
        com.codelens.model.entity.Repository.GitProvider gitProvider,
        String owner,
        String repo,
        int prNumber,
        java.util.UUID organizationId
    ) {
        // Constructor for backwards compatibility
        public ReviewRequest(
            com.codelens.model.entity.Repository.GitProvider gitProvider,
            String owner,
            String repo,
            int prNumber
        ) {
            this(gitProvider, owner, repo, prNumber, null);
        }
    }

    /**
     * Result of reviewing a single file
     */
    private record FileReviewResult(
        List<ReviewIssue> issues,
        List<ReviewComment> comments,
        int inputTokens,
        int outputTokens
    ) {}

    /**
     * Complete review result
     */
    public record ReviewResult(
        String summary,
        List<ReviewIssue> issues,
        List<ReviewComment> comments,
        int filesReviewed,
        int linesAdded,
        int linesRemoved,
        int totalInputTokens,
        int totalOutputTokens,
        String llmProvider,
        double estimatedCost,
        String rawDiff,
        List<DiffParser.FileDiff> parsedDiffs
    ) {}

    /**
     * Request to review a single commit
     */
    public record CommitReviewRequest(
        com.codelens.model.entity.Repository.GitProvider gitProvider,
        String owner,
        String repo,
        String commitSha,
        java.util.UUID organizationId
    ) {}

    /**
     * Execute a full review for a single commit
     */
    public ReviewResult executeCommitReview(CommitReviewRequest request) {
        return executeCommitReview(request, null);
    }

    /**
     * Execute a full review for a single commit with progress callback
     */
    public ReviewResult executeCommitReview(CommitReviewRequest request, Consumer<ProgressUpdate> progressCallback) {
        log.info("Starting commit review for {}/{} commit {}", request.owner(), request.repo(), request.commitSha());
        long startTime = System.currentTimeMillis();

        try {
            return executeCommitReviewInternal(request, progressCallback, startTime);
        } finally {
            staticAnalysisService.cleanupAnalysisSession();
        }
    }

    /**
     * Internal implementation of executeCommitReview
     */
    private ReviewResult executeCommitReviewInternal(CommitReviewRequest request, Consumer<ProgressUpdate> progressCallback, long startTime) {
        GitProvider gitProvider = gitProviderFactory.getProvider(request.gitProvider());

        // Fetch commit info
        GitProvider.CommitInfo commitInfo = gitProvider.getCommit(request.owner(), request.repo(), request.commitSha());
        log.info("Reviewing commit: {} by {}", commitInfo.message().split("\n")[0], commitInfo.author());

        // Fetch ESLint config for JS/TS files
        final EslintConfig eslintConfig = fetchEslintConfig(gitProvider, request.owner(), request.repo(), request.commitSha());
        if (eslintConfig != null) {
            log.info("Using project ESLint config: {}", eslintConfig.configFilename());
        }

        // Fetch custom review rules
        final String repoRules = fetchCustomRepoRules(gitProvider, request.owner(), request.repo(), request.commitSha());
        if (repoRules != null) {
            log.info("Using custom repo review rules from {}", CUSTOM_RULES_PATH);
        }

        // Get changed files in this commit
        List<GitProvider.ChangedFile> changedFiles = gitProvider.getCommitChangedFiles(request.owner(), request.repo(), request.commitSha());
        log.info("Found {} changed files in commit", changedFiles.size());

        // Calculate total diff lines
        int totalDiffLines = changedFiles.stream()
            .mapToInt(f -> f.additions() + f.deletions())
            .sum();
        log.info("Total diff lines: {} (limit: {})", totalDiffLines, maxDiffLines);

        if (totalDiffLines > maxDiffLines) {
            throw new IllegalArgumentException(String.format(
                "Commit too large: %d lines changed exceeds limit of %d lines.",
                totalDiffLines, maxDiffLines));
        }

        // Filter files
        List<GitProvider.ChangedFile> filesToReview = filterAndPrioritizeFiles(changedFiles);
        log.info("After filtering: {} files to review", filesToReview.size());

        if (filesToReview.size() > maxFiles) {
            log.warn("Commit has {} reviewable files, limiting to {}", filesToReview.size(), maxFiles);
            filesToReview = filesToReview.subList(0, maxFiles);
        }

        final int totalFiles = filesToReview.size();

        if (progressCallback != null) {
            progressCallback.accept(new ProgressUpdate(0, totalFiles, "Starting review...", ProgressPhase.ANALYZING));
        }

        // Get the diff
        String diff = gitProvider.getCommitDiff(request.owner(), request.repo(), request.commitSha());
        List<DiffParser.FileDiff> parsedDiffs = diffParser.parse(diff);

        // Get LLM provider
        LlmProvider reviewProvider = llmRouter.routeRequest("review");
        String providerName = reviewProvider.getName();
        log.info("Using LLM provider: {}", providerName);

        // Track results
        List<ReviewIssue> allIssues = java.util.Collections.synchronizedList(new ArrayList<>());
        List<ReviewComment> allComments = java.util.Collections.synchronizedList(new ArrayList<>());
        AtomicInteger totalInputTokens = new AtomicInteger(0);
        AtomicInteger totalOutputTokens = new AtomicInteger(0);
        AtomicInteger filesCompleted = new AtomicInteger(0);

        // Review files in parallel
        ExecutorService executor = Executors.newFixedThreadPool(parallelThreads);
        final List<GitProvider.ChangedFile> finalFilesToReview = filesToReview;
        java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean(false);

        try {
            List<CompletableFuture<Void>> futures = filesToReview.stream()
                .map(file -> CompletableFuture.runAsync(() -> {
                    try {
                        if (Thread.currentThread().isInterrupted() || cancelled.get()) {
                            log.info("Review cancelled, skipping file: {}", file.filename());
                            return;
                        }

                        if (progressCallback != null) {
                            progressCallback.accept(new ProgressUpdate(
                                filesCompleted.get(),
                                totalFiles,
                                file.filename(),
                                ProgressPhase.REVIEWING
                            ));
                        }

                        FileReviewResult fileResult = reviewCommitFile(
                            request, gitProvider, commitInfo, file, parsedDiffs,
                            request.organizationId(), eslintConfig, repoRules
                        );
                        allIssues.addAll(fileResult.issues());
                        allComments.addAll(fileResult.comments());
                        totalInputTokens.addAndGet(fileResult.inputTokens());
                        totalOutputTokens.addAndGet(fileResult.outputTokens());

                        int completed = filesCompleted.incrementAndGet();
                        if (progressCallback != null) {
                            progressCallback.accept(new ProgressUpdate(
                                completed,
                                totalFiles,
                                file.filename(),
                                ProgressPhase.REVIEWING
                            ));
                        }
                    } catch (java.util.concurrent.CancellationException e) {
                        log.info("Review file task cancelled: {}", file.filename());
                        cancelled.set(true);
                    } catch (Exception e) {
                        if (Thread.currentThread().isInterrupted()) {
                            log.info("Review interrupted during file: {}", file.filename());
                            cancelled.set(true);
                        } else {
                            log.error("Error reviewing file: {}", file.filename(), e);
                        }
                        filesCompleted.incrementAndGet();
                    }
                }, executor))
                .toList();

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        } catch (java.util.concurrent.CancellationException e) {
            log.info("Commit review execution was cancelled");
            cancelled.set(true);
        } finally {
            executor.shutdownNow();
        }

        if (cancelled.get() || Thread.currentThread().isInterrupted()) {
            throw new ReviewCancelledException("Review was cancelled");
        }

        if (progressCallback != null) {
            progressCallback.accept(new ProgressUpdate(totalFiles, totalFiles, "Generating summary...", ProgressPhase.SUMMARIZING));
        }

        // Generate summary
        String summary = generateCommitSummary(commitInfo, finalFilesToReview, new ArrayList<>(allIssues));

        // Calculate stats
        int additions = finalFilesToReview.stream().mapToInt(GitProvider.ChangedFile::additions).sum();
        int deletions = finalFilesToReview.stream().mapToInt(GitProvider.ChangedFile::deletions).sum();

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("Commit review completed in {}ms for {} files with {} issues",
            elapsed, finalFilesToReview.size(), allIssues.size());

        if (progressCallback != null) {
            progressCallback.accept(new ProgressUpdate(totalFiles, totalFiles, "Complete", ProgressPhase.COMPLETE));
        }

        int inputTokens = totalInputTokens.get();
        int outputTokens = totalOutputTokens.get();
        double estimatedCost = reviewProvider.estimateCost(inputTokens, outputTokens);

        return new ReviewResult(
            summary,
            new ArrayList<>(allIssues),
            new ArrayList<>(allComments),
            finalFilesToReview.size(),
            additions,
            deletions,
            inputTokens,
            outputTokens,
            providerName,
            estimatedCost,
            diff,
            parsedDiffs
        );
    }

    /**
     * Review a single file in a commit
     */
    private FileReviewResult reviewCommitFile(
            CommitReviewRequest request,
            GitProvider gitProvider,
            GitProvider.CommitInfo commitInfo,
            GitProvider.ChangedFile file,
            List<DiffParser.FileDiff> parsedDiffs,
            java.util.UUID organizationId,
            EslintConfig eslintConfig,
            String customRepoRules) {

        log.debug("Reviewing file: {}", file.filename());

        List<ReviewIssue> issues = new ArrayList<>();
        List<ReviewComment> comments = new ArrayList<>();
        int inputTokens = 0;
        int outputTokens = 0;

        // Get file content (for added/modified files)
        String fileContent = null;
        try {
            if (!"deleted".equalsIgnoreCase(file.status()) && !"removed".equalsIgnoreCase(file.status())) {
                fileContent = gitProvider.getFileContent(
                    request.owner(), request.repo(), file.filename(), request.commitSha()
                );
            }
        } catch (Exception e) {
            log.debug("Could not get file content for {}, might be deleted", file.filename());
        }

        // Check if file should be ignored
        if (fileContent != null && ignoreCommentParser.shouldIgnoreFile(fileContent)) {
            log.info("Skipping file {} due to @codelens-ignore-file", file.filename());
            return new FileReviewResult(issues, comments, 0, 0);
        }

        Set<Integer> ignoredLines = fileContent != null ?
            ignoreCommentParser.getIgnoredLines(fileContent) : Set.of();

        // Find the corresponding diff
        DiffParser.FileDiff fileDiff = parsedDiffs.stream()
            .filter(d -> d.getPath().equals(file.filename()))
            .findFirst()
            .orElse(null);

        if (fileDiff == null || file.patch() == null) {
            return new FileReviewResult(issues, comments, 0, 0);
        }

        final String finalFileContent = fileContent;
        final Set<Integer> finalIgnoredLines = ignoredLines;
        final EslintConfig finalEslintConfig = eslintConfig;
        final java.util.UUID finalOrgId = organizationId;

        // Get changed line numbers from the diff (only additions, not context lines)
        final Set<Integer> changedLines = getChangedLineNumbers(file.patch());

        // Run static analysis in background
        CompletableFuture<List<ReviewIssue>> staticFuture = CompletableFuture.supplyAsync(() -> {
            List<ReviewIssue> staticIssues = new ArrayList<>();
            if (finalFileContent != null) {
                try {
                    List<AnalysisIssue> analysisIssues = staticAnalysisService.analyzeFile(
                        file.filename(), finalFileContent, finalOrgId, finalEslintConfig);

                    // Filter to only issues on changed lines
                    analysisIssues = staticAnalysisService.filterToChangedLines(analysisIssues, changedLines);

                    for (AnalysisIssue staticIssue : analysisIssues) {
                        if (!finalIgnoredLines.contains(staticIssue.line())) {
                            staticIssues.add(convertStaticIssue(staticIssue, file.filename()));
                        }
                    }
                    log.debug("Static analysis found {} issues in {} (filtered to {} changed lines)",
                        analysisIssues.size(), file.filename(), changedLines.size());
                } catch (Exception e) {
                    log.warn("Static analysis failed for {}: {}", file.filename(), e.getMessage());
                }
            }
            return staticIssues;
        });

        // Check review mode
        SmartContextExtractor.ExtractionResult extraction =
            smartContextExtractor.extract(file.filename(), fileContent, file.patch());

        if (extraction.mode() == SmartContextExtractor.ReviewMode.SKIP_LLM) {
            log.info("Skipping LLM review for {} ({}), static analysis only",
                file.filename(), extraction.reason());
        } else if (extraction.mode() == SmartContextExtractor.ReviewMode.SECURITY_SCAN) {
            log.info("Running security scan for config file: {}", file.filename());
            String securityPrompt = buildSecurityScanPrompt(file.filename(), file.patch());
            try {
                LlmProvider.LlmResponse response = llmRouter.generate(securityPrompt, "security");
                inputTokens = response.inputTokens();
                outputTokens = response.outputTokens();
                parseReviewResponse(response.content(), file.filename(), request.commitSha(),
                    issues, comments, ignoredLines);
            } catch (Exception e) {
                log.error("Error running security scan for {}", file.filename(), e);
            }
        } else {
            // Full AI review
            String prompt = buildReviewPrompt(file.filename(), file.patch(), fileContent, customRepoRules);
            try {
                LlmProvider.LlmResponse response = llmRouter.generate(prompt, "review");
                inputTokens = response.inputTokens();
                outputTokens = response.outputTokens();
                parseReviewResponse(response.content(), file.filename(), request.commitSha(),
                    issues, comments, ignoredLines);
                log.debug("LLM review for {} used {} input, {} output tokens",
                    file.filename(), inputTokens, outputTokens);
            } catch (Exception e) {
                log.error("Error getting LLM review for {}", file.filename(), e);
            }
        }

        // Merge static analysis results
        try {
            List<ReviewIssue> staticIssues = staticFuture.join();
            issues.addAll(staticIssues);
        } catch (Exception e) {
            log.warn("Failed to get static analysis results for {}", file.filename(), e);
        }

        return new FileReviewResult(issues, comments, inputTokens, outputTokens);
    }

    /**
     * Generate summary for a commit review
     */
    private String generateCommitSummary(GitProvider.CommitInfo commitInfo, List<GitProvider.ChangedFile> files, List<ReviewIssue> issues) {
        String template = loadPromptTemplate("summary.txt");

        StringBuilder fileList = new StringBuilder();
        for (GitProvider.ChangedFile file : files) {
            fileList.append("- ").append(file.filename())
                .append(" (+").append(file.additions())
                .append("/-").append(file.deletions()).append(")\n");
        }

        String commitTitle = commitInfo.message().split("\n")[0];
        String prompt = template
            .replace("{{title}}", commitTitle)
            .replace("{{description}}", commitInfo.message())
            .replace("{{files}}", fileList.toString())
            .replace("{{issue_count}}", String.valueOf(issues.size()));

        try {
            LlmProvider.LlmResponse response = llmRouter.generate(prompt, "summary");
            return response.content();
        } catch (Exception e) {
            log.error("Error generating commit summary", e);
            return "Commit review completed with " + issues.size() + " issues found.";
        }
    }
}
