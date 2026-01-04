package com.codelens.llm.providers;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class GeminiProvider extends AbstractLlmProvider {

    @Value("${codelens.llm.providers.gemini.enabled:true}")
    private boolean enabled;

    @Value("${codelens.llm.providers.gemini.api-key:}")
    private String apiKey;

    @Value("${codelens.llm.providers.gemini.model:gemini-2.5-flash}")
    private String model;

    @Override
    public String getName() {
        return "gemini";
    }

    @Override
    public boolean isEnabled() {
        return enabled && apiKey != null && !apiKey.isEmpty();
    }

    @Override
    protected ChatLanguageModel createChatModel() {
        if (!isEnabled()) {
            throw new IllegalStateException("Gemini provider is not enabled or API key is missing");
        }

        return GoogleAiGeminiChatModel.builder()
            .apiKey(apiKey)
            .modelName(model)
            .temperature(0.3)
            .maxOutputTokens(4096)
            .build();
    }

    @Override
    public double estimateCost(int inputTokens, int outputTokens) {
        // Gemini 2.0 Flash pricing: $0.075/1M input, $0.30/1M output
        double inputCost = inputTokens * 0.075 / 1_000_000;
        double outputCost = outputTokens * 0.30 / 1_000_000;
        return inputCost + outputCost;
    }
}
