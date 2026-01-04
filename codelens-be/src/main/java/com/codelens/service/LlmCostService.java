package com.codelens.service;

import com.codelens.repository.LlmUsageRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Service for tracking LLM costs and enforcing daily quota limits.
 */
@Slf4j
@Service
public class LlmCostService {

    private final LlmUsageRepository llmUsageRepository;

    @Value("${codelens.cost.enforce-daily-limit:true}")
    private boolean enforceDailyLimit;

    @Value("${codelens.cost.daily-limit-usd:50.00}")
    private double dailyLimitUsd;

    @Value("${codelens.cost.alert-threshold-daily:100.00}")
    private double alertThresholdDaily;

    public LlmCostService(LlmUsageRepository llmUsageRepository) {
        this.llmUsageRepository = llmUsageRepository;
    }

    /**
     * Check if we're within the daily quota.
     * @return true if under quota, false if quota exceeded
     */
    public boolean isWithinDailyQuota() {
        if (!enforceDailyLimit) {
            return true;
        }
        double todayCost = getTodayCost();
        return todayCost < dailyLimitUsd;
    }

    /**
     * Get remaining budget for today in USD.
     */
    public double getRemainingBudget() {
        double todayCost = getTodayCost();
        return Math.max(0, dailyLimitUsd - todayCost);
    }

    /**
     * Get today's total LLM cost in USD.
     */
    public double getTodayCost() {
        LocalDateTime startOfDay = LocalDate.now().atStartOfDay();
        Double cost = llmUsageRepository.sumEstimatedCostByCreatedAtAfter(startOfDay);
        return cost != null ? cost : 0.0;
    }

    /**
     * Get the configured daily limit.
     */
    public double getDailyLimit() {
        return dailyLimitUsd;
    }

    /**
     * Check if daily limit enforcement is enabled.
     */
    public boolean isEnforcementEnabled() {
        return enforceDailyLimit;
    }

    /**
     * Check quota and throw exception if exceeded.
     * @throws DailyQuotaExceededException if quota is exceeded
     */
    public void checkQuotaOrThrow() throws DailyQuotaExceededException {
        if (!isWithinDailyQuota()) {
            double todayCost = getTodayCost();
            log.warn("Daily LLM quota exceeded: ${} / ${} limit",
                    String.format("%.2f", todayCost),
                    String.format("%.2f", dailyLimitUsd));
            throw new DailyQuotaExceededException(todayCost, dailyLimitUsd);
        }
    }

    /**
     * Get quota status for display.
     */
    public QuotaStatus getQuotaStatus() {
        double todayCost = getTodayCost();
        double remaining = Math.max(0, dailyLimitUsd - todayCost);
        double percentUsed = dailyLimitUsd > 0 ? (todayCost / dailyLimitUsd) * 100 : 0;
        boolean exceeded = todayCost >= dailyLimitUsd;
        boolean warning = todayCost >= (dailyLimitUsd * 0.8); // 80% threshold

        return new QuotaStatus(
                todayCost,
                dailyLimitUsd,
                remaining,
                percentUsed,
                exceeded,
                warning,
                enforceDailyLimit
        );
    }

    public record QuotaStatus(
            double usedToday,
            double dailyLimit,
            double remaining,
            double percentUsed,
            boolean exceeded,
            boolean warning,
            boolean enforcementEnabled
    ) {}

    /**
     * Exception thrown when daily LLM quota is exceeded.
     */
    public static class DailyQuotaExceededException extends RuntimeException {
        private final double currentCost;
        private final double dailyLimit;

        public DailyQuotaExceededException(double currentCost, double dailyLimit) {
            super(String.format("Daily LLM quota exceeded: $%.2f used of $%.2f limit",
                    currentCost, dailyLimit));
            this.currentCost = currentCost;
            this.dailyLimit = dailyLimit;
        }

        public double getCurrentCost() {
            return currentCost;
        }

        public double getDailyLimit() {
            return dailyLimit;
        }
    }
}
