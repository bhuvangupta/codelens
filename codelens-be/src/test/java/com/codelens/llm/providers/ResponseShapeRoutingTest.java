package com.codelens.llm.providers;

import com.codelens.llm.LlmProvider;
import com.codelens.llm.ReviewIssueSchema;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A single provider bean serves review, security scan, PR summary, ticket scope and
 * verification, and those want different output shapes. The response format is therefore
 * attached per request rather than baked into the model at construction. If it were baked
 * in, enabling structured output for reviews would also force the PR summary — which is
 * markdown — to come back as a JSON array of issues.
 */
class ResponseShapeRoutingTest {

    /** Records which call path was taken and what response format came with it. */
    static class RecordingModel implements ChatLanguageModel {
        boolean legacyGenerateCalled;
        boolean chatRequestCalled;
        ResponseFormat seenResponseFormat;

        @Override
        public Response<AiMessage> generate(List<ChatMessage> messages) {
            legacyGenerateCalled = true;
            return Response.from(AiMessage.from("[]"), new TokenUsage(10, 5), FinishReason.STOP);
        }

        @Override
        public ChatResponse chat(ChatRequest request) {
            chatRequestCalled = true;
            seenResponseFormat = request.responseFormat();
            return ChatResponse.builder()
                .aiMessage(AiMessage.from("[]"))
                .tokenUsage(new TokenUsage(10, 5))
                .finishReason(FinishReason.STOP)
                .build();
        }
    }

    /** Provider that can enforce the review shape, like Gemini. */
    static class StructuredProvider extends AbstractLlmProvider {
        final RecordingModel model = new RecordingModel();

        @Override public String getName() { return "structured-test"; }
        @Override public boolean isEnabled() { return true; }
        @Override public double estimateCost(int in, int out) { return 0; }
        @Override protected ChatLanguageModel createChatModel() { return model; }

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
        @Override protected ChatLanguageModel createChatModel() { return model; }
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

            assertTrue(provider.model.chatRequestCalled, "should use the ChatRequest path");
            assertFalse(provider.model.legacyGenerateCalled);
            assertSame(ReviewIssueSchema.responseFormat(), provider.model.seenResponseFormat);
        }

        @Test
        void freeTextCallsAreLeftUnconstrained() {
            StructuredProvider provider = new StructuredProvider();
            provider.chat(prompt(), LlmProvider.ResponseShape.FREE_TEXT);

            assertTrue(provider.model.legacyGenerateCalled,
                "a summary must not be forced into the review-issue schema");
            assertFalse(provider.model.chatRequestCalled);
            assertNull(provider.model.seenResponseFormat);
        }

        @Test
        void theDefaultChatOverloadIsFreeText() {
            StructuredProvider provider = new StructuredProvider();
            provider.chat(prompt());

            assertTrue(provider.model.legacyGenerateCalled);
            assertFalse(provider.model.chatRequestCalled);
        }

        @Test
        void aNullShapeIsTreatedAsFreeText() {
            StructuredProvider provider = new StructuredProvider();
            assertDoesNotThrow(() -> provider.chat(prompt(), null));
            assertTrue(provider.model.legacyGenerateCalled);
        }
    }

    @Nested
    class WhenProviderCannotEnforceSchemas {

        @Test
        void reviewCallsStillSucceedOnTheLegacyPath() {
            PlainProvider provider = new PlainProvider();
            LlmProvider.LlmResponse response =
                provider.chat(prompt(), LlmProvider.ResponseShape.REVIEW_ISSUES);

            assertTrue(provider.model.legacyGenerateCalled,
                "a provider without schema support must not be routed through ChatRequest");
            assertFalse(provider.model.chatRequestCalled);
            assertEquals("[]", response.content());
            assertEquals(10, response.inputTokens());
            assertEquals(5, response.outputTokens());
        }
    }

    @Nested
    class TokenAndTruncationReporting {

        @Test
        void structuredPathStillReportsUsageAndFinishReason() {
            StructuredProvider provider = new StructuredProvider();
            LlmProvider.LlmResponse response =
                provider.chat(prompt(), LlmProvider.ResponseShape.REVIEW_ISSUES);

            assertEquals(10, response.inputTokens());
            assertEquals(5, response.outputTokens());
            assertFalse(response.truncated());
        }

        @Test
        void structuredPathFlagsTruncation() {
            StructuredProvider provider = new StructuredProvider() {
                @Override protected ChatLanguageModel createChatModel() {
                    return new RecordingModel() {
                        @Override public ChatResponse chat(ChatRequest request) {
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
