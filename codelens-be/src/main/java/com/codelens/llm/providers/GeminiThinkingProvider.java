package com.codelens.llm.providers;

import dev.langchain4j.model.googleai.GeminiThinkingConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Gemini tier with an explicitly pinned reasoning effort, used for security scans.
 *
 * <p>Expressed as its own provider bean rather than as a per-call option, because the
 * codebase already selects capability tiers by provider name in {@code routing}
 * ({@code gemini} / {@code gemini-pro}, {@code claude} / {@code claude-opus}). Reusing
 * that mechanism means no change to the provider interface and no new configuration
 * concept: point a task at this bean and its reasoning effort becomes tunable on its own.
 *
 * <p>The level is MEDIUM by default, the balanced setting. Reasoning tokens are billed as
 * output, so raising it is a real cost and latency decision rather than a free upgrade —
 * and security scanning sits on the per-file path, where that cost multiplies. Raise it
 * deliberately, with the thinking-token counter to show what it actually cost.
 */
@Slf4j
@Component
public class GeminiThinkingProvider extends AbstractGeminiProvider {

    @Value("${codelens.llm.providers.gemini-thinking.enabled:true}")
    private boolean enabled;

    @Value("${codelens.llm.providers.gemini-thinking.api-key:${codelens.llm.providers.gemini.api-key:}}")
    private String apiKey;

    @Value("${codelens.llm.providers.gemini-thinking.model:${codelens.llm.providers.gemini.model:gemini-3.8-flash}}")
    private String model;

    /** Reasoning answers run longer, so the ceiling is higher than the review tier. */
    @Value("${codelens.llm.providers.gemini-thinking.max-output-tokens:32768}")
    private Integer maxOutputTokens;

    @Value("${codelens.llm.providers.gemini-thinking.temperature:#{null}}")
    private Double temperature;

    /**
     * MINIMAL, LOW, MEDIUM or HIGH. MEDIUM is the balanced default; raise it to trade
     * latency and cost for depth without changing any routing.
     */
    @Value("${codelens.llm.providers.gemini-thinking.thinking-level:MEDIUM}")
    private String thinkingLevel;

    @Value("${codelens.llm.providers.gemini-thinking.structured-output:true}")
    private boolean structuredOutputEnabled;

    @Override
    public String getName() {
        return "gemini-thinking";
    }

    @Override
    protected String modelName() {
        return model + " (thinking=" + thinkingLevel + ")";
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
        return new GeminiSettings(apiKey, model, temperature, maxOutputTokens,
            GeminiThinkingConfig.builder()
                // The enum overload is deliberate: it lowercases the value to the form the
                // API expects, whereas the String overload passes it through verbatim and
                // would send "HIGH" instead of "high". It also rejects a typo at startup.
                .thinkingLevel(parseThinkingLevel(thinkingLevel))
                .includeThoughts(false)
                .build());
    }

    /** Fails fast with an actionable message rather than at the first API call. */
    static GeminiThinkingConfig.GeminiThinkingLevel parseThinkingLevel(String configured) {
        if (configured == null || configured.isBlank()) {
            return GeminiThinkingConfig.GeminiThinkingLevel.MEDIUM;
        }
        try {
            return GeminiThinkingConfig.GeminiThinkingLevel.valueOf(
                configured.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                "Invalid codelens.llm.providers.gemini-thinking.thinking-level '" + configured
                    + "'. Expected one of MINIMAL, LOW, MEDIUM, HIGH.", e);
        }
    }

    @Override
    public double estimateCost(int inputTokens, int outputTokens) {
        // Same Flash rate as the default tier; the extra cost of reasoning shows up as
        // more output tokens rather than a higher per-token price.
        double inputCost = inputTokens * 0.75 / 1_000_000;
        double outputCost = outputTokens * 3.75 / 1_000_000;
        return inputCost + outputCost;
    }
}
