package com.codelens.service;

import com.codelens.core.SecretRedactor;
import com.codelens.exception.RateLimitExceededException;
import com.codelens.git.GitProvider;
import com.codelens.git.GitProvider.ChangedFile;
import com.codelens.git.GitProviderFactory;
import com.codelens.llm.LlmProvider;
import com.codelens.llm.LlmRouter;
import com.codelens.model.entity.Repository;
import com.codelens.model.entity.Review;
import com.codelens.model.entity.ReviewIssue;
import com.codelens.repository.ReviewIssueRepository;
import com.codelens.repository.ReviewRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
public class OptimizationService {

    private final ReviewRepository reviewRepository;
    private final ReviewIssueRepository issueRepository;
    private final GitProviderFactory gitProviderFactory;
    private final LlmRouter llmRouter;
    private final ResourceLoader resourceLoader;
    private final ReviewProgressService progressService;
    private final RateLimitService rateLimitService;
    private final SecretRedactor secretRedactor;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final int MAX_FILES = 30;
    private static final int MAX_LINES_PER_FILE = 800;
    private static final int PARALLEL_THREADS = 3;

    public OptimizationService(
            ReviewRepository reviewRepository,
            ReviewIssueRepository issueRepository,
            GitProviderFactory gitProviderFactory,
            LlmRouter llmRouter,
            ResourceLoader resourceLoader,
            ReviewProgressService progressService,
            RateLimitService rateLimitService,
            SecretRedactor secretRedactor) {
        this.reviewRepository = reviewRepository;
        this.issueRepository = issueRepository;
        this.gitProviderFactory = gitProviderFactory;
        this.llmRouter = llmRouter;
        this.resourceLoader = resourceLoader;
        this.progressService = progressService;
        this.rateLimitService = rateLimitService;
        this.secretRedactor = secretRedactor;
    }

    /**
     * Run optimization analysis on a completed review
     */
    @Async("reviewTaskExecutor")
    public void analyzeOptimizationsAsync(UUID reviewId) {
        try {
            analyzeOptimizations(reviewId);
        } catch (Exception e) {
            log.error("Error running optimization analysis for review {}", reviewId, e);
            updateOptimizationFailed(reviewId, e.getMessage());
        }
    }

