package com.codelens.api;

import com.codelens.api.dto.TrendResponse;
import com.codelens.repository.LlmUsageRepository;
import com.codelens.repository.ReviewIssueRepository;
import com.codelens.repository.ReviewRepository;
import com.codelens.repository.UserRepository;
import com.codelens.security.AuthenticatedUser;
import com.codelens.service.DeveloperAnalyticsService;
import com.codelens.service.LlmCostService;
import com.codelens.service.TrendAnalyticsService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/analytics")
@Validated
public class AnalyticsController {

    private final ReviewRepository reviewRepository;
    private final ReviewIssueRepository issueRepository;
    private final LlmUsageRepository llmUsageRepository;
    private final TrendAnalyticsService trendAnalyticsService;
    private final LlmCostService llmCostService;
    private final DeveloperAnalyticsService developerAnalyticsService;
    private final UserRepository userRepository;

    public AnalyticsController(
            ReviewRepository reviewRepository,
            ReviewIssueRepository issueRepository,
            LlmUsageRepository llmUsageRepository,
            TrendAnalyticsService trendAnalyticsService,
            LlmCostService llmCostService,
            DeveloperAnalyticsService developerAnalyticsService,
            UserRepository userRepository) {
        this.reviewRepository = reviewRepository;
        this.issueRepository = issueRepository;
        this.llmUsageRepository = llmUsageRepository;
        this.trendAnalyticsService = trendAnalyticsService;
        this.llmCostService = llmCostService;
        this.developerAnalyticsService = developerAnalyticsService;
        this.userRepository = userRepository;
    }

    /**
     * Get dashboard statistics
     */
    @GetMapping("/dashboard")
    public ResponseEntity<Map<String, Object>> getDashboardStats(
            @RequestParam(required = false) UUID organizationId) {

        Map<String, Object> stats = new HashMap<>();

        LocalDateTime thirtyDaysAgo = LocalDateTime.now().minusDays(30);
        LocalDateTime sevenDaysAgo = LocalDateTime.now().minusDays(7);

        // Total reviews
        long totalReviews = reviewRepository.count();
        stats.put("totalReviews", totalReviews);

        // Completed reviews
        long completedReviews = reviewRepository.countByStatus(
            com.codelens.model.entity.Review.ReviewStatus.COMPLETED);
        stats.put("completedReviews", completedReviews);

        // Completed reviews this week
        long completedThisWeek = reviewRepository.countByStatusAndCreatedAtAfter(
            com.codelens.model.entity.Review.ReviewStatus.COMPLETED, sevenDaysAgo);
        stats.put("completedThisWeek", completedThisWeek);

        // Reviews this week (all statuses)
        long reviewsThisWeek = reviewRepository.countByCreatedAtAfter(sevenDaysAgo);
        stats.put("reviewsThisWeek", reviewsThisWeek);

        // Pending reviews
        long pendingReviews = reviewRepository.countByStatus(
            com.codelens.model.entity.Review.ReviewStatus.PENDING);
        stats.put("pendingReviews", pendingReviews);

        // In progress reviews
        long inProgressReviews = reviewRepository.countByStatus(
            com.codelens.model.entity.Review.ReviewStatus.IN_PROGRESS);
        stats.put("inProgressReviews", inProgressReviews);

        // Estimated time saved (rough estimate: 15 minutes per review)
        int timeSavedHours = (int) (totalReviews * 15 / 60);
        stats.put("timeSavedHours", timeSavedHours);

        // Total issues found
        long totalIssues = issueRepository.count();
        stats.put("totalIssues", totalIssues);

        // Issues by severity
        Map<String, Long> issuesBySeverity = new HashMap<>();
        issuesBySeverity.put("critical", issueRepository.countBySeverity(
            com.codelens.model.entity.ReviewIssue.Severity.CRITICAL));
        issuesBySeverity.put("high", issueRepository.countBySeverity(
            com.codelens.model.entity.ReviewIssue.Severity.HIGH));
        issuesBySeverity.put("medium", issueRepository.countBySeverity(
            com.codelens.model.entity.ReviewIssue.Severity.MEDIUM));
        issuesBySeverity.put("low", issueRepository.countBySeverity(
            com.codelens.model.entity.ReviewIssue.Severity.LOW));
        stats.put("issuesBySeverity", issuesBySeverity);

        // LLM usage stats
        Double totalCost = llmUsageRepository.sumEstimatedCostByCreatedAtAfter(thirtyDaysAgo);
        stats.put("llmCostThisMonth", totalCost != null ? totalCost : 0.0);

        Integer totalTokens = llmUsageRepository.sumTotalTokensByCreatedAtAfter(thirtyDaysAgo);
        stats.put("tokensThisMonth", totalTokens != null ? totalTokens : 0);

        return ResponseEntity.ok(stats);
    }

