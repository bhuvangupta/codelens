package com.codelens.llm.providers;

import com.codelens.llm.LlmProvider;
import com.codelens.llm.ReviewIssueSchema;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A single provider bean serves review, security scan, PR summary, ticket scope and
 * verification, and those want different output shapes. The response format is therefore
 * attached to each request rather than baked into the model at construction. If it were
 * baked in, enabling structured output for reviews would also force the PR summary —
 * which is markdown — to come back as a JSON array of issues.
 */
class ResponseShapeRoutingTest {

    /** Records the response format that arrived on the request. */
    static class RecordingModel implements ChatModel {
        ChatRequest lastRequest;

        @Override
        public ChatResponse chat(ChatRequest request) {
            lastRequest = request;
            return ChatResponse.builder()
                .aiMessage(AiMessage.from("[]"))
                .tokenUsage(new TokenUsage(10, 5))
                .finishReason(FinishReason.STOP)
                .build();
        }

        ResponseFormat seenResponseFormat() {
            return lastRequest == null ? null : lastRequest.responseFormat();
        }
    }

    /** Provider that can enforce the review shape, like Gemini. */
    static class StructuredProvider extends AbstractLlmProvider {
        final RecordingModel model = new RecordingModel();

        @Override public String getName() { return "structured-test"; }
        @Override public boolean isEnabled() { return true; }
        @Override public double estimateCost(int in, int out) { return 0; }
        @Override protected ChatModel createChatModel() { return model; }

        @Override
        protected ResponseFormat responseFormatFor(LlmProvider.ResponseShape shape) {
            return shape == LlmProvider.ResponseShape.REVIEW_ISSUES
                ? ReviewIssueSchema.responseFormat() : null;
        }
    }

    /** Provider with no structured-output support, like Ollama or Claude. */
    static class PlainProvider extends AbstractLlmProvider {
        final RecordingModel model = new RecordingModel();

        @Override public String getName() { return "plain-test"; }
        @Override public boolean isEnabled() { return true; }
        @Override public double estimateCost(int in, int out) { return 0; }
        @Override protected ChatModel createChatModel() { return model; }
    }

    private static List<Map<String, String>> prompt() {
        return List.of(Map.of("role", "user", "content", "review this"));
    }

    @Nested
    class WhenProviderSupportsSchemas {

        @Test
        void reviewCallsCarryTheSchema() {
            StructuredProvider provider = new StructuredProvider();
            provider.chat(prompt(), LlmProvider.ResponseShape.REVIEW_ISSUES);

            assertSame(ReviewIssueSchema.responseFormat(), provider.model.seenResponseFormat());
        }

        @Test
        void freeTextCallsAreLeftUnconstrained() {
            StructuredProvider provider = new StructuredProvider();
            provider.chat(prompt(), LlmProvider.ResponseShape.FREE_TEXT);

            assertNull(provider.model.seenResponseFormat(),
                "a markdown summary must not be forced into the review-issue schema");
        }

        @Test
        void theDefaultChatOverloadIsFreeText() {
            StructuredProvider provider = new StructuredProvider();
            provider.chat(prompt());

            assertNull(provider.model.seenResponseFormat());
        }

        @Test
        void aNullShapeIsTreatedAsFreeText() {
            StructuredProvider provider = new StructuredProvider();
            assertDoesNotThrow(() -> provider.chat(prompt(), null));
            assertNull(provider.model.seenResponseFormat());
        }

        @Test
        void messagesStillReachTheModel() {
            StructuredProvider provider = new StructuredProvider();
            provider.chat(prompt(), LlmProvider.ResponseShape.REVIEW_ISSUES);

            assertEquals(1, provider.model.lastRequest.messages().size());
        }
    }

    @Nested
    class WhenProviderCannotEnforceSchemas {

        @Test
        void reviewCallsStillSucceedWithoutASchema() {
            PlainProvider provider = new PlainProvider();
            LlmProvider.LlmResponse response =
                provider.chat(prompt(), LlmProvider.ResponseShape.REVIEW_ISSUES);

            assertNull(provider.model.seenResponseFormat(),
                "a provider that cannot enforce a schema must not be sent one");
            assertEquals("[]", response.content());
            assertEquals(10, response.inputTokens());
            assertEquals(5, response.outputTokens());
        }
    }

    @Nested
    class TokenAndTruncationReporting {

        @Test
        void reportsUsageAndFinishReason() {
            StructuredProvider provider = new StructuredProvider();
            LlmProvider.LlmResponse response =
                provider.chat(prompt(), LlmProvider.ResponseShape.REVIEW_ISSUES);

            assertEquals(10, response.inputTokens());
            assertEquals(5, response.outputTokens());
            assertFalse(response.truncated());
        }

        @Test
        void flagsTruncationEvenWithASchema() {
            StructuredProvider provider = new StructuredProvider() {
                @Override protected ChatModel createChatModel() {
                    return new RecordingModel() {
                        @Override public ChatResponse chat(ChatRequest request) {
                            lastRequest = request;
                            return ChatResponse.builder()
                                .aiMessage(AiMessage.from("[{\"line\":1"))
                                .tokenUsage(new TokenUsage(10, 16384))
                                .finishReason(FinishReason.LENGTH)
                                .build();
                        }
                    };
                }
            };

            LlmProvider.LlmResponse response =
                provider.chat(prompt(), LlmProvider.ResponseShape.REVIEW_ISSUES);

            assertTrue(response.truncated(),
                "a schema does not prevent hitting the output ceiling; truncation must still surface");
        }
    }
}
