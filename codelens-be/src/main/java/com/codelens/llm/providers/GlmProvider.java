package com.codelens.llm.providers;

import ai.z.openapi.ZaiClient;
import ai.z.openapi.service.model.*;
import com.codelens.llm.LlmProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * GLM (Z.AI) provider using official Z.AI SDK.
 * API Documentation: https://docs.z.ai
 */
@Slf4j
@Component
public class GlmProvider implements LlmProvider {

    @Value("${codelens.llm.providers.glm.enabled:true}")
    private boolean enabled;

    @Value("${codelens.llm.providers.glm.api-key:}")
    private String apiKey;

    @Value("${codelens.llm.providers.glm.model:glm-4.7}")
    private String model;

    @Value("${codelens.llm.providers.glm.temperature:0.3}")
    private double temperature;

    @Value("${codelens.llm.providers.glm.max-tokens:4096}")
    private int maxTokens;

    private ZaiClient client;

    @PostConstruct
    public void init() {
        if (apiKey != null && !apiKey.isEmpty()) {
            this.client = ZaiClient.builder().ofZAI()
                .apiKey(apiKey)
                .build();
            log.info("Z.AI GLM client initialized with model: {}", model);
        } else {
            log.warn("GLM provider not initialized: API key not configured");
        }
    }

    @Override
    public String getName() {
        return "glm";
    }

    public String getModel() {
        return model;
    }

    @Override
    public boolean isEnabled() {
        return enabled && apiKey != null && !apiKey.isEmpty();
    }

    @Override
    public LlmResponse generate(String prompt) {
        return chat(List.of(Map.of("role", "user", "content", prompt)));
    }

    @Override
    public LlmResponse chat(List<Map<String, String>> messages) {
        if (!isEnabled()) {
            throw new IllegalStateException("GLM provider is not enabled or API key is missing");
        }

        try {
            List<ChatMessage> chatMessages = messages.stream()
                .map(m -> ChatMessage.builder()
                    .role(m.get("role"))
                    .content(m.get("content"))
                    .build())
                .collect(Collectors.toList());

            ChatCompletionCreateParams request = ChatCompletionCreateParams.builder()
                .model(model)
                .messages(chatMessages)
                .temperature((float) temperature)
                .maxTokens(maxTokens)
                .stream(false)
                .build();

            log.debug("GLM request: model={}, messages={}", model, messages.size());

            ChatCompletionResponse response = client.chat().createChatCompletion(request);

            if (response.getData() != null &&
                response.getData().getChoices() != null &&
                !response.getData().getChoices().isEmpty()) {

                Object contentObj = response.getData().getChoices().get(0).getMessage().getContent();
                String content = contentObj != null ? contentObj.toString() : "";

                int inputTokens = 0;
                int outputTokens = 0;
                if (response.getData().getUsage() != null) {
                    inputTokens = response.getData().getUsage().getPromptTokens();
                    outputTokens = response.getData().getUsage().getCompletionTokens();
                }

                log.debug("GLM response: {} chars, {} input tokens, {} output tokens",
                    content.length(), inputTokens, outputTokens);

                return new LlmResponse(content, inputTokens, outputTokens);
            }

            throw new RuntimeException("Empty response from GLM API");

        } catch (Exception e) {
            log.error("GLM API call failed", e);
            throw new RuntimeException("GLM API call failed: " + e.getMessage(), e);
        }
    }

    @Override
    public double estimateCost(int inputTokens, int outputTokens) {
        // GLM-4.7 pricing (approximate): $0.001 per 1K tokens
        return (inputTokens + outputTokens) * 0.001 / 1000;
    }
}
