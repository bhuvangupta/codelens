package com.codelens.llm.providers;

import com.codelens.llm.LlmProvider;
import com.codelens.llm.ReviewIssueSchema;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.googleai.GeminiHarmBlockThreshold;
import dev.langchain4j.model.googleai.GeminiHarmCategory;
import dev.langchain4j.model.googleai.GeminiThinkingConfig;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;

import java.util.Map;

/**
 * Shared assembly for the Gemini-backed providers, which differ only in model, limits and
 * reasoning effort. Keeping the builder in one place means a change to safety settings or
 * structured output applies to every Gemini tier instead of drifting between copies.
 */
public abstract class AbstractGeminiProvider extends AbstractLlmProvider {

    /**
     * Code review legitimately analyses SQL injection, weak crypto and exploit code. The
     * default DANGEROUS_CONTENT filter can block those diffs outright, which reaches the
     * user as an empty review rather than an error. Other categories stay at API defaults.
     */
    private static final Map<GeminiHarmCategory, GeminiHarmBlockThreshold> CODE_REVIEW_SAFETY =
        Map.of(GeminiHarmCategory.HARM_CATEGORY_DANGEROUS_CONTENT,
               GeminiHarmBlockThreshold.BLOCK_ONLY_HIGH);

    /** Model, limits and reasoning effort for this tier. */
    protected record GeminiSettings(
        String apiKey,
        String model,
        Double temperature,
        Integer maxOutputTokens,
        /** Null for the model's own default effort. */
        GeminiThinkingConfig thinking
    ) {}

    protected abstract GeminiSettings settings();

    /** Whether this tier should enforce the review-issue schema natively. */
    protected boolean supportsStructuredOutput() {
        return true;
    }

    @Override
    protected ChatModel createChatModel() {
        if (!isEnabled()) {
            throw new IllegalStateException(getName() + " provider is not enabled or API key is missing");
        }
        GeminiSettings s = settings();

        // The builder methods return a generic self-type, so `var` keeps the concrete
        // builder type through the chain.
        var builder = GoogleAiGeminiChatModel.builder()
            .apiKey(s.apiKey())
            .modelName(s.model())
            .temperature(s.temperature())
            .maxOutputTokens(s.maxOutputTokens())
            .safetySettings(CODE_REVIEW_SAFETY);

        if (s.thinking() != null) {
            builder = builder
                .thinkingConfig(s.thinking())
                // Reasoning text must not come back in the message body: it would sit in
                // front of the JSON array and break parsing. The tokens are still billed
                // and still counted through thoughtsTokenCount().
                .returnThinking(false);
        }

        return builder.build();
    }

    /**
     * Gemini supports native JSON-schema output, so the review contract is enforced rather
     * than merely requested in prose. Applied per request, so free-text tasks on the same
     * provider (the PR summary, ticket scope) are unaffected.
     */
    @Override
    protected ResponseFormat responseFormatFor(LlmProvider.ResponseShape shape) {
        if (!supportsStructuredOutput() || shape != LlmProvider.ResponseShape.REVIEW_ISSUES) {
            return null;
        }
        return ReviewIssueSchema.responseFormat();
    }
}
