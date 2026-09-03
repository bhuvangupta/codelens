package com.codelens.llm.providers;

import dev.langchain4j.model.googleai.GoogleAiGeminiTokenUsage;
import dev.langchain4j.model.output.TokenUsage;

/**
 * Reads the extra token counters some providers report alongside the usual input and
 * output totals.
 *
 * <p>Two of them decide whether the levers this project pulls are actually working:
 * <ul>
 *   <li><b>cached</b> input tokens show whether the prompt layout is winning prefix
 *       cache hits. Without this number, a change intended to improve caching cannot
 *       be told apart from one that did nothing.
 *   <li><b>thinking</b> tokens are billed as output, so a reasoning tier's real cost is
 *       invisible without them.
 * </ul>
 *
 * <p>Provider-specific knowledge is isolated here rather than in the shared base class,
 * and anything unrecognised simply reports zero.
 */
final class ProviderTokenUsage {

    private ProviderTokenUsage() {}

    /** Input tokens served from a prompt cache, or 0 when the provider does not report it. */
    static int cachedTokens(TokenUsage usage) {
        if (usage instanceof GoogleAiGeminiTokenUsage gemini) {
            return orZero(gemini.cachedContentTokenCount());
        }
        return 0;
    }

    /** Reasoning tokens, billed as output, or 0 when the provider does not report them. */
    static int thinkingTokens(TokenUsage usage) {
        if (usage instanceof GoogleAiGeminiTokenUsage gemini) {
            return orZero(gemini.thoughtsTokenCount());
        }
        return 0;
    }

    private static int orZero(Integer value) {
        return value == null ? 0 : value;
    }
}