    /**
     * Get review trends over time
     */
    @GetMapping("/trends")
    public ResponseEntity<Map<String, Object>> getReviewTrends(
            @RequestParam(defaultValue = "30") @Min(1) @Max(365) int days) {

        LocalDateTime startDate = LocalDateTime.now().minusDays(days);

        Map<String, Object> trends = new HashMap<>();

        // Daily review counts
        List<Object[]> dailyCounts = reviewRepository.countByDayAfter(startDate);

        // Convert to map for easy lookup
        Map<String, Long> countsByDate = new HashMap<>();
        for (Object[] row : dailyCounts) {
            if (row[0] != null) {
                String dateStr = row[0].toString();
                Long count = ((Number) row[1]).longValue();
                countsByDate.put(dateStr, count);
            }
        }

        // Fill in all days in the range with 0 for missing days
        List<List<Object>> dailyReviews = new java.util.ArrayList<>();
        java.time.LocalDate current = startDate.toLocalDate();
        java.time.LocalDate end = java.time.LocalDate.now();

        while (!current.isAfter(end)) {
            String dateStr = current.toString();
            Long count = countsByDate.getOrDefault(dateStr, 0L);
            dailyReviews.add(List.of(dateStr, count));
            current = current.plusDays(1);
        }

        trends.put("dailyReviews", dailyReviews);

        // Issue discovery rate - same treatment
        List<Object[]> dailyIssuesRaw = issueRepository.countByDayAfter(startDate);
        Map<String, Long> issuesByDate = new HashMap<>();
        for (Object[] row : dailyIssuesRaw) {
            if (row[0] != null) {
                String dateStr = row[0].toString();
                Long count = ((Number) row[1]).longValue();
                issuesByDate.put(dateStr, count);
            }
        }

        List<List<Object>> dailyIssues = new java.util.ArrayList<>();
        current = startDate.toLocalDate();
        while (!current.isAfter(end)) {
            String dateStr = current.toString();
            Long count = issuesByDate.getOrDefault(dateStr, 0L);
            dailyIssues.add(List.of(dateStr, count));
            current = current.plusDays(1);
        }

        trends.put("dailyIssues", dailyIssues);

        return ResponseEntity.ok(trends);
    }

