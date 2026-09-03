package com.codelens.llm.providers;

import dev.langchain4j.model.googleai.GoogleAiGeminiTokenUsage;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cached and reasoning token counts are the only evidence that two of this project's
 * levers work at all: whether the reordered prompts win prefix cache hits, and what a
 * reasoning tier actually costs (reasoning tokens bill as output). Both are reported in
 * a provider-specific subtype of TokenUsage, so the extraction is worth pinning.
 */
class ProviderTokenUsageTest {

    @Nested
    class GeminiUsage {

        @Test
        void readsCachedAndThinkingCounts() {
            TokenUsage usage = GoogleAiGeminiTokenUsage.builder()
                .inputTokenCount(1000)
                .outputTokenCount(200)
                .cachedContentTokenCount(750)
                .thoughtsTokenCount(120)
                .build();

            assertEquals(750, ProviderTokenUsage.cachedTokens(usage));
            assertEquals(120, ProviderTokenUsage.thinkingTokens(usage));
        }

        @Test
        void treatsUnreportedCountsAsZeroRatherThanFailing() {
            TokenUsage usage = GoogleAiGeminiTokenUsage.builder()
                .inputTokenCount(1000)
                .outputTokenCount(200)
                .build();

            assertEquals(0, ProviderTokenUsage.cachedTokens(usage));
            assertEquals(0, ProviderTokenUsage.thinkingTokens(usage));
        }
    }

    @Nested
    class OtherProviders {

        @Test
        void plainUsageReportsZero() {
            TokenUsage usage = new TokenUsage(1000, 200);

            assertEquals(0, ProviderTokenUsage.cachedTokens(usage));
            assertEquals(0, ProviderTokenUsage.thinkingTokens(usage));
        }

        @Test
        void nullUsageDoesNotThrow() {
            assertEquals(0, ProviderTokenUsage.cachedTokens(null));
            assertEquals(0, ProviderTokenUsage.thinkingTokens(null));
        }
    }
}
