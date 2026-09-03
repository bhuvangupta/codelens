package com.codelens.llm;

import com.codelens.service.LlmCostService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The router sits between the call site that knows which shape it wants and the provider
 * that can enforce it. It is a plain pass-through, which is exactly the kind of seam where
 * a parameter gets quietly dropped and structured output silently stops being requested
 * without anything failing.
 */
class LlmRouterShapeTest {

    /** Provider that records the shape it was asked for. */
    static class RecordingProvider implements LlmProvider {
        private final String name;
        final List<ResponseShape> shapesSeen = new ArrayList<>();
        boolean failNext;

        RecordingProvider(String name) { this.name = name; }

        @Override public String getName() { return name; }
        @Override public boolean isEnabled() { return true; }
        @Override public double estimateCost(int in, int out) { return 0; }

        @Override
        public LlmResponse generate(String prompt) {
            return chat(List.of(Map.of("role", "user", "content", prompt)));
        }

        @Override
        public LlmResponse chat(List<Map<String, String>> messages) {
            return chat(messages, ResponseShape.FREE_TEXT);
        }

        @Override
        public LlmResponse chat(List<Map<String, String>> messages, ResponseShape shape) {
            shapesSeen.add(shape);
            if (failNext) {
                failNext = false;
                throw new RuntimeException("simulated provider failure");
            }
            return new LlmResponse("[]", 1, 1);
        }
    }

    private RecordingProvider primary;
    private RecordingProvider fallback;
    private LlmRouter router;

    @BeforeEach
    void setUp() {
        primary = new RecordingProvider("primary");
        fallback = new RecordingProvider("fallback");

        LlmProviderFactory factory = new LlmProviderFactory(List.of(primary, fallback));
        LlmCostService costService = Mockito.mock(LlmCostService.class);
        router = new LlmRouter(factory, costService);

        ReflectionTestUtils.setField(router, "defaultProvider", "primary");
        ReflectionTestUtils.setField(router, "summaryProvider", "primary");
        ReflectionTestUtils.setField(router, "reviewProvider", "primary");
        ReflectionTestUtils.setField(router, "securityProvider", "primary");
        ReflectionTestUtils.setField(router, "verificationProvider", "primary");
        ReflectionTestUtils.setField(router, "fallbackProvider", "fallback");
        ReflectionTestUtils.setField(router, "fallbackEnabled", true);
        ReflectionTestUtils.setField(router, "maxFallbackAttempts", 3);
    }

    @Nested
    class ShapePassThrough {

        @Test
        void reviewShapeReachesTheProvider() {
            router.generate("prompt", "review", LlmProvider.ResponseShape.REVIEW_ISSUES);
            assertEquals(List.of(LlmProvider.ResponseShape.REVIEW_ISSUES), primary.shapesSeen);
        }

        @Test
        void securityShapeReachesTheProvider() {
            router.generate("prompt", "security", LlmProvider.ResponseShape.REVIEW_ISSUES);
            assertEquals(List.of(LlmProvider.ResponseShape.REVIEW_ISSUES), primary.shapesSeen);
        }

        @Test
        void callersThatDoNotAskForAShapeGetFreeText() {
            // The PR summary and ticket-scope calls use this overload and must stay
            // unconstrained, or a markdown summary would be forced into a JSON array.
            router.generate("prompt", "summary");
            assertEquals(List.of(LlmProvider.ResponseShape.FREE_TEXT), primary.shapesSeen);
        }

        @Test
        void verificationIsNotGivenTheReviewShape() {
            // Verification returns verdict objects, not review issues.
            router.generate("prompt", "verification");
            assertEquals(List.of(LlmProvider.ResponseShape.FREE_TEXT), primary.shapesSeen);
        }
    }

    @Nested
    class AcrossFallback {

        @Test
        void theShapeSurvivesFailoverToAnotherProvider() {
            primary.failNext = true;

            LlmProvider.LlmResponse response =
                router.generate("prompt", "review", LlmProvider.ResponseShape.REVIEW_ISSUES);

            assertEquals("[]", response.content());
            assertEquals(List.of(LlmProvider.ResponseShape.REVIEW_ISSUES), primary.shapesSeen);
            assertEquals(List.of(LlmProvider.ResponseShape.REVIEW_ISSUES), fallback.shapesSeen,
                "the fallback provider must be asked for the same shape as the primary");
        }
    }
}
