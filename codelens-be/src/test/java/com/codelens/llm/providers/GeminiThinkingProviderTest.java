package com.codelens.llm.providers;

import dev.langchain4j.model.googleai.GeminiThinkingConfig;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The high-reasoning tier is a separate provider bean rather than a per-call option,
 * reusing the existing "select a capability tier by provider name" mechanism. These tests
 * cover the parts that would otherwise only fail against the live API: the reasoning level
 * actually being attached, the wire format of that level, and configuration inheritance
 * from the plain Gemini provider.
 */
class GeminiThinkingProviderTest {

    @Configuration
    static class TestConfig {
        @Bean
        static PropertySourcesPlaceholderConfigurer placeholders() {
            return new PropertySourcesPlaceholderConfigurer();
        }

        @Bean GeminiThinkingProvider thinking() { return new GeminiThinkingProvider(); }
        @Bean GeminiProvider plain() { return new GeminiProvider(); }
        @Bean GeminiProProvider pro() { return new GeminiProProvider(); }
    }

    @Nested
    @SpringJUnitConfig(TestConfig.class)
    @TestPropertySource(properties = "codelens.llm.providers.gemini.api-key=test-key")
    class Defaults {

        @Autowired GeminiThinkingProvider thinking;
        @Autowired GeminiProvider plain;
        @Autowired GeminiProProvider pro;

        @Test
        void inheritsApiKeyAndModelFromThePlainGeminiProvider() {
            assertTrue(thinking.isEnabled(),
                "one GOOGLE_API_KEY should enable the thinking tier too");
            assertEquals("gemini-3.8-flash", thinking.settings().model());
        }

        @Test
        void runsAtHighReasoningEffortByDefault() {
            GeminiThinkingConfig config = thinking.settings().thinking();
            assertNotNull(config, "the thinking tier must attach a reasoning config");
            assertEquals("high", config.thinkingLevel(),
                "the API expects the lowercase form; an uppercase value would be rejected");
        }

        @Test
        void doesNotAskForReasoningTextInTheResponse() {
            // Reasoning text in the body would precede the JSON array and break parsing.
            assertEquals(Boolean.FALSE, thinking.settings().thinking().includeThoughts());
        }

        @Test
        void allowsALongerAnswerThanTheReviewTier() {
            assertTrue(thinking.settings().maxOutputTokens() > plain.settings().maxOutputTokens(),
                "deeper reasoning produces longer answers");
        }

        @Test
        void theOtherGeminiTiersUseTheModelDefaultEffort() {
            assertNull(plain.settings().thinking());
            assertNull(pro.settings().thinking());
        }

        @Test
        void isDistinctFromThePlainProviderInRouting() {
            assertEquals("gemini-thinking", thinking.getName());
            assertNotEquals(plain.getName(), thinking.getName());
        }

        @Test
        void everyGeminiTierBuildsAModel() {
            assertDoesNotThrow(thinking::createChatModel);
            assertDoesNotThrow(plain::createChatModel);
        }
    }

    @Nested
    @SpringJUnitConfig(TestConfig.class)
    @TestPropertySource(properties = {
        "codelens.llm.providers.gemini.api-key=test-key",
        "codelens.llm.providers.gemini-thinking.thinking-level=LOW",
        "codelens.llm.providers.gemini-thinking.model=gemini-3.7-flash"
    })
    class Overrides {

        @Autowired GeminiThinkingProvider thinking;

        @Test
        void effortCanBeLoweredWithoutChangingRouting() {
            assertEquals("low", thinking.settings().thinking().thinkingLevel());
        }

        @Test
        void modelCanBePinnedIndependently() {
            assertEquals("gemini-3.7-flash", thinking.settings().model());
        }
    }

    @Nested
    class LevelParsing {

        @Test
        void acceptsEveryDocumentedLevelCaseInsensitively() {
            assertEquals(GeminiThinkingConfig.GeminiThinkingLevel.MINIMAL,
                GeminiThinkingProvider.parseThinkingLevel("minimal"));
            assertEquals(GeminiThinkingConfig.GeminiThinkingLevel.LOW,
                GeminiThinkingProvider.parseThinkingLevel(" Low "));
            assertEquals(GeminiThinkingConfig.GeminiThinkingLevel.MEDIUM,
                GeminiThinkingProvider.parseThinkingLevel("MEDIUM"));
            assertEquals(GeminiThinkingConfig.GeminiThinkingLevel.HIGH,
                GeminiThinkingProvider.parseThinkingLevel("high"));
        }

        @Test
        void fallsBackToHighWhenUnset() {
            assertEquals(GeminiThinkingConfig.GeminiThinkingLevel.HIGH,
                GeminiThinkingProvider.parseThinkingLevel(null));
            assertEquals(GeminiThinkingConfig.GeminiThinkingLevel.HIGH,
                GeminiThinkingProvider.parseThinkingLevel("  "));
        }

        @Test
        void rejectsATypoWithAnActionableMessage() {
            IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> GeminiThinkingProvider.parseThinkingLevel("HIHG"));
            assertTrue(e.getMessage().contains("MINIMAL, LOW, MEDIUM, HIGH"),
                "message should list the valid values: " + e.getMessage());
        }
    }
}
