package com.codelens.config;

import com.codelens.llm.LlmProvider;
import com.codelens.llm.LlmProviderFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;
import java.util.List;

@Slf4j
@Configuration
public class LlmConfig {

    private final LlmProviderFactory providerFactory;

    public LlmConfig(LlmProviderFactory providerFactory) {
        this.providerFactory = providerFactory;
    }

    @PostConstruct
    public void logProviderStatus() {
        List<LlmProvider> enabledProviders = providerFactory.getEnabledProviders();

        log.info("=== LLM Provider Status ===");
        for (String name : providerFactory.getProviderNames()) {
            boolean available = providerFactory.isProviderAvailable(name);
            log.info("  {} : {}", name, available ? "ENABLED" : "DISABLED");
        }
        log.info("Total enabled providers: {}", enabledProviders.size());

        if (enabledProviders.isEmpty()) {
            log.warn("No LLM providers are enabled! Configure API keys in application.yaml");
        }
    }
}
