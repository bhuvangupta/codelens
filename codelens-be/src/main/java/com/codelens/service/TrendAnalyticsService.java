package com.codelens.service;

import com.codelens.api.dto.TrendResponse;
import com.codelens.repository.MonthlyMetrics;
import com.codelens.repository.ReviewRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Service for computing review trend analytics.
 * Provides insights into code quality trends over time.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TrendAnalyticsService {

    private final ReviewRepository reviewRepository;

    /**
     * Get trend analytics for an organization.
     *
     * @param orgId  Organization ID
     * @param months Number of months to analyze (default 6)
     * @return TrendResponse with monthly metrics and trend indicators
     */
    @Transactional(readOnly = true)
    public TrendResponse getOrganizationTrends(UUID orgId, int months) {
        LocalDateTime since = LocalDateTime.now().minusMonths(months);
        List<MonthlyMetrics> monthlyData = reviewRepository.getMonthlyTrend(orgId, since);

        return buildTrendResponse(monthlyData, months);
    }

    /**
     * Get trend analytics for a specific user.
     *
     * @param userId User ID
     * @param months Number of months to analyze
     * @return TrendResponse with monthly metrics and trend indicators
     */
    @Transactional(readOnly = true)
    public TrendResponse getUserTrends(UUID userId, int months) {
        LocalDateTime since = LocalDateTime.now().minusMonths(months);
        List<MonthlyMetrics> monthlyData = reviewRepository.getMonthlyTrendByUser(userId, since);

        return buildTrendResponse(monthlyData, months);
    }

    /**
     * Get trend analytics for ALL reviews (admin view).
     * No filtering by user or organization.
     *
     * @param months Number of months to analyze
     * @return TrendResponse with monthly metrics and trend indicators
     */
    @Transactional(readOnly = true)
    public TrendResponse getAllTrends(int months) {
        LocalDateTime since = LocalDateTime.now().minusMonths(months);
        List<MonthlyMetrics> monthlyData = reviewRepository.getMonthlyTrendAll(since);

        return buildTrendResponse(monthlyData, months);
    }

    /**
     * Get issue category breakdown for an organization.
     *
     * @param orgId  Organization ID
     * @param months Number of months to analyze
     * @return Map of category to count
     */
    @Transactional(readOnly = true)
    public Map<String, Long> getCategoryBreakdown(UUID orgId, int months) {
        LocalDateTime since = LocalDateTime.now().minusMonths(months);
        List<Object[]> results = reviewRepository.getIssueCategoryBreakdown(orgId, since);

        Map<String, Long> breakdown = new HashMap<>();
        for (Object[] row : results) {
            String category = (String) row[0];
            Long count = ((Number) row[1]).longValue();
            breakdown.put(category, count);
        }
        return breakdown;
    }

    private TrendResponse buildTrendResponse(List<MonthlyMetrics> monthlyData, int months) {
        if (monthlyData.isEmpty()) {
            return TrendResponse.builder()
                    .monthlyData(List.of())
                    .trend("insufficient_data")
                    .totalReviews(0L)
                    .totalCriticalIssues(0L)
                    .averageIssuesPerReview(0.0)
                    .build();
        }

        // Calculate totals
        long totalReviews = monthlyData.stream()
                .mapToLong(MonthlyMetrics::getReviewCount)
                .sum();

        long totalCritical = monthlyData.stream()
                .mapToLong(MonthlyMetrics::getCriticalIssues)
                .sum();

        double avgIssues = monthlyData.stream()
                .mapToDouble(m -> m.getAvgIssues() * m.getReviewCount())
                .sum() / Math.max(totalReviews, 1);

        // Calculate trend
        String trend = calculateTrend(monthlyData);

        return TrendResponse.builder()
                .monthlyData(monthlyData.stream()
                        .map(m -> new TrendResponse.MonthData(
                                m.getMonth(),
                                m.getReviewCount(),
                                m.getAvgIssues(),
                                m.getCriticalIssues(),
                                m.getHighIssues()))
                        .toList())
                .trend(trend)
                .totalReviews(totalReviews)
                .totalCriticalIssues(totalCritical)
                .averageIssuesPerReview(Math.round(avgIssues * 100.0) / 100.0)
                .build();
    }

    /**
     * Calculate trend direction based on critical issues.
     * Compares recent period (last 1/3 of data) vs earlier period (first 1/3).
     *
     * @return "improving" if critical issues decreased by >20%,
     *         "degrading" if increased by >20%,
     *         "stable" otherwise
     */
    private String calculateTrend(List<MonthlyMetrics> monthlyData) {
        if (monthlyData.size() < 3) {
            return "stable";
        }

        int size = monthlyData.size();
        int third = size / 3;

        // Earlier period (first third)
        long earlierCritical = monthlyData.subList(0, third).stream()
                .mapToLong(MonthlyMetrics::getCriticalIssues)
                .sum();

        // Recent period (last third)
        long recentCritical = monthlyData.subList(size - third, size).stream()
                .mapToLong(MonthlyMetrics::getCriticalIssues)
                .sum();

        if (earlierCritical == 0 && recentCritical == 0) {
            return "stable";
        }

        if (earlierCritical == 0) {
            return "degrading"; // went from 0 to some critical issues
        }

        double changePercent = ((double) (recentCritical - earlierCritical) / earlierCritical) * 100;

        if (changePercent <= -20) {
            return "improving";
        } else if (changePercent >= 20) {
            return "degrading";
        }
        return "stable";
    }
}
