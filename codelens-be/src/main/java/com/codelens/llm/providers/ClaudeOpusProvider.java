package com.codelens.llm.providers;

import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class ClaudeOpusProvider extends AbstractLlmProvider {

    @Value("${codelens.llm.providers.claude-opus.enabled:true}")
    private boolean enabled;

    @Value("${codelens.llm.providers.claude-opus.api-key:${codelens.llm.providers.claude.api-key:}}")
    private String apiKey;

    @Value("${codelens.llm.providers.claude-opus.model:claude-opus-4-6}")
    private String model;

    @Override
    public String getName() {
        return "claude-opus";
    }

    @Override
    public boolean isEnabled() {
        return enabled && apiKey != null && !apiKey.isEmpty();
    }

    @Override
    protected ChatLanguageModel createChatModel() {
        if (!isEnabled()) {
            throw new IllegalStateException("Claude Opus provider is not enabled or API key is missing");
        }

        return AnthropicChatModel.builder()
            .apiKey(apiKey)
            .modelName(model)
            .temperature(0.2)
            .maxTokens(8192)
            .build();
    }

    @Override
    public double estimateCost(int inputTokens, int outputTokens) {
        // Claude Opus 4.6 pricing: $15/1M input, $75/1M output
        double inputCost = inputTokens * 15.0 / 1_000_000;
        double outputCost = outputTokens * 75.0 / 1_000_000;
        return inputCost + outputCost;
    }
}
