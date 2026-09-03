package com.codelens.llm;

import java.util.List;
import java.util.Map;

/**
 * Interface for LLM providers
 */
public interface LlmProvider {

    /**
     * Get the provider name (e.g., "glm", "claude", "gemini")
     */
    String getName();

    /**
     * Check if the provider is enabled and configured
     */
    boolean isEnabled();

    /**
     * Generate a response from a simple prompt
     */
    LlmResponse generate(String prompt);

    /**
     * Chat with message history
     */
    LlmResponse chat(List<Map<String, String>> messages);

    /**
     * Chat with message history, asking the provider to enforce a response shape.
     *
     * <p>Providers that cannot enforce structure ignore the shape and answer as usual,
     * so callers always get a response and the prose instructions in the prompt remain
     * the contract of last resort.
     */
    default LlmResponse chat(List<Map<String, String>> messages, ResponseShape shape) {
        return chat(messages);
    }

    /**
     * Response shapes a caller can ask a provider to enforce natively.
     *
     * <p>Kept as a small named set rather than a schema object so that providers which
     * do not use LangChain4j (GLM) are not forced to depend on its types.
     */
    enum ResponseShape {
        /** Whatever the prompt asks for; no machine-enforced structure. */
        FREE_TEXT,
        /** The review-issue array shared by the review and security-scan prompts. */
        REVIEW_ISSUES
    }

    /**
     * Estimate cost for given token counts (in USD)
     */
    double estimateCost(int inputTokens, int outputTokens);

    /**
     * Response from LLM provider.
     *
     * <p>{@code truncated} is true when the model stopped because it hit the output
     * token ceiling rather than finishing its answer. Truncated responses are the
     * dominant cause of unparseable review JSON, so callers should treat a truncated
     * response as a degraded result rather than an empty one.
     */
    record LlmResponse(
        String content,
        int inputTokens,
        int outputTokens,
        boolean truncated,
        /** Input tokens served from a prompt cache. 0 when unreported. */
        int cachedTokens,
        /** Reasoning tokens, billed as output. 0 when unreported. */
        int thinkingTokens
    ) {
        /** Convenience constructor for providers that cannot report a finish reason. */
        public LlmResponse(String content, int inputTokens, int outputTokens) {
            this(content, inputTokens, outputTokens, false);
        }

        public LlmResponse(String content, int inputTokens, int outputTokens, boolean truncated) {
            this(content, inputTokens, outputTokens, truncated, 0, 0);
        }

        /** Fraction of input served from cache, 0.0 to 1.0. */
        public double cacheHitRatio() {
            return inputTokens <= 0 ? 0.0 : (double) cachedTokens / inputTokens;
        }

        public double estimatedCost(LlmProvider provider) {
            return provider.estimateCost(inputTokens, outputTokens);
        }
    }
}