    @Transactional
    public List<ReviewIssue> analyzeOptimizations(UUID reviewId) {
        Review review = reviewRepository.findById(reviewId)
            .orElseThrow(() -> new IllegalArgumentException("Review not found: " + reviewId));

        if (review.getStatus() != Review.ReviewStatus.COMPLETED) {
            throw new IllegalStateException("Cannot run optimization on non-completed review. Status: " + review.getStatus());
        }

        if (Boolean.TRUE.equals(review.getOptimizationCompleted())) {
            log.info("Optimization already completed for review {}", reviewId);
            return issueRepository.findByReviewIdAndCategory(reviewId, ReviewIssue.Category.OPTIMIZATION);
        }

        // Rate limit check - per user (or per organization if no user)
        UUID rateLimitKey = review.getUser() != null ? review.getUser().getId() :
                (review.getRepository() != null && review.getRepository().getOrganization() != null ?
                        review.getRepository().getOrganization().getId() : null);

        if (rateLimitKey != null && !rateLimitService.allowOptimization(rateLimitKey)) {
            throw new RateLimitExceededException("Optimization rate limit exceeded. Maximum " +
                    RateLimitService.OPTIMIZATION_REQUESTS_PER_HOUR + " requests per hour.");
        }

        log.info("Starting optimization analysis for review {}", reviewId);

        // Parse PR URL to get info
        GitProviderFactory.ParsedPrUrl parsed = gitProviderFactory.parsePrUrl(review.getPrUrl());
        GitProvider gitProvider = gitProviderFactory.getProvider(parsed.provider());

        // Check if git provider is properly initialized
        if (gitProvider instanceof com.codelens.git.github.GitHubService ghService && !ghService.isInitialized()) {
            throw new IllegalStateException("GitHub service is not initialized. Please set GITHUB_TOKEN environment variable.");
        }

        // Get changed files
        List<ChangedFile> changedFiles = gitProvider.getChangedFiles(parsed.owner(), parsed.repo(), parsed.prNumber());

        // Filter to code files only (skip configs, assets, etc.)
        List<ChangedFile> codeFiles = changedFiles.stream()
            .filter(this::isCodeFile)
            .limit(MAX_FILES)
            .toList();

        log.info("Analyzing {} code files for optimizations", codeFiles.size());

        // Start progress tracking
        final int totalFiles = codeFiles.size();
        progressService.startOptimizationProgress(reviewId, totalFiles);

        AtomicInteger totalInputTokens = new AtomicInteger(0);
        AtomicInteger totalOutputTokens = new AtomicInteger(0);

        // Use CompletableFuture to collect results - each future returns its own list
        // This avoids shared mutable state and is inherently thread-safe
        ExecutorService executor = Executors.newFixedThreadPool(PARALLEL_THREADS);
        List<CompletableFuture<List<ReviewIssue>>> futures;
        try {
            futures = codeFiles.stream()
                .map(file -> CompletableFuture.supplyAsync(() -> {
                    try {
                        // Update current file being analyzed (display only, no counter change)
                        progressService.updateOptimizationCurrentFile(reviewId, file.filename());

                        List<ReviewIssue> fileOptimizations = analyzeFileOptimizations(
                            review, gitProvider, parsed, file, totalInputTokens, totalOutputTokens);

                        // Atomically increment progress counter after file completion
                        // Uses SQL UPDATE ... SET counter = counter + 1 to prevent lost updates
                        progressService.incrementOptimizationProgress(reviewId, file.filename());

                        return fileOptimizations;
                    } catch (Exception e) {
                        log.warn("Failed to analyze optimizations for {}: {}", file.filename(), e.getMessage());
                        progressService.incrementOptimizationProgress(reviewId, file.filename());
                        return List.<ReviewIssue>of(); // Return empty list on failure
                    }
                }, executor))
                .toList();

            // Wait for all futures to complete
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        } finally {
            executor.shutdown();
        }

        // Collect all results after all futures complete - no shared mutable state
        List<ReviewIssue> allOptimizations = futures.stream()
            .map(CompletableFuture::join)
            .flatMap(List::stream)
            .collect(java.util.stream.Collectors.toList());

        // Save optimization issues
        for (ReviewIssue issue : allOptimizations) {
            issue.setReview(review);
            issueRepository.save(issue);
        }

        // Generate and save summary
        String summary = generateOptimizationSummary(allOptimizations);
        review.setOptimizationSummary(summary);
        review.setOptimizationCompleted(true);
        review.setOptimizationInProgress(false);
        review.setOptimizationCurrentFile(null);
        reviewRepository.save(review);

        // Complete progress tracking
        progressService.completeOptimizationProgress(reviewId);

        log.info("Optimization analysis completed for review {} with {} suggestions", reviewId, allOptimizations.size());

        return new ArrayList<>(allOptimizations);
    }

    private List<ReviewIssue> analyzeFileOptimizations(
            Review review,
            GitProvider gitProvider,
            GitProviderFactory.ParsedPrUrl parsed,
            ChangedFile file,
            AtomicInteger totalInputTokens,
            AtomicInteger totalOutputTokens) {

        List<ReviewIssue> optimizations = new ArrayList<>();

        // Get file content
        String fileContent = null;
        try {
            fileContent = gitProvider.getFileContent(
                parsed.owner(), parsed.repo(), file.filename(), review.getHeadCommitSha());
        } catch (Exception e) {
            log.debug("Could not get file content for {}", file.filename());
            return optimizations;
        }

        if (fileContent == null || file.patch() == null) {
            return optimizations;
        }

        // Build optimization prompt
        String prompt = buildOptimizationPrompt(file.filename(), file.patch(), fileContent);

        try {
            // Use fallback-enabled generation for reliability
            LlmProvider.LlmResponse response = llmRouter.generate(prompt, "optimization");

            totalInputTokens.addAndGet(response.inputTokens());
            totalOutputTokens.addAndGet(response.outputTokens());

            // Parse response
            parseOptimizationResponse(response.content(), file.filename(), optimizations);

        } catch (Exception e) {
            log.error("Error getting optimization analysis for {}", file.filename(), e);
        }

        return optimizations;
    }

