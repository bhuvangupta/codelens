package com.codelens.llm.providers;

import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class ClaudeProvider extends AbstractLlmProvider {

    @Value("${codelens.llm.providers.claude.enabled:true}")
    private boolean enabled;

    @Value("${codelens.llm.providers.claude.api-key:}")
    private String apiKey;

    @Value("${codelens.llm.providers.claude.model:claude-sonnet-4-20250514}")
    private String model;

    @Override
    public String getName() {
        return "claude";
    }

    @Override
    public boolean isEnabled() {
        return enabled && apiKey != null && !apiKey.isEmpty();
    }

    @Override
    protected ChatLanguageModel createChatModel() {
        if (!isEnabled()) {
            throw new IllegalStateException("Claude provider is not enabled or API key is missing");
        }

        return AnthropicChatModel.builder()
            .apiKey(apiKey)
            .modelName(model)
            .temperature(0.3)
            .maxTokens(4096)
            .build();
    }

    @Override
    public double estimateCost(int inputTokens, int outputTokens) {
        // Claude Sonnet 4 pricing: $3/1M input, $15/1M output
        double inputCost = inputTokens * 3.0 / 1_000_000;
        double outputCost = outputTokens * 15.0 / 1_000_000;
        return inputCost + outputCost;
    }
}
