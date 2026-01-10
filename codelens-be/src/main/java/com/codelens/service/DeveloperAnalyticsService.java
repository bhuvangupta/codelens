package com.codelens.service;

import com.codelens.llm.LlmRouter;
import com.codelens.model.entity.Review;
import com.codelens.repository.ReviewIssueRepository;
import com.codelens.repository.ReviewRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeveloperAnalyticsService {

    private final ReviewRepository reviewRepository;
    private final ReviewIssueRepository issueRepository;
    private final LlmRouter llmRouter;

    // Cache for weekly summaries (key: "userId:days", value: CachedSummary)
    private final Map<String, CachedSummary> summaryCache = new ConcurrentHashMap<>();
    private static final long CACHE_TTL_HOURS = 24;

    private record CachedSummary(WeeklySummary summary, LocalDateTime cachedAt) {
        boolean isExpired() {
            return LocalDateTime.now().isAfter(cachedAt.plusHours(CACHE_TTL_HOURS));
        }
    }

    /**
     * Get developer leaderboard with review statistics.
     */
    public List<DeveloperStats> getLeaderboard(int days, int limit) {
        LocalDateTime since = LocalDateTime.now().minusDays(days);
        List<Object[]> results = reviewRepository.getDeveloperLeaderboard(since, limit);

        List<DeveloperStats> leaderboard = new ArrayList<>();
        int rank = 1;
        for (Object[] row : results) {
            leaderboard.add(DeveloperStats.builder()
                    .rank(rank++)
                    .userId(bytesToUuid((byte[]) row[0]))
                    .userName(row[1] != null ? row[1].toString() : "Unknown")
                    .userEmail(row[2] != null ? row[2].toString() : null)
                    .avatarUrl(row[3] != null ? row[3].toString() : null)
                    .reviewCount(((Number) row[4]).longValue())
                    .linesReviewed(((Number) row[5]).longValue())
                    .issuesFound(((Number) row[6]).longValue())
                    .avgIssuesPerReview(((Number) row[7]).doubleValue())
                    .criticalIssues(((Number) row[8]).longValue())
                    .avgCycleTimeSeconds(((Number) row[9]).doubleValue())
                    .build());
        }
        return leaderboard;
    }

    /**
     * Get stats for a specific developer.
     */
    public DeveloperStats getDeveloperStats(UUID userId, int days) {
        LocalDateTime since = LocalDateTime.now().minusDays(days);
        List<Object[]> results = reviewRepository.getDeveloperStats(userId, since);

        if (results == null || results.isEmpty() || results.get(0)[0] == null) {
            return DeveloperStats.builder()
                    .userId(userId)
                    .reviewCount(0L)
                    .linesReviewed(0L)
                    .issuesFound(0L)
                    .avgIssuesPerReview(0.0)
                    .criticalIssues(0L)
                    .highIssues(0L)
                    .avgCycleTimeSeconds(0.0)
                    .repositoriesReviewed(0L)
                    .build();
        }

        Object[] row = results.get(0);
        return DeveloperStats.builder()
                .userId(userId)
                .reviewCount(((Number) row[0]).longValue())
                .linesReviewed(((Number) row[1]).longValue())
                .issuesFound(((Number) row[2]).longValue())
                .avgIssuesPerReview(((Number) row[3]).doubleValue())
                .criticalIssues(((Number) row[4]).longValue())
                .highIssues(((Number) row[5]).longValue())
                .avgCycleTimeSeconds(((Number) row[6]).doubleValue())
                .repositoriesReviewed(((Number) row[7]).longValue())
                .build();
    }

    /**
     * Get daily activity for a developer (for charts).
     */
    public List<DailyActivity> getDeveloperDailyActivity(UUID userId, int days) {
        LocalDateTime since = LocalDateTime.now().minusDays(days);
        List<Object[]> results = reviewRepository.getDeveloperDailyActivity(userId, since);

        Map<String, DailyActivity> activityByDate = new LinkedHashMap<>();

        // Fill in all days with zeros first
        java.time.LocalDate current = since.toLocalDate();
        java.time.LocalDate end = java.time.LocalDate.now();
        while (!current.isAfter(end)) {
            activityByDate.put(current.toString(), new DailyActivity(current.toString(), 0L, 0L));
            current = current.plusDays(1);
        }

        // Fill in actual data
        for (Object[] row : results) {
            String date = row[0].toString();
            activityByDate.put(date, new DailyActivity(
                    date,
                    ((Number) row[1]).longValue(),
                    ((Number) row[2]).longValue()
            ));
        }

        return new ArrayList<>(activityByDate.values());
    }

    /**
     * Get PR size distribution.
     */
    public List<SizeDistribution> getPrSizeDistribution(int days) {
        LocalDateTime since = LocalDateTime.now().minusDays(days);
        List<Object[]> results = reviewRepository.getPrSizeDistribution(since);

        return results.stream()
                .map(row -> new SizeDistribution(
                        row[0].toString(),
                        ((Number) row[1]).longValue()
                ))
                .toList();
    }

    /**
     * Get PR size distribution for a specific user.
     */
    public List<SizeDistribution> getPrSizeDistributionByUser(UUID userId, int days) {
        LocalDateTime since = LocalDateTime.now().minusDays(days);
        List<Object[]> results = reviewRepository.getPrSizeDistributionByUser(userId, since);

        return results.stream()
                .map(row -> new SizeDistribution(
                        row[0].toString(),
                        ((Number) row[1]).longValue()
                ))
                .toList();
    }

    /**
     * Get average cycle time trend.
     */
    public List<CycleTimeTrend> getCycleTimeTrend(int days) {
        LocalDateTime since = LocalDateTime.now().minusDays(days);
        List<Object[]> results = reviewRepository.getAverageCycleTimeByDay(since);

        return results.stream()
                .map(row -> new CycleTimeTrend(
                        row[0].toString(),
                        row[1] != null ? ((Number) row[1]).doubleValue() : 0.0
                ))
                .toList();
    }

    /**
     * Get summary stats for all developers.
     */
    public SummaryStats getSummaryStats(int days) {
        LocalDateTime since = LocalDateTime.now().minusDays(days);
        List<Object[]> results = reviewRepository.getDeveloperSummaryStats(since);

        if (results == null || results.isEmpty() || results.get(0)[0] == null) {
            return new SummaryStats(0L, 0L, 0L, 0L, 0.0);
        }

        Object[] row = results.get(0);
        return new SummaryStats(
                ((Number) row[0]).longValue(),
                ((Number) row[1]).longValue(),
                ((Number) row[2]).longValue(),
                ((Number) row[3]).longValue(),
                ((Number) row[4]).doubleValue()
        );
    }

    /**
     * Get false positive rate from feedback data.
     */
    public FeedbackStats getFeedbackStats(int days) {
        LocalDateTime since = LocalDateTime.now().minusDays(days);

        long totalWithFeedback = issueRepository.countByFeedbackAtAfter(since);
        long falsePositives = issueRepository.countByIsFalsePositiveTrueAndFeedbackAtAfter(since);
        long helpful = issueRepository.countByIsHelpfulTrueAndFeedbackAtAfter(since);

        double fpRate = totalWithFeedback > 0 ? (double) falsePositives / totalWithFeedback * 100 : 0;
        double helpfulRate = totalWithFeedback > 0 ? (double) helpful / totalWithFeedback * 100 : 0;

        return new FeedbackStats(totalWithFeedback, falsePositives, helpful, fpRate, helpfulRate);
    }

    private UUID bytesToUuid(byte[] bytes) {
        if (bytes == null || bytes.length != 16) return null;
        long msb = 0, lsb = 0;
        for (int i = 0; i < 8; i++) msb = (msb << 8) | (bytes[i] & 0xff);
        for (int i = 8; i < 16; i++) lsb = (lsb << 8) | (bytes[i] & 0xff);
        return new UUID(msb, lsb);
    }

    // ============ AI Weekly Summary ============

    /**
     * Generate an AI-powered weekly summary for a developer.
     * Results are cached for 24 hours to avoid repeated LLM calls.
     */
    public WeeklySummary generateWeeklySummary(UUID userId, String userName, int days) {
        // Check cache first
        String cacheKey = userId.toString() + ":" + days;
        CachedSummary cached = summaryCache.get(cacheKey);
        if (cached != null && !cached.isExpired()) {
            log.debug("Returning cached weekly summary for user {}", userId);
            return cached.summary();
        }

        LocalDateTime since = LocalDateTime.now().minusDays(days);
        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(days);
        DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("MMM d");

        // Gather data
        List<Review> reviews = reviewRepository.findByUserIdAndCreatedAtAfterCompleted(userId, since);
        DeveloperStats stats = getDeveloperStats(userId, days);
        List<Object[]> issueCategories = reviewRepository.getIssueCategoriesForUser(userId, since);
        List<Object[]> topRepos = reviewRepository.getTopRepositoriesForUser(userId, since, 5);

        // If no reviews, return empty summary (also cached)
        if (reviews.isEmpty()) {
            WeeklySummary emptySummary = new WeeklySummary(
                    userName,
                    startDate.format(dateFormatter) + " - " + endDate.format(dateFormatter),
                    "No code reviews completed during this period.",
                    List.of(),
                    List.of(),
                    null
            );
            summaryCache.put(cacheKey, new CachedSummary(emptySummary, LocalDateTime.now()));
            return emptySummary;
        }

        // Build data for prompt
        String periodString = startDate.format(dateFormatter) + " - " + endDate.format(dateFormatter);

        // Extract PR titles
        List<String> prTitles = reviews.stream()
                .map(Review::getPrTitle)
                .filter(Objects::nonNull)
                .limit(10)
                .toList();

        // Format issue categories
        Map<String, Long> categoryMap = new LinkedHashMap<>();
        for (Object[] row : issueCategories) {
            String category = row[0] != null ? row[0].toString() : "OTHER";
            long count = ((Number) row[1]).longValue();
            categoryMap.put(category, count);
        }

        // Format repositories
        List<String> repositories = topRepos.stream()
                .map(row -> row[0] != null ? row[0].toString() : "Unknown")
                .toList();

        // Build the prompt
        String prompt = buildWeeklySummaryPrompt(userName, periodString, stats, categoryMap, repositories, prTitles);

        // Generate AI summary
        String aiSummary;
        try {
            var response = llmRouter.generate(prompt, "summary");
            aiSummary = response.content();
        } catch (Exception e) {
            log.error("Failed to generate AI summary for user {}: {}", userId, e.getMessage());
            aiSummary = buildFallbackSummary(userName, periodString, stats, categoryMap, repositories);
        }

        // Build highlights list
        List<String> highlights = new ArrayList<>();
        highlights.add(String.format("Reviewed %d PRs across %d repositories",
                stats.reviewCount(), repositories.size()));
        highlights.add(String.format("Found %d issues (%d critical)",
                stats.issuesFound(), stats.criticalIssues()));
        if (stats.avgCycleTimeSeconds() > 0) {
            highlights.add(String.format("Average review time: %s", formatTime(stats.avgCycleTimeSeconds())));
        }

        // Build category breakdown
        List<CategoryBreakdown> categories = categoryMap.entrySet().stream()
                .map(e -> new CategoryBreakdown(e.getKey(), e.getValue()))
                .toList();

        WeeklySummary summary = new WeeklySummary(userName, periodString, aiSummary, highlights, categories, repositories.isEmpty() ? null : repositories.get(0));

        // Cache the result
        summaryCache.put(cacheKey, new CachedSummary(summary, LocalDateTime.now()));
        log.debug("Cached weekly summary for user {} (cache size: {})", userId, summaryCache.size());

        // Clean up expired entries periodically (simple cleanup)
        if (summaryCache.size() > 100) {
            summaryCache.entrySet().removeIf(entry -> entry.getValue().isExpired());
        }

        return summary;
    }

    private String buildWeeklySummaryPrompt(String userName, String period, DeveloperStats stats,
                                            Map<String, Long> categories, List<String> repos, List<String> prTitles) {
        StringBuilder sb = new StringBuilder();
        sb.append("Generate a brief, professional weekly code review summary for a developer. ");
        sb.append("Write in third person and be concise (3-4 sentences max). ");
        sb.append("Highlight notable achievements and areas of focus.\n\n");

        sb.append("Developer: ").append(userName).append("\n");
        sb.append("Period: ").append(period).append("\n\n");

        sb.append("Statistics:\n");
        sb.append("- PRs reviewed: ").append(stats.reviewCount()).append("\n");
        sb.append("- Lines reviewed: ").append(formatNumber(stats.linesReviewed())).append("\n");
        sb.append("- Issues found: ").append(stats.issuesFound()).append("\n");
        sb.append("- Critical issues: ").append(stats.criticalIssues()).append("\n");
        sb.append("- Repositories: ").append(repos.size()).append("\n\n");

        if (!categories.isEmpty()) {
            sb.append("Issue categories found:\n");
            categories.forEach((cat, count) -> sb.append("- ").append(cat).append(": ").append(count).append("\n"));
            sb.append("\n");
        }

        if (!repos.isEmpty()) {
            sb.append("Repositories reviewed: ").append(String.join(", ", repos)).append("\n\n");
        }

        if (!prTitles.isEmpty()) {
            sb.append("Sample PR titles reviewed:\n");
            prTitles.forEach(title -> sb.append("- ").append(title).append("\n"));
        }

        return sb.toString();
    }

    private String buildFallbackSummary(String userName, String period, DeveloperStats stats,
                                        Map<String, Long> categories, List<String> repos) {
        StringBuilder sb = new StringBuilder();
        sb.append(userName).append(" completed ").append(stats.reviewCount());
        sb.append(" code reviews during ").append(period);
        sb.append(", analyzing ").append(formatNumber(stats.linesReviewed())).append(" lines of code");

        if (!repos.isEmpty()) {
            sb.append(" across ").append(repos.size()).append(" repositories");
        }

        sb.append(". Found ").append(stats.issuesFound()).append(" issues");
        if (stats.criticalIssues() > 0) {
            sb.append(" including ").append(stats.criticalIssues()).append(" critical");
        }
        sb.append(".");

        if (!categories.isEmpty()) {
            String topCategory = categories.keySet().iterator().next();
            sb.append(" Primary focus area: ").append(topCategory.toLowerCase()).append(" issues.");
        }

        return sb.toString();
    }

    private String formatNumber(Long num) {
        if (num == null) return "0";
        if (num >= 1000000) return String.format("%.1fM", num / 1000000.0);
        if (num >= 1000) return String.format("%.1fK", num / 1000.0);
        return num.toString();
    }

    private String formatTime(Double seconds) {
        if (seconds == null || seconds <= 0) return "N/A";
        if (seconds < 60) return Math.round(seconds) + "s";
        if (seconds < 3600) return Math.round(seconds / 60) + "m";
        return String.format("%.1fh", seconds / 3600);
    }

    // DTOs
    @lombok.Builder
    public record DeveloperStats(
            Integer rank,
            UUID userId,
            String userName,
            String userEmail,
            String avatarUrl,
            Long reviewCount,
            Long linesReviewed,
            Long issuesFound,
            Double avgIssuesPerReview,
            Long criticalIssues,
            Long highIssues,
            Double avgCycleTimeSeconds,
            Long repositoriesReviewed
    ) {}

    public record DailyActivity(String date, Long reviewCount, Long linesReviewed) {}

    public record SizeDistribution(String category, Long count) {}

    public record CycleTimeTrend(String date, Double avgCycleTimeSeconds) {}

    public record SummaryStats(
            Long totalDevelopers,
            Long totalReviews,
            Long totalLinesReviewed,
            Long totalIssuesFound,
            Double avgCycleTimeSeconds
    ) {}

    public record FeedbackStats(
            Long totalWithFeedback,
            Long falsePositives,
            Long helpful,
            Double falsePositiveRate,
            Double helpfulRate
    ) {}

    public record WeeklySummary(
            String developerName,
            String period,
            String summary,
            List<String> highlights,
            List<CategoryBreakdown> issueCategories,
            String primaryRepository
    ) {}

    public record CategoryBreakdown(String category, Long count) {}
}
