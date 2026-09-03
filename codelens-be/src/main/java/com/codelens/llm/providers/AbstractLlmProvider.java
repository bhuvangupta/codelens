package com.codelens.llm.providers;

import com.codelens.llm.LlmProvider;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.Response;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Abstract base class for LangChain4j-based LLM providers
 */
@Slf4j
public abstract class AbstractLlmProvider implements LlmProvider {

    protected abstract ChatLanguageModel createChatModel();

    private ChatLanguageModel chatModel;

    protected ChatLanguageModel getChatModel() {
        if (chatModel == null) {
            chatModel = createChatModel();
        }
        return chatModel;
    }

    @Override
    public LlmResponse generate(String prompt) {
        return chat(List.of(Map.of("role", "user", "content", prompt)));
    }

    /**
     * Native response format for a requested shape, or null when this provider cannot
     * enforce it. Subclasses that support structured output override this.
     */
    protected ResponseFormat responseFormatFor(LlmProvider.ResponseShape shape) {
        return null;
    }

    @Override
    public LlmResponse chat(List<Map<String, String>> messages) {
        return chat(messages, ResponseShape.FREE_TEXT);
    }

    @Override
    public LlmResponse chat(List<Map<String, String>> messages, ResponseShape shape) {
        try {
            List<ChatMessage> chatMessages = new ArrayList<>();

            for (Map<String, String> msg : messages) {
                String role = msg.get("role");
                String content = msg.get("content");

                if ("system".equals(role)) {
                    chatMessages.add(SystemMessage.from(content));
                } else if ("assistant".equals(role)) {
                    chatMessages.add(AiMessage.from(content));
                } else {
                    chatMessages.add(UserMessage.from(content));
                }
            }

            // Only take the structured-output path when this provider can actually
            // enforce the shape. Everything else stays on the long-proven legacy call,
            // and the response format is attached per request so that one shared model
            // instance can still serve free-text tasks such as the PR summary.
            ResponseFormat responseFormat =
                shape == null || shape == ResponseShape.FREE_TEXT ? null : responseFormatFor(shape);

            Response<AiMessage> response;
            if (responseFormat != null) {
                ChatResponse chatResponse = getChatModel().chat(ChatRequest.builder()
                    .messages(chatMessages)
                    .responseFormat(responseFormat)
                    .build());
                response = Response.from(
                    chatResponse.aiMessage(), chatResponse.tokenUsage(), chatResponse.finishReason());
            } else {
                response = getChatModel().generate(chatMessages);
            }

            // Get token counts
            int inputTokens = 0;
            int outputTokens = 0;

            if (response.tokenUsage() != null) {
                inputTokens = response.tokenUsage().inputTokenCount();
                outputTokens = response.tokenUsage().outputTokenCount();
            } else {
                // Estimate if not provided
                inputTokens = estimateTokens(messages);
                outputTokens = estimateTokens(response.content().text());
            }

            // A truncated or filtered completion is the usual cause of unparseable
            // review JSON. Surface it instead of letting the parser fail quietly.
            FinishReason finishReason = response.finishReason();
            boolean truncated = finishReason == FinishReason.LENGTH;
            if (truncated) {
                log.warn("Provider {} ({}) hit the output token limit after {} output tokens; "
                        + "response is truncated and may not parse. Raise max-output-tokens.",
                    getName(), modelName(), outputTokens);
            } else if (finishReason == FinishReason.CONTENT_FILTER) {
                log.warn("Provider {} ({}) stopped on a content filter; response is incomplete.",
                    getName(), modelName());
            }

            return new LlmResponse(
                response.content().text(),
                inputTokens,
                outputTokens,
                truncated
            );

        } catch (Exception e) {
            log.error("LLM chat failed for provider {}: {}", getName(), e.getMessage(), e);
            throw new RuntimeException("LLM chat failed: " + e.getMessage(), e);
        }
    }

    /**
     * Model identifier for logging. Subclasses override to name the concrete model.
     */
    protected String modelName() {
        return "unknown";
    }

    /**
     * Rough token estimation (4 chars per token on average)
     */
    protected int estimateTokens(String text) {
        if (text == null) return 0;
        return Math.max(1, text.length() / 4);
    }

    protected int estimateTokens(List<Map<String, String>> messages) {
        int total = 0;
        for (Map<String, String> msg : messages) {
            total += estimateTokens(msg.get("content"));
        }
        return total;
    }
}