    /**
     * Get LLM provider usage breakdown
     */
    @GetMapping("/llm-usage")
    public ResponseEntity<Map<String, Object>> getLlmUsage(
            @RequestParam(defaultValue = "30") @Min(1) @Max(365) int days) {

        try {
            LocalDateTime startDate = LocalDateTime.now().minusDays(days);

            Map<String, Object> usage = new HashMap<>();

            // Usage by provider
            List<Object[]> byProvider = llmUsageRepository.sumCostByProviderAfter(startDate);
            Map<String, Double> providerCosts = new HashMap<>();
            if (byProvider != null) {
                for (Object[] row : byProvider) {
                    if (row[0] != null) {
                        String provider = row[0].toString();
                        Double cost = row[1] != null ? ((Number) row[1]).doubleValue() : 0.0;
                        providerCosts.put(provider, cost);
                    }
                }
            }
            usage.put("costByProvider", providerCosts);

            // Total tokens
            Integer totalInput = llmUsageRepository.sumInputTokensByCreatedAtAfter(startDate);
            Integer totalOutput = llmUsageRepository.sumOutputTokensByCreatedAtAfter(startDate);
            usage.put("totalInputTokens", totalInput != null ? totalInput : 0);
            usage.put("totalOutputTokens", totalOutput != null ? totalOutput : 0);

            return ResponseEntity.ok(usage);
        } catch (DataAccessException e) {
            log.error("Database error fetching LLM usage analytics", e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", "Failed to fetch LLM usage data");
            errorResponse.put("costByProvider", new HashMap<>());
            errorResponse.put("totalInputTokens", 0);
            errorResponse.put("totalOutputTokens", 0);
            return ResponseEntity.ok(errorResponse);
        }
    }

    /**
     * Get issue category breakdown
     */
    @GetMapping("/issues")
    public ResponseEntity<Map<String, Object>> getIssueAnalytics(
            @RequestParam(defaultValue = "30") @Min(1) @Max(365) int days) {

        try {
            LocalDateTime startDate = LocalDateTime.now().minusDays(days);

            Map<String, Object> analytics = new HashMap<>();

            // Issues by source (AI vs Static)
            List<Object[]> bySource = issueRepository.countBySourceAfter(startDate);
            Map<String, Long> sourceCounts = new HashMap<>();
            if (bySource != null) {
                for (Object[] row : bySource) {
                    if (row[0] != null) {
                        String source = row[0].toString();
                        Long count = ((Number) row[1]).longValue();
                        sourceCounts.put(source, count);
                    }
                }
            }
            analytics.put("bySource", sourceCounts);

            // Issues by category
            List<Object[]> byCategory = issueRepository.countByCategoryAfter(startDate);
            Map<String, Long> categoryCounts = new HashMap<>();
            if (byCategory != null) {
                for (Object[] row : byCategory) {
                    if (row[0] != null) {
                        String category = row[0].toString();
                        Long count = ((Number) row[1]).longValue();
                        categoryCounts.put(category, count);
                    }
                }
            }
            analytics.put("byCategory", categoryCounts);

            // CVE count
            long cveCount = issueRepository.countByCveIdNotNull();
            analytics.put("cveCount", cveCount);

            return ResponseEntity.ok(analytics);
        } catch (DataAccessException e) {
            log.error("Database error fetching issue analytics", e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", "Failed to fetch issue analytics");
            errorResponse.put("bySource", new HashMap<>());
            errorResponse.put("byCategory", new HashMap<>());
            errorResponse.put("cveCount", 0);
            return ResponseEntity.ok(errorResponse);
        }
    }

    /**
     * Get sidebar quick stats
     */
    @GetMapping("/sidebar-stats")
    public ResponseEntity<Map<String, Object>> getSidebarStats() {
        Map<String, Object> stats = new HashMap<>();

        LocalDateTime startOfToday = LocalDateTime.now().withHour(0).withMinute(0).withSecond(0);

        // Reviews today
        long reviewsToday = reviewRepository.countByCreatedAtAfter(startOfToday);
        stats.put("reviewsToday", reviewsToday);

        // Daily goal (configurable, default 16)
        int dailyGoal = 16;
        stats.put("dailyGoal", dailyGoal);

        // Progress percentage
        int progress = dailyGoal > 0 ? (int) Math.min(100, (reviewsToday * 100) / dailyGoal) : 0;
        stats.put("progressPercent", progress);

        // Pending reviews count (for badge)
        long pendingCount = reviewRepository.countByStatus(
            com.codelens.model.entity.Review.ReviewStatus.PENDING);
        stats.put("pendingCount", pendingCount);

        return ResponseEntity.ok(stats);
    }

    /**
     * Get recent activity feed
     */
    @GetMapping("/activity")
    public ResponseEntity<List<Map<String, Object>>> getRecentActivity(
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int limit) {

        List<com.codelens.model.entity.Review> recentReviews = reviewRepository.findTop10ByOrderByCreatedAtDesc();

        List<Map<String, Object>> activities = recentReviews.stream()
            .limit(limit)
            .map(review -> {
                Map<String, Object> activity = new HashMap<>();

                // Get user info - prefer logged-in user, fall back to PR author
                if (review.getUser() != null) {
                    activity.put("userName", review.getUser().getName());
                    activity.put("userAvatar", review.getUser().getAvatarUrl());
                } else if (review.getPrAuthor() != null && !review.getPrAuthor().isEmpty()) {
                    activity.put("userName", review.getPrAuthor());
                    activity.put("userAvatar", null);
                } else {
                    activity.put("userName", "Unknown");
                    activity.put("userAvatar", null);
                }

                // Determine action based on review status
                String action = switch (review.getStatus()) {
                    case COMPLETED -> "completed review of PR #" + review.getPrNumber();
                    case IN_PROGRESS -> "started reviewing PR #" + review.getPrNumber();
                    case PENDING -> "submitted PR #" + review.getPrNumber();
                    case FAILED -> "review failed for PR #" + review.getPrNumber();
                    case CANCELLED -> "cancelled review of PR #" + review.getPrNumber();
                };
                activity.put("action", action);

                // Format time ago
                activity.put("timestamp", review.getCreatedAt().toString());
                activity.put("timeAgo", formatTimeAgo(review.getCreatedAt()));

                return activity;
            })
            .toList();

        return ResponseEntity.ok(activities);
    }

    /**
     * Get top repositories by review count
     */
    @GetMapping("/top-repositories")
    public ResponseEntity<List<Map<String, Object>>> getTopRepositories(
            @RequestParam(defaultValue = "30") @Min(1) @Max(365) int days,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int limit) {

        try {
            LocalDateTime startDate = LocalDateTime.now().minusDays(days);

            List<Object[]> topRepos = reviewRepository.findTopRepositoriesByReviewCount(startDate, limit);

            List<Map<String, Object>> result = topRepos.stream()
                .map(row -> {
                    Map<String, Object> repo = new HashMap<>();
                    repo.put("repositoryName", row[0] != null ? row[0].toString() : "Unknown");
                    repo.put("reviewCount", ((Number) row[1]).longValue());
                    repo.put("issueCount", row[2] != null ? ((Number) row[2]).longValue() : 0L);
                    repo.put("criticalCount", row[3] != null ? ((Number) row[3]).longValue() : 0L);
                    return repo;
                })
                .toList();

            return ResponseEntity.ok(result);
        } catch (DataAccessException e) {
            log.error("Database error fetching top repositories", e);
            return ResponseEntity.ok(List.of());
        }
    }

    /**
     * Get top issue types/categories
     */
    @GetMapping("/top-issue-types")
    public ResponseEntity<List<Map<String, Object>>> getTopIssueTypes(
            @RequestParam(defaultValue = "30") @Min(1) @Max(365) int days,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int limit) {

        try {
            LocalDateTime startDate = LocalDateTime.now().minusDays(days);

            List<Object[]> topTypes = issueRepository.findTopCategoriesByCount(startDate, limit);

            List<Map<String, Object>> result = topTypes.stream()
                .map(row -> {
                    Map<String, Object> issueType = new HashMap<>();
                    issueType.put("category", row[0] != null ? row[0].toString() : "Unknown");
                    issueType.put("count", ((Number) row[1]).longValue());
                    return issueType;
                })
                .toList();

            return ResponseEntity.ok(result);
        } catch (DataAccessException e) {
            log.error("Database error fetching top issue types", e);
            return ResponseEntity.ok(List.of());
        }
    }

    // ============ Trend Analytics ============

    /**
     * Get monthly trend analytics for an organization.
     * Returns review counts, issue trends, and quality indicators over time.
     */
    @GetMapping("/trends/organization/{orgId}")
    public ResponseEntity<TrendResponse> getOrganizationTrends(
            @PathVariable UUID orgId,
            @RequestParam(defaultValue = "6") @Min(1) @Max(24) int months) {
        try {
            TrendResponse trends = trendAnalyticsService.getOrganizationTrends(orgId, months);
            return ResponseEntity.ok(trends);
        } catch (DataAccessException e) {
            log.error("Database error fetching organization trends for {}", orgId, e);
            return ResponseEntity.ok(TrendResponse.builder()
                    .monthlyData(List.of())
                    .trend("error")
                    .totalReviews(0L)
                    .totalCriticalIssues(0L)
                    .averageIssuesPerReview(0.0)
                    .build());
        }
    }

    /**
     * Get monthly trend analytics for the current user.
     */
    @GetMapping("/trends/me")
    public ResponseEntity<TrendResponse> getMyTrends(
            @AuthenticationPrincipal AuthenticatedUser auth,
            @RequestParam(defaultValue = "6") @Min(1) @Max(24) int months) {
        if (auth == null) {
            return ResponseEntity.status(401).build();
        }

        try {
            var user = userRepository.findByEmail(auth.email());
            if (user.isEmpty()) {
                return ResponseEntity.notFound().build();
            }

            TrendResponse trends = trendAnalyticsService.getUserTrends(user.get().getId(), months);
            return ResponseEntity.ok(trends);
        } catch (DataAccessException e) {
            log.error("Database error fetching user trends", e);
            return ResponseEntity.ok(TrendResponse.builder()
                    .monthlyData(List.of())
                    .trend("error")
                    .totalReviews(0L)
                    .totalCriticalIssues(0L)
                    .averageIssuesPerReview(0.0)
                    .build());
        }
    }

    /**
     * Get monthly trend analytics for ALL reviews (admin/global view).
     * No filtering by user or organization.
     */
    @GetMapping("/trends/all")
    public ResponseEntity<TrendResponse> getAllTrends(
            @RequestParam(defaultValue = "6") @Min(1) @Max(24) int months) {
        try {
            TrendResponse trends = trendAnalyticsService.getAllTrends(months);
            return ResponseEntity.ok(trends);
        } catch (DataAccessException e) {
            log.error("Database error fetching all trends", e);
            return ResponseEntity.ok(TrendResponse.builder()
                    .monthlyData(List.of())
                    .trend("error")
                    .totalReviews(0L)
                    .totalCriticalIssues(0L)
                    .averageIssuesPerReview(0.0)
                    .build());
        }
    }

    /**
     * Get issue category breakdown for an organization.
     */
    @GetMapping("/trends/organization/{orgId}/categories")
    public ResponseEntity<Map<String, Long>> getOrganizationCategoryBreakdown(
            @PathVariable UUID orgId,
            @RequestParam(defaultValue = "6") @Min(1) @Max(24) int months) {
        try {
            Map<String, Long> breakdown = trendAnalyticsService.getCategoryBreakdown(orgId, months);
            return ResponseEntity.ok(breakdown);
        } catch (DataAccessException e) {
            log.error("Database error fetching category breakdown for {}", orgId, e);
            return ResponseEntity.ok(Map.of());
        }
    }

    // ============ LLM Cost Quota ============

    /**
     * Get current LLM cost quota status.
     * Shows today's usage, remaining budget, and whether limit is enforced.
     */
    @GetMapping("/llm-quota")
    public ResponseEntity<LlmCostService.QuotaStatus> getLlmQuotaStatus() {
        return ResponseEntity.ok(llmCostService.getQuotaStatus());
    }

    private String formatTimeAgo(LocalDateTime dateTime) {
        long minutes = java.time.Duration.between(dateTime, LocalDateTime.now()).toMinutes();
        if (minutes < 1) return "Just now";
        if (minutes < 60) return minutes + " mins ago";
        long hours = minutes / 60;
        if (hours < 24) return hours + " hours ago";
        long days = hours / 24;
        return days + " days ago";
    }

    // ============ Developer Activity Analytics ============

    /**
     * Get developer leaderboard with review statistics.
     */
    @GetMapping("/developers/leaderboard")
    public ResponseEntity<List<DeveloperAnalyticsService.DeveloperStats>> getDeveloperLeaderboard(
            @RequestParam(defaultValue = "30") @Min(1) @Max(365) int days,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int limit) {
        try {
            List<DeveloperAnalyticsService.DeveloperStats> leaderboard =
                    developerAnalyticsService.getLeaderboard(days, limit);
            return ResponseEntity.ok(leaderboard);
        } catch (DataAccessException e) {
            log.error("Database error fetching developer leaderboard", e);
            return ResponseEntity.ok(List.of());
        }
    }

    /**
     * Get stats for a specific developer.
     */
    @GetMapping("/developers/{userId}")
    public ResponseEntity<DeveloperAnalyticsService.DeveloperStats> getDeveloperStats(
            @PathVariable UUID userId,
            @RequestParam(defaultValue = "30") @Min(1) @Max(365) int days) {
        try {
            DeveloperAnalyticsService.DeveloperStats stats =
                    developerAnalyticsService.getDeveloperStats(userId, days);
            return ResponseEntity.ok(stats);
        } catch (DataAccessException e) {
            log.error("Database error fetching developer stats for {}", userId, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get stats for the current logged-in user.
     */
    @GetMapping("/developers/me")
    public ResponseEntity<DeveloperAnalyticsService.DeveloperStats> getMyDeveloperStats(
            @AuthenticationPrincipal AuthenticatedUser auth,
            @RequestParam(defaultValue = "30") @Min(1) @Max(365) int days) {
        if (auth == null) {
            return ResponseEntity.status(401).build();
        }

        try {
            var user = userRepository.findByEmail(auth.email());
            if (user.isEmpty()) {
                return ResponseEntity.notFound().build();
            }

            DeveloperAnalyticsService.DeveloperStats stats =
                    developerAnalyticsService.getDeveloperStats(user.get().getId(), days);
            return ResponseEntity.ok(stats);
        } catch (DataAccessException e) {
            log.error("Database error fetching current user stats", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get daily activity for a developer (for charts).
     */
    @GetMapping("/developers/{userId}/activity")
    public ResponseEntity<List<DeveloperAnalyticsService.DailyActivity>> getDeveloperActivity(
            @PathVariable UUID userId,
            @RequestParam(defaultValue = "30") @Min(1) @Max(365) int days) {
        try {
            List<DeveloperAnalyticsService.DailyActivity> activity =
                    developerAnalyticsService.getDeveloperDailyActivity(userId, days);
            return ResponseEntity.ok(activity);
        } catch (DataAccessException e) {
            log.error("Database error fetching developer activity for {}", userId, e);
            return ResponseEntity.ok(List.of());
        }
    }

    /**
     * Get summary stats for all developers.
     */
    @GetMapping("/developers/summary")
    public ResponseEntity<DeveloperAnalyticsService.SummaryStats> getDeveloperSummary(
            @RequestParam(defaultValue = "30") @Min(1) @Max(365) int days) {
        try {
            DeveloperAnalyticsService.SummaryStats summary =
                    developerAnalyticsService.getSummaryStats(days);
            return ResponseEntity.ok(summary);
        } catch (DataAccessException e) {
            log.error("Database error fetching developer summary", e);
            return ResponseEntity.ok(new DeveloperAnalyticsService.SummaryStats(0L, 0L, 0L, 0L, 0.0));
        }
    }

    /**
     * Get PR size distribution.
     */
    @GetMapping("/developers/pr-sizes")
    public ResponseEntity<List<DeveloperAnalyticsService.SizeDistribution>> getPrSizeDistribution(
            @RequestParam(defaultValue = "30") @Min(1) @Max(365) int days) {
        try {
            List<DeveloperAnalyticsService.SizeDistribution> distribution =
                    developerAnalyticsService.getPrSizeDistribution(days);
            return ResponseEntity.ok(distribution);
        } catch (DataAccessException e) {
            log.error("Database error fetching PR size distribution", e);
            return ResponseEntity.ok(List.of());
        }
    }

    /**
     * Get review cycle time trend.
     */
    @GetMapping("/developers/cycle-time")
    public ResponseEntity<List<DeveloperAnalyticsService.CycleTimeTrend>> getCycleTimeTrend(
            @RequestParam(defaultValue = "30") @Min(1) @Max(365) int days) {
        try {
            List<DeveloperAnalyticsService.CycleTimeTrend> trend =
                    developerAnalyticsService.getCycleTimeTrend(days);
            return ResponseEntity.ok(trend);
        } catch (DataAccessException e) {
            log.error("Database error fetching cycle time trend", e);
            return ResponseEntity.ok(List.of());
        }
    }

    /**
     * Get feedback statistics (false positive rate, helpful rate).
     */
    @GetMapping("/developers/feedback")
    public ResponseEntity<DeveloperAnalyticsService.FeedbackStats> getFeedbackStats(
            @RequestParam(defaultValue = "30") @Min(1) @Max(365) int days) {
        try {
            DeveloperAnalyticsService.FeedbackStats stats =
                    developerAnalyticsService.getFeedbackStats(days);
            return ResponseEntity.ok(stats);
        } catch (DataAccessException e) {
            log.error("Database error fetching feedback stats", e);
            return ResponseEntity.ok(new DeveloperAnalyticsService.FeedbackStats(0L, 0L, 0L, 0.0, 0.0));
        }
    }

    /**
     * Get AI-generated weekly summary for the current user.
     */
    @GetMapping("/developers/me/weekly-summary")
    public ResponseEntity<DeveloperAnalyticsService.WeeklySummary> getMyWeeklySummary(
            @AuthenticationPrincipal AuthenticatedUser auth,
            @RequestParam(defaultValue = "7") @Min(1) @Max(30) int days) {
        if (auth == null) {
            return ResponseEntity.status(401).build();
        }

        try {
            var user = userRepository.findByEmail(auth.email());
            if (user.isEmpty()) {
                return ResponseEntity.notFound().build();
            }

            String userName = auth.name() != null ? auth.name() : user.get().getName();
            DeveloperAnalyticsService.WeeklySummary summary =
                    developerAnalyticsService.generateWeeklySummary(user.get().getId(), userName, days);
            return ResponseEntity.ok(summary);
        } catch (Exception e) {
            log.error("Error generating weekly summary for user {}", auth.email(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get AI-generated weekly summary for a specific developer.
     */
    @GetMapping("/developers/{userId}/weekly-summary")
    public ResponseEntity<DeveloperAnalyticsService.WeeklySummary> getDeveloperWeeklySummary(
            @PathVariable UUID userId,
            @RequestParam(defaultValue = "7") @Min(1) @Max(30) int days) {
        try {
            var user = userRepository.findById(userId);
            if (user.isEmpty()) {
                return ResponseEntity.notFound().build();
            }

            DeveloperAnalyticsService.WeeklySummary summary =
                    developerAnalyticsService.generateWeeklySummary(userId, user.get().getName(), days);
            return ResponseEntity.ok(summary);
        } catch (Exception e) {
            log.error("Error generating weekly summary for user {}", userId, e);
            return ResponseEntity.internalServerError().build();
        }
    }
}
