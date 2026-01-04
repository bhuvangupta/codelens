package com.codelens.llm.providers;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class OpenAiProvider extends AbstractLlmProvider {

    @Value("${codelens.llm.providers.openai.enabled:true}")
    private boolean enabled;

    @Value("${codelens.llm.providers.openai.api-key:}")
    private String apiKey;

    @Value("${codelens.llm.providers.openai.model:gpt-4o}")
    private String model;

    @Override
    public String getName() {
        return "openai";
    }

    @Override
    public boolean isEnabled() {
        return enabled && apiKey != null && !apiKey.isEmpty();
    }

    @Override
    protected ChatLanguageModel createChatModel() {
        if (!isEnabled()) {
            throw new IllegalStateException("OpenAI provider is not enabled or API key is missing");
        }

        return OpenAiChatModel.builder()
            .apiKey(apiKey)
            .modelName(model)
            .temperature(0.3)
            .maxTokens(4096)
            .build();
    }

    @Override
    public double estimateCost(int inputTokens, int outputTokens) {
        // GPT-4o pricing: $2.50/1M input, $10/1M output
        double inputCost = inputTokens * 2.50 / 1_000_000;
        double outputCost = outputTokens * 10.0 / 1_000_000;
        return inputCost + outputCost;
    }
}
