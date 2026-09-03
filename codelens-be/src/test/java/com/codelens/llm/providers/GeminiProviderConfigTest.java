package com.codelens.llm.providers;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Property binding for the Gemini provider.
 *
 * <p>The temperature default is {@code #{null}} rather than a number, so an
 * unconfigured temperature is omitted from the request entirely and the model applies
 * its own default. That relies on the placeholder default resolving to null; if it ever
 * resolved to a literal string instead, the failure would only appear at application
 * startup, so it is pinned here.
 */
class GeminiProviderConfigTest {

    @Configuration
    static class TestConfig {
        @Bean
        static PropertySourcesPlaceholderConfigurer placeholders() {
            return new PropertySourcesPlaceholderConfigurer();
        }

        @Bean
        GeminiProvider geminiProvider() {
            return new GeminiProvider();
        }
    }

    private static Object field(Object target, String name) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }

    @Nested
    @SpringJUnitConfig(TestConfig.class)
    @TestPropertySource(properties = {
        "codelens.llm.providers.gemini.api-key=test-key"
    })
    class Defaults {

        @Autowired
        GeminiProvider provider;

        @Test
        void temperatureIsUnsetSoTheModelDefaultApplies() throws Exception {
            assertNull(field(provider, "temperature"),
                "an unconfigured temperature must bind to null, not a literal or zero");
        }

        @Test
        void modelDefaultsToGemini38Flash() throws Exception {
            assertEquals("gemini-3.8-flash", field(provider, "model"));
        }

        @Test
        void outputCeilingIsRaisedAboveTheTruncatingDefault() throws Exception {
            assertEquals(16384, field(provider, "maxOutputTokens"),
                "4096 truncated multi-issue reviews mid-JSON");
        }

        @Test
        void buildsAChatModelWithoutThrowing() {
            assertTrue(provider.isEnabled());
            assertDoesNotThrow(provider::createChatModel,
                "the builder must accept a null temperature");
        }
    }

    @Nested
    @SpringJUnitConfig(TestConfig.class)
    @TestPropertySource(properties = {
        "codelens.llm.providers.gemini.api-key=test-key",
        "codelens.llm.providers.gemini.model=gemini-3.7-flash",
        "codelens.llm.providers.gemini.temperature=0.25",
        "codelens.llm.providers.gemini.max-output-tokens=2048"
    })
    class Overrides {

        @Autowired
        GeminiProvider provider;

        @Test
        void explicitValuesWin() throws Exception {
            assertEquals("gemini-3.7-flash", field(provider, "model"));
            assertEquals(0.25, field(provider, "temperature"));
            assertEquals(2048, field(provider, "maxOutputTokens"));
        }
    }

    @Nested
    class Pricing {

        @Test
        void usesGemini38FlashRates() {
            GeminiProvider provider = new GeminiProvider();
            // 1M input + 1M output at $0.75 / $3.75
            assertEquals(4.50, provider.estimateCost(1_000_000, 1_000_000), 1e-9);
            assertEquals(0.0, provider.estimateCost(0, 0), 1e-9);
        }
    }
}
