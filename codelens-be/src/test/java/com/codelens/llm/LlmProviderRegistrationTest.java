package com.codelens.llm;

import com.codelens.llm.providers.ClaudeOpusProvider;
import com.codelens.llm.providers.ClaudeProvider;
import com.codelens.llm.providers.GeminiProProvider;
import com.codelens.llm.providers.GeminiProvider;
import com.codelens.llm.providers.GeminiThinkingProvider;
import com.codelens.llm.providers.OllamaProvider;
import com.codelens.llm.providers.OpenAiProvider;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link LlmProviderFactory} builds its registry with {@code Collectors.toMap} keyed on
 * provider name, which throws on a duplicate key. A clashing name therefore does not
 * degrade gracefully — it stops the application from starting. Adding a provider is
 * exactly when that mistake gets made, so the names are pinned here.
 *
 * <p>The names are also the values written in {@code codelens.llm.routing.*}, so renaming
 * one silently breaks routing configuration and deployment environment variables.
 */
class LlmProviderRegistrationTest {

    private static List<LlmProvider> allProviders() {
        return List.of(
            new GeminiProvider(),
            new GeminiThinkingProvider(),
            new GeminiProProvider(),
            new ClaudeProvider(),
            new ClaudeOpusProvider(),
            new OpenAiProvider(),
            new OllamaProvider());
    }

    @Test
    void providerNamesAreUniqueSoTheRegistryCanBeBuilt() {
        List<String> names = allProviders().stream().map(LlmProvider::getName).toList();
        assertEquals(names.size(), Set.copyOf(names).size(),
            "duplicate provider name would fail application startup: " + names);
    }

    @Test
    void theFactoryAcceptsThemAll() {
        LlmProviderFactory factory = assertDoesNotThrow(() -> new LlmProviderFactory(allProviders()));
        assertNotNull(factory);
    }

    @Test
    void routableNamesAreStable() {
        // These strings appear in application.yaml routing and in deployment env vars.
        assertEquals(
            Set.of("gemini", "gemini-thinking", "gemini-pro", "claude", "claude-opus",
                   "openai", "ollama"),
            allProviders().stream().map(LlmProvider::getName).collect(Collectors.toSet()));
    }
}
