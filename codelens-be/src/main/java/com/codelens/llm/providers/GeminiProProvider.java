package com.codelens.llm.providers;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class GeminiProProvider extends AbstractLlmProvider {

    @Value("${codelens.llm.providers.gemini-pro.enabled:true}")
    private boolean enabled;

    @Value("${codelens.llm.providers.gemini-pro.api-key:${codelens.llm.providers.gemini.api-key:}}")
    private String apiKey;

    @Value("${codelens.llm.providers.gemini-pro.model:gemini-2.5-pro}")
    private String model;

    @Override
    public String getName() {
        return "gemini-pro";
    }

    @Override
    public boolean isEnabled() {
        return enabled && apiKey != null && !apiKey.isEmpty();
    }

    @Override
    protected ChatLanguageModel createChatModel() {
        if (!isEnabled()) {
            throw new IllegalStateException("Gemini Pro provider is not enabled or API key is missing");
        }

        return GoogleAiGeminiChatModel.builder()
            .apiKey(apiKey)
            .modelName(model)
            .temperature(0.2)  // Lower temperature for more precise security analysis
            .maxOutputTokens(8192)  // Higher output for detailed security reports
            .build();
    }

    @Override
    public double estimateCost(int inputTokens, int outputTokens) {
        // Gemini 2.5 Pro pricing: $1.25/1M input, $5.00/1M output (standard)
        double inputCost = inputTokens * 1.25 / 1_000_000;
        double outputCost = outputTokens * 5.00 / 1_000_000;
        return inputCost + outputCost;
    }
}
