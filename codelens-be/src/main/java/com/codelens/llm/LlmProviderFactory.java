package com.codelens.llm;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Component
public class LlmProviderFactory {

    private final Map<String, LlmProvider> providers;

    @org.springframework.beans.factory.annotation.Value("${codelens.llm.default-provider:glm}")
    private String defaultProviderName;

    public LlmProviderFactory(List<LlmProvider> providerList) {
        this.providers = providerList.stream()
            .collect(Collectors.toMap(
                LlmProvider::getName,
                Function.identity()
            ));

        log.info("Registered {} LLM providers: {}",
            providers.size(),
            providers.keySet());
    }

    @PostConstruct
    public void logDefaultProvider() {
        List<String> enabledProviders = getEnabledProviders().stream()
            .map(LlmProvider::getName)
            .toList();

        log.info("===========================================");
        log.info("  LLM Configuration");
        log.info("===========================================");
        log.info("  Default provider: {}", defaultProviderName);
        log.info("  Enabled providers: {}", enabledProviders);

        try {
            LlmProvider defaultProvider = getDefaultProvider();
            log.info("  Active provider: {} ({})", defaultProvider.getName(), defaultProvider.getClass().getSimpleName());
        } catch (Exception e) {
            log.warn("  WARNING: Default provider '{}' is not available!", defaultProviderName);
            log.warn("  Reason: {}", e.getMessage());
        }
        log.info("===========================================");
    }

    /**
     * Get a provider by name
     */
    public Optional<LlmProvider> getProvider(String name) {
        LlmProvider provider = providers.get(name.toLowerCase());
        if (provider != null && provider.isEnabled()) {
            return Optional.of(provider);
        }
        return Optional.empty();
    }

    /**
     * Get a provider by name, throw if not found
     */
    public LlmProvider getProviderOrThrow(String name) {
        return getProvider(name)
            .orElseThrow(() -> new IllegalArgumentException(
                "LLM provider not found or not enabled: " + name));
    }

    /**
     * Get all enabled providers
     */
    public List<LlmProvider> getEnabledProviders() {
        return providers.values().stream()
            .filter(LlmProvider::isEnabled)
            .toList();
    }

    /**
     * Get all provider names
     */
    public List<String> getProviderNames() {
        return providers.keySet().stream().toList();
    }

    /**
     * Check if a provider is available
     */
    public boolean isProviderAvailable(String name) {
        return getProvider(name).isPresent();
    }

    /**
     * Get the default provider
     */
    public LlmProvider getDefaultProvider() {
        return getProvider(defaultProviderName)
            .orElseGet(() -> getEnabledProviders().stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("No LLM providers available")));
    }
}
