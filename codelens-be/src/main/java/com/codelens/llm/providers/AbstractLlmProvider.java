package com.codelens.llm.providers;

import com.codelens.llm.LlmProvider;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.ChatMessage;
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

    @Override
    public LlmResponse chat(List<Map<String, String>> messages) {
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

            Response<AiMessage> response = getChatModel().generate(chatMessages);

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

            return new LlmResponse(
                response.content().text(),
                inputTokens,
                outputTokens
            );

        } catch (Exception e) {
            log.error("LLM chat failed for provider {}: {}", getName(), e.getMessage(), e);
            throw new RuntimeException("LLM chat failed: " + e.getMessage(), e);
        }
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
