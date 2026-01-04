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
     * Estimate cost for given token counts (in USD)
     */
    double estimateCost(int inputTokens, int outputTokens);

    /**
     * Response from LLM provider
     */
    record LlmResponse(
        String content,
        int inputTokens,
        int outputTokens
    ) {
        public double estimatedCost(LlmProvider provider) {
            return provider.estimateCost(inputTokens, outputTokens);
        }
    }
}