    private String buildOptimizationPrompt(String filename, String patch, String fileContent) {
        String template = loadPromptTemplate("optimize.txt");

        // Redact secrets from code before sending to LLM
        String safePatch = patch != null ? secretRedactor.redactSecrets(patch) : "";
        String safeContent = secretRedactor.redactSecrets(truncateContent(fileContent));

        return template
            .replace("{{filename}}", filename)
            .replace("{{patch}}", safePatch)
            .replace("{{file_content}}", safeContent);
    }

    private void parseOptimizationResponse(String response, String filename, List<ReviewIssue> optimizations) {
        String json = extractJson(response);
        if (json == null || json.isBlank()) {
            log.debug("No JSON found in optimization response for {}", filename);
            return;
        }

        try {
            List<Map<String, Object>> issueList = objectMapper.readValue(
                json, new TypeReference<List<Map<String, Object>>>() {});

            for (Map<String, Object> issueData : issueList) {
                try {
                    int lineNum = ((Number) issueData.get("line")).intValue();
                    String severityStr = (String) issueData.getOrDefault("severity", "MEDIUM");
                    String categoryStr = (String) issueData.getOrDefault("category", "ALGORITHM");
                    String rule = (String) issueData.getOrDefault("rule", "optimization");
                    String message = (String) issueData.get("message");
                    String suggestion = (String) issueData.get("suggestion");
                    String existingCode = (String) issueData.get("existing_code");
                    String impact = (String) issueData.get("impact");
                    String confidence = (String) issueData.getOrDefault("confidence", "MEDIUM");

                    ReviewIssue.Severity severity = parseEnumSafe(severityStr, ReviewIssue.Severity.class, ReviewIssue.Severity.MEDIUM);

                    ReviewIssue issue = new ReviewIssue();
                    issue.setFilePath(filename);
                    issue.setLineNumber(lineNum);
                    issue.setSeverity(severity);
                    issue.setCategory(ReviewIssue.Category.OPTIMIZATION);
                    issue.setRule(mapOptimizationCategory(categoryStr) + "-" + rule);
                    issue.setDescription(message);
                    issue.setSuggestedFix(suggestion);
                    issue.setSource(ReviewIssue.Source.AI);
                    issue.setAnalyzer("optimization");

                    // Build explanation with impact and confidence
                    StringBuilder explanation = new StringBuilder();
                    if (existingCode != null) {
                        explanation.append("**Current code:**\n```\n").append(existingCode).append("\n```\n\n");
                    }
                    if (impact != null) {
                        explanation.append("**Expected impact:** ").append(impact).append("\n");
                    }
                    if (confidence != null) {
                        explanation.append("**Confidence:** ").append(confidence);
                    }
                    issue.setAiExplanation(explanation.toString());

                    optimizations.add(issue);

                } catch (Exception e) {
                    log.debug("Could not parse optimization issue: {}", issueData, e);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse optimization JSON response for {}: {}", filename, e.getMessage());
        }
    }

    private String mapOptimizationCategory(String category) {
        if (category == null) return "general";
        return switch (category.toUpperCase()) {
            case "ALGORITHM" -> "algorithm";
            case "DATABASE" -> "database";
            case "MEMORY" -> "memory";
            case "CACHING" -> "caching";
            case "CONCURRENCY" -> "concurrency";
            case "REFACTORING" -> "refactoring";
            default -> "general";
        };
    }

    public String generateOptimizationSummary(List<ReviewIssue> optimizations) {
        if (optimizations.isEmpty()) {
            return "No significant optimization opportunities were identified in this PR.";
        }

        // Count by category
        Map<String, Long> byCategoryRule = optimizations.stream()
            .collect(java.util.stream.Collectors.groupingBy(
                issue -> {
                    String rule = issue.getRule();
                    if (rule != null && rule.contains("-")) {
                        return rule.substring(0, rule.indexOf("-"));
                    }
                    return "general";
                },
                java.util.stream.Collectors.counting()
            ));

        StringBuilder summary = new StringBuilder();
        summary.append("Found ").append(optimizations.size()).append(" optimization opportunities:\n");

        byCategoryRule.entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
            .forEach(entry -> {
                String categoryLabel = switch (entry.getKey()) {
                    case "algorithm" -> "Algorithm improvements";
                    case "database" -> "Database/query optimizations";
                    case "memory" -> "Memory optimizations";
                    case "caching" -> "Caching opportunities";
                    case "concurrency" -> "Concurrency improvements";
                    case "refactoring" -> "Code structure improvements";
                    default -> "General optimizations";
                };
                summary.append("- ").append(categoryLabel).append(": ").append(entry.getValue()).append("\n");
            });

        // Add severity breakdown
        long high = optimizations.stream().filter(i -> i.getSeverity() == ReviewIssue.Severity.HIGH).count();
        long medium = optimizations.stream().filter(i -> i.getSeverity() == ReviewIssue.Severity.MEDIUM).count();
        long low = optimizations.stream().filter(i -> i.getSeverity() == ReviewIssue.Severity.LOW).count();

        summary.append("\nBy priority: ");
        if (high > 0) summary.append(high).append(" high, ");
        if (medium > 0) summary.append(medium).append(" medium, ");
        if (low > 0) summary.append(low).append(" low");

        return summary.toString().trim();
    }

    private void updateOptimizationFailed(UUID reviewId, String errorMessage) {
        reviewRepository.findById(reviewId).ifPresent(review -> {
            review.setOptimizationSummary("Optimization analysis failed: " + errorMessage);
            review.setOptimizationCompleted(true);
            review.setOptimizationInProgress(false);
            review.setOptimizationCurrentFile(null);
            reviewRepository.save(review);
        });
        progressService.completeOptimizationProgress(reviewId);
    }

    private boolean isCodeFile(ChangedFile file) {
        String filename = file.filename().toLowerCase();

        // Skip non-code files
        if (filename.endsWith(".md") || filename.endsWith(".txt") ||
            filename.endsWith(".json") || filename.endsWith(".yaml") ||
            filename.endsWith(".yml") || filename.endsWith(".xml") ||
            filename.endsWith(".lock") || filename.endsWith(".sum") ||
            filename.endsWith(".mod") || filename.contains(".min.") ||
            filename.endsWith(".svg") || filename.endsWith(".png") ||
            filename.endsWith(".jpg") || filename.endsWith(".gif") ||
            filename.endsWith(".css") || filename.endsWith(".scss") ||
            filename.endsWith(".d.ts") || filename.endsWith(".map")) {
            return false;
        }

        // Include common code files
        return filename.endsWith(".java") || filename.endsWith(".kt") ||
               filename.endsWith(".js") || filename.endsWith(".ts") ||
               filename.endsWith(".jsx") || filename.endsWith(".tsx") ||
               filename.endsWith(".py") || filename.endsWith(".go") ||
               filename.endsWith(".rs") || filename.endsWith(".rb") ||
               filename.endsWith(".php") || filename.endsWith(".cs") ||
               filename.endsWith(".cpp") || filename.endsWith(".c") ||
               filename.endsWith(".swift") || filename.endsWith(".scala") ||
               filename.endsWith(".svelte") || filename.endsWith(".vue");
    }

    private String extractJson(String response) {
        if (response.contains("```json")) {
            int start = response.indexOf("```json") + 7;
            int end = response.indexOf("```", start);
            if (end > start) {
                return response.substring(start, end).trim();
            }
        }
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
        if (response.trim().startsWith("[")) {
            return response.trim();
        }
        return null;
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

    private String truncateContent(String content) {
        String[] lines = content.split("\n");
        if (lines.length <= MAX_LINES_PER_FILE) {
            return content;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < MAX_LINES_PER_FILE; i++) {
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
            log.warn("Could not load prompt template {}", name);
            return getDefaultOptimizePrompt();
        }
    }

    private String getDefaultOptimizePrompt() {
        return """
            Analyze this code for optimization opportunities.

            File: {{filename}}

            Changes:
            {{patch}}

            Full file:
            {{file_content}}

            Look for:
            - Algorithm improvements (O(n²) → O(n), etc.)
            - Database query optimizations (N+1, missing indexes)
            - Memory improvements (object reuse, streaming)
            - Caching opportunities
            - Concurrency improvements

            Return JSON array:
            ```json
            [
              {
                "line": 42,
                "severity": "HIGH",
                "category": "ALGORITHM",
                "rule": "nested-loop",
                "message": "Nested loop can be optimized with HashMap",
                "suggestion": "Use HashMap for O(1) lookup",
                "impact": "O(n²) → O(n)"
              }
            ]
            ```

            If no optimizations, return: []
            """;
    }
}
