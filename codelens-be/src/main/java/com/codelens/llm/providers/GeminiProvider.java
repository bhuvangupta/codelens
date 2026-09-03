package com.codelens.llm.providers;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.googleai.GeminiHarmBlockThreshold;
import dev.langchain4j.model.googleai.GeminiHarmCategory;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
public class GeminiProvider extends AbstractLlmProvider {

    @Value("${codelens.llm.providers.gemini.enabled:true}")
    private boolean enabled;

    @Value("${codelens.llm.providers.gemini.api-key:}")
    private String apiKey;

    @Value("${codelens.llm.providers.gemini.model:gemini-3.8-flash}")
    private String model;

    /**
     * Output ceiling. 4096 truncated multi-issue reviews mid-JSON; 16384 comfortably
     * fits the "max 5-7 issues" response the prompts ask for.
     */
    @Value("${codelens.llm.providers.gemini.max-output-tokens:16384}")
    private Integer maxOutputTokens;

    /**
     * Unset by default: Gemini 3.x is tuned for its own default temperature, and an
     * unset value is omitted from the request rather than sent as zero. Set this only
     * to deliberately override the model default.
     */
    @Value("${codelens.llm.providers.gemini.temperature:#{null}}")
    private Double temperature;

    @Override
    public String getName() {
        return "gemini";
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
    protected ChatLanguageModel createChatModel() {
        if (!isEnabled()) {
            throw new IllegalStateException("Gemini provider is not enabled or API key is missing");
        }

        return GoogleAiGeminiChatModel.builder()
            .apiKey(apiKey)
            .modelName(model)
            .temperature(temperature)
            .maxOutputTokens(maxOutputTokens)
            // Code review legitimately analyses SQL injection, weak crypto and exploit
            // code. The default DANGEROUS_CONTENT filter can block those diffs outright,
            // which surfaces as an empty review. Other categories stay at API defaults.
            .safetySettings(Map.of(
                GeminiHarmCategory.HARM_CATEGORY_DANGEROUS_CONTENT,
                GeminiHarmBlockThreshold.BLOCK_ONLY_HIGH
            ))
            .build();
    }

    @Override
    public double estimateCost(int inputTokens, int outputTokens) {
        // Gemini 3.8 Flash, verified against ai.google.dev/gemini-api/docs/pricing on
        // 2026-09-03: $0.75/1M input, $3.75/1M output. Thinking tokens bill as output.
        // NOTE: this is the introductory rate; it doubles to $1.50/$7.50 on 2027-01-01.
        double inputCost = inputTokens * 0.75 / 1_000_000;
        double outputCost = outputTokens * 3.75 / 1_000_000;
        return inputCost + outputCost;
    }
}
