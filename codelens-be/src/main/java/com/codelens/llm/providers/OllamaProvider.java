package com.codelens.llm.providers;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Slf4j
@Component
public class OllamaProvider extends AbstractLlmProvider {

    @Value("${codelens.llm.providers.ollama.enabled:true}")
    private boolean enabled;

    @Value("${codelens.llm.providers.ollama.base-url:http://localhost:11434}")
    private String baseUrl;

    @Value("${codelens.llm.providers.ollama.model:deepseek-coder:33b}")
    private String model;

    @Override
    public String getName() {
        return "ollama";
    }

    @Override
    public boolean isEnabled() {
        return enabled && baseUrl != null && !baseUrl.isEmpty();
    }

    @Override
    protected ChatLanguageModel createChatModel() {
        if (!isEnabled()) {
            throw new IllegalStateException("Ollama provider is not enabled or base URL is missing");
        }

        return OllamaChatModel.builder()
            .baseUrl(baseUrl)
            .modelName(model)
            .temperature(0.3)
            .timeout(Duration.ofMinutes(5))
            .build();
    }

    @Override
    public double estimateCost(int inputTokens, int outputTokens) {
        // Ollama is local/free
        return 0.0;
    }
}
