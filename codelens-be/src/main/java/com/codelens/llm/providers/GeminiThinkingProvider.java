package com.codelens.llm.providers;

import dev.langchain4j.model.googleai.GeminiThinkingConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * High-reasoning Gemini tier, for tasks where depth beats latency: security scans and
 * anything else routed to it explicitly.
 *
 * <p>Expressed as its own provider bean rather than as a per-call option, because the
 * codebase already selects capability tiers by provider name in {@code routing}
 * ({@code gemini} / {@code gemini-pro}, {@code claude} / {@code claude-opus}). Reusing
 * that mechanism means no change to the provider interface and no new configuration
 * concept: point a task at this bean and it gets more reasoning effort.
 *
 * <p>Reasoning tokens are billed as output, so this tier is not free. Its real cost is
 * visible through the thinking-token counter rather than being buried in the output total.
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

    /** Deeper reasoning produces longer answers, so the ceiling is higher than the review tier. */
    @Value("${codelens.llm.providers.gemini-thinking.max-output-tokens:32768}")
    private Integer maxOutputTokens;

    @Value("${codelens.llm.providers.gemini-thinking.temperature:#{null}}")
    private Double temperature;

    /**
     * MINIMAL, LOW, MEDIUM or HIGH. HIGH is the point of this bean; lower it to trade
     * depth for latency and cost without changing any routing.
     */
    @Value("${codelens.llm.providers.gemini-thinking.thinking-level:HIGH}")
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
            return GeminiThinkingConfig.GeminiThinkingLevel.HIGH;
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
