package com.codelens.llm;

import com.codelens.service.LlmCostService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Routes tasks to appropriate LLM providers based on task type
 * for cost optimization (cheap models for simple tasks, expensive for complex)
 */
@Slf4j
@Component
public class LlmRouter {

    private final LlmProviderFactory providerFactory;
    private final LlmCostService costService;

    @Value("${codelens.llm.default-provider:glm}")
    private String defaultProvider;

    @Value("${codelens.llm.routing.summary:glm}")
    private String summaryProvider;

    @Value("${codelens.llm.routing.review:glm}")
    private String reviewProvider;

    @Value("${codelens.llm.routing.security:claude}")
    private String securityProvider;

    @Value("${codelens.llm.routing.fallback:ollama}")
    private String fallbackProvider;

    @Value("${codelens.llm.fallback.enabled:true}")
    private boolean fallbackEnabled;

    @Value("${codelens.llm.fallback.max-attempts:3}")
    private int maxFallbackAttempts;

    public LlmRouter(LlmProviderFactory providerFactory, LlmCostService costService) {
        this.providerFactory = providerFactory;
        this.costService = costService;
    }

    /**
     * Result of a fallback-enabled LLM call.
     */
    public record FallbackResult(
            LlmProvider.LlmResponse response,
            String providerUsed,
            int attemptCount,
            List<String> failedProviders
    ) {}

    /**
     * Generate a response with automatic fallback on failure.
     * Tries: preferred provider → default provider → fallback provider
     */
    public FallbackResult generateWithFallback(String prompt, TaskType taskType) {
        return generateWithFallback(
                List.of(Map.of("role", "user", "content", prompt)),
                taskType
        );
    }

    /**
     * Chat with automatic fallback on failure.
     * Tries: preferred provider → default provider → fallback provider
     * @throws LlmCostService.DailyQuotaExceededException if daily quota is exceeded
     */
    public FallbackResult generateWithFallback(List<Map<String, String>> messages, TaskType taskType) {
        // Check daily quota before making LLM call
        costService.checkQuotaOrThrow();

        List<String> providersToTry = buildFallbackChain(taskType);
        List<String> failedProviders = new ArrayList<>();
        int attempt = 0;

        for (String providerName : providersToTry) {
            if (attempt >= maxFallbackAttempts) {
                log.warn("Max fallback attempts ({}) reached", maxFallbackAttempts);
                break;
            }

            var providerOpt = providerFactory.getProvider(providerName);
            if (providerOpt.isEmpty()) {
                log.debug("Provider {} not available, skipping", providerName);
                continue;
            }

            LlmProvider provider = providerOpt.get();
            attempt++;

            try {
                log.debug("Attempting LLM call with provider: {} (attempt {})", providerName, attempt);
                LlmProvider.LlmResponse response = provider.chat(messages);

                if (attempt > 1) {
                    log.info("LLM call succeeded with fallback provider: {} after {} attempts. Failed: {}",
                            providerName, attempt, failedProviders);
                }

                return new FallbackResult(response, providerName, attempt, failedProviders);

            } catch (Exception e) {
                failedProviders.add(providerName);
                log.warn("LLM provider {} failed: {}. Attempting fallback...",
                        providerName, e.getMessage());

                if (!fallbackEnabled) {
                    throw new RuntimeException("LLM call failed and fallback is disabled: " + e.getMessage(), e);
                }
            }
        }

        throw new RuntimeException(String.format(
                "All LLM providers failed after %d attempts. Tried: %s",
                attempt, failedProviders));
    }

    /**
     * Build the fallback chain for a given task type.
     * Returns providers in order: preferred → default → fallback
     */
    private List<String> buildFallbackChain(TaskType taskType) {
        String preferred = switch (taskType) {
            case SUMMARY, DESCRIBE -> summaryProvider;
            case REVIEW, QUICK_SCAN -> reviewProvider;
            case SECURITY_SCAN, DEEP_REVIEW -> securityProvider;
        };

        List<String> chain = new ArrayList<>();
        chain.add(preferred);

        // Add default if different from preferred
        if (!defaultProvider.equals(preferred)) {
            chain.add(defaultProvider);
        }

        // Add fallback if different from both
        if (!fallbackProvider.equals(preferred) && !fallbackProvider.equals(defaultProvider)) {
            chain.add(fallbackProvider);
        }

        return chain;
    }

    /**
     * Simple prompt generation with automatic fallback.
     * Use this for straightforward LLM calls where you don't need provider details.
     */
    public LlmProvider.LlmResponse generate(String prompt, String taskName) {
        TaskType taskType = switch (taskName.toLowerCase()) {
            case "summary" -> TaskType.SUMMARY;
            case "describe" -> TaskType.DESCRIBE;
            case "security" -> TaskType.SECURITY_SCAN;
            case "deep_review" -> TaskType.DEEP_REVIEW;
            case "optimization" -> TaskType.REVIEW; // Use review routing for optimization
            default -> TaskType.REVIEW;
        };
        return generateWithFallback(prompt, taskType).response();
    }

    /**
     * Route a request by task name string
     */
    public LlmProvider routeRequest(String taskName) {
        TaskType taskType = switch (taskName.toLowerCase()) {
            case "summary" -> TaskType.SUMMARY;
            case "describe" -> TaskType.DESCRIBE;
            case "review" -> TaskType.REVIEW;
            case "security" -> TaskType.SECURITY_SCAN;
            case "deep_review" -> TaskType.DEEP_REVIEW;
            default -> TaskType.REVIEW;
        };
        return getProviderForTask(taskType);
    }

    /**
     * Get the appropriate provider for a task type
     */
    public LlmProvider getProviderForTask(TaskType taskType) {
        String preferredProvider = switch (taskType) {
            case SUMMARY, DESCRIBE -> summaryProvider;
            case REVIEW, QUICK_SCAN -> reviewProvider;
            case SECURITY_SCAN -> securityProvider;
            case DEEP_REVIEW -> securityProvider; // Use expensive model
        };

        return providerFactory.getProvider(preferredProvider)
            .or(() -> providerFactory.getProvider(defaultProvider))
            .or(() -> providerFactory.getProvider(fallbackProvider))
            .orElseThrow(() -> new IllegalStateException(
                "No LLM provider available for task: " + taskType));
    }

    /**
     * Get a specific provider or fall back to default
     */
    public LlmProvider getProviderOrDefault(String providerName) {
        if (providerName == null || providerName.isEmpty()) {
            return getDefaultProvider();
        }

        return providerFactory.getProvider(providerName)
            .orElseGet(this::getDefaultProvider);
    }

    /**
     * Get the default provider
     */
    public LlmProvider getDefaultProvider() {
        return providerFactory.getProvider(defaultProvider)
            .or(() -> providerFactory.getProvider(fallbackProvider))
            .orElseThrow(() -> new IllegalStateException("No default LLM provider available"));
    }

    /**
     * Task types for routing decisions
     */
    public enum TaskType {
        SUMMARY,        // Generate PR summary (cheap)
        DESCRIBE,       // Generate PR description (cheap)
        QUICK_SCAN,     // Quick code scan (cheap)
        REVIEW,         // Standard code review (medium)
        DEEP_REVIEW,    // Deep analysis (expensive)
        SECURITY_SCAN   // Security-focused review (expensive)
    }
}
