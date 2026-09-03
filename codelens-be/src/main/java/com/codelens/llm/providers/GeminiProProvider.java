package com.codelens.llm.providers;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Gemini Pro tier: higher quality, slower and more expensive than Flash.
 */
@Slf4j
@Component
public class GeminiProProvider extends AbstractGeminiProvider {

    @Value("${codelens.llm.providers.gemini-pro.enabled:true}")
    private boolean enabled;

    @Value("${codelens.llm.providers.gemini-pro.api-key:${codelens.llm.providers.gemini.api-key:}}")
    private String apiKey;

    @Value("${codelens.llm.providers.gemini-pro.model:gemini-2.5-pro}")
    private String model;

    @Value("${codelens.llm.providers.gemini-pro.max-output-tokens:8192}")
    private Integer maxOutputTokens;

    /** Lower temperature for more precise security analysis. */
    @Value("${codelens.llm.providers.gemini-pro.temperature:0.2}")
    private Double temperature;

    @Value("${codelens.llm.providers.gemini-pro.structured-output:true}")
    private boolean structuredOutputEnabled;

    @Override
    public String getName() {
        return "gemini-pro";
    }

    @Override
    protected String modelName() {
        return model;
    }

    @Override
    public boolean isEnabled() {
        return enabled && apiKey != null && !apiKey.isEmpty();
    }

    @Override
    protected boolean supportsStructuredOutput() {
        return structuredOutputEnabled;
    }

    @Override
    protected GeminiSettings settings() {
        return new GeminiSettings(apiKey, model, temperature, maxOutputTokens, null);
    }

    @Override
    public double estimateCost(int inputTokens, int outputTokens) {
        // Gemini 2.5 Pro pricing: $1.25/1M input, $5.00/1M output (standard)
        double inputCost = inputTokens * 1.25 / 1_000_000;
        double outputCost = outputTokens * 5.00 / 1_000_000;
        return inputCost + outputCost;
    }
}
