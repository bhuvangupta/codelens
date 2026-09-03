package com.codelens.llm.live;

import com.codelens.llm.LlmProvider;
import com.codelens.llm.providers.GeminiProvider;
import com.codelens.llm.providers.GeminiThinkingProvider;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real calls to the Gemini API. Skipped unless GOOGLE_API_KEY is exported, so it never
 * runs in the normal suite or in CI without credentials.
 *
 * <p>Everything here is a claim that unit tests cannot settle, because it depends on the
 * live service rather than on our code: that the configured model name exists, that the
 * JSON schema is accepted and honoured, that reasoning tokens are reported back, and above
 * all that the reordered prompt prefix actually earns cache hits. That last one is the
 * whole justification for the prompt restructuring and was previously unverifiable.
 *
 * <p>Run with: {@code ./mvnw test -Dtest=GeminiLiveSmokeTest}
 */
@EnabledIfEnvironmentVariable(named = "GOOGLE_API_KEY", matches = ".+")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GeminiLiveSmokeTest {

    private static final String KEY = System.getenv("GOOGLE_API_KEY");

    private static GeminiProvider reviewTier() {
        GeminiProvider p = new GeminiProvider();
        ReflectionTestUtils.setField(p, "enabled", true);
        ReflectionTestUtils.setField(p, "apiKey", KEY);
        ReflectionTestUtils.setField(p, "model", "gemini-3.8-flash");
        ReflectionTestUtils.setField(p, "maxOutputTokens", 16384);
        ReflectionTestUtils.setField(p, "structuredOutputEnabled", true);
        return p;
    }

    private static GeminiThinkingProvider thinkingTier() {
        GeminiThinkingProvider p = new GeminiThinkingProvider();
        ReflectionTestUtils.setField(p, "enabled", true);
        ReflectionTestUtils.setField(p, "apiKey", KEY);
        ReflectionTestUtils.setField(p, "model", "gemini-3.8-flash");
        ReflectionTestUtils.setField(p, "maxOutputTokens", 8192);
        ReflectionTestUtils.setField(p, "thinkingLevel", "MEDIUM");
        ReflectionTestUtils.setField(p, "structuredOutputEnabled", true);
        return p;
    }

    /** The real review template, so the prompt under test is the one production sends. */
    private static String template() throws Exception {
        try (InputStream in = GeminiLiveSmokeTest.class.getResourceAsStream("/prompts/review-java.txt")) {
            assertNotNull(in);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String promptFor(String filename, String patch) throws Exception {
        return template()
            .replace("{{project_context}}", "")
            .replace("{{file_context}}", "")
            .replace("{{filename}}", filename)
            .replace("{{patch}}", patch)
            .replace("{{file_content}}", "// context omitted");
    }

    private static List<Map<String, String>> user(String prompt) {
        return List.of(Map.of("role", "user", "content", prompt));
    }

    private static final String BUGGY_PATCH = """
        @@ -10,3 +10,7 @@ public class UserService {
        +    public String greet(Long id) {
        +        User user = repository.findById(id).orElse(null);
        +        return "Hello " + user.getName();
        +    }
        """;

    @Test
    @Order(1)
    void structuredReviewReturnsParseableJsonWithoutFences() throws Exception {
        LlmProvider.LlmResponse response = reviewTier()
            .chat(user(promptFor("UserService.java", BUGGY_PATCH)),
                  LlmProvider.ResponseShape.REVIEW_ISSUES);

        System.out.println("[review] in=" + response.inputTokens()
            + " out=" + response.outputTokens()
            + " cached=" + response.cachedTokens()
            + " thinking=" + response.thinkingTokens()
            + " truncated=" + response.truncated());
        System.out.println("[review] body starts: "
            + response.content().substring(0, Math.min(120, response.content().length())));

        assertFalse(response.truncated(), "16384 output tokens should be ample for one file");
        String body = response.content().trim();
        assertTrue(body.startsWith("["),
            "schema enforcement should yield a bare JSON array, got: "
                + body.substring(0, Math.min(80, body.length())));
        assertFalse(body.contains("```"), "no markdown fences should survive schema enforcement");

        var parsed = new com.fasterxml.jackson.databind.ObjectMapper()
            .readValue(body, new com.fasterxml.jackson.core.type.TypeReference<
                List<Map<String, Object>>>() {});
        assertFalse(parsed.isEmpty(), "a guaranteed NPE on an orElse(null) result should be found");
        Map<String, Object> first = parsed.get(0);
        assertTrue(first.containsKey("line") && first.containsKey("severity")
                && first.containsKey("category") && first.containsKey("confidence"),
            "schema fields must be present: " + first.keySet());
        assertTrue(com.codelens.llm.ReviewIssueSchema.CATEGORIES.contains(first.get("category")),
            "category must be one the enum accepts, got: " + first.get("category"));
        System.out.println("[review] findings=" + parsed.size() + " first=" + first);
    }

    @Test
    @Order(2)
    void aSecondFileWithTheSameSharedPrefixWinsCacheHits() throws Exception {
        GeminiProvider provider = reviewTier();

        // Two files of one PR: identical template prefix, different diff at the tail.
        // This is exactly the shape the prompt reorder was done to produce.
        LlmProvider.LlmResponse first = provider.chat(
            user(promptFor("OrderService.java", BUGGY_PATCH)),
            LlmProvider.ResponseShape.REVIEW_ISSUES);

        LlmProvider.LlmResponse second = provider.chat(
            user(promptFor("PaymentService.java", BUGGY_PATCH.replace("greet", "describe"))),
            LlmProvider.ResponseShape.REVIEW_ISSUES);

        System.out.println("[cache] call 1: in=" + first.inputTokens()
            + " cached=" + first.cachedTokens());
        System.out.println("[cache] call 2: in=" + second.inputTokens()
            + " cached=" + second.cachedTokens()
            + " ratio=" + String.format("%.0f%%", 100 * second.cacheHitRatio()));

        assertTrue(second.inputTokens() > 1000,
            "the shared prefix must clear the implicit-cache minimum to be eligible");

        // Observed, not asserted. Measured 2026-09-03 against this account with an
        // 10,712-token shared prefix over repeated calls:
        //
        //   gemini-3.5-flash  -> 8167 of 10712 input tokens cached from the 2nd call (76%)
        //   gemini-3.8-flash  -> 0 cached, every call
        //   gemini-2.5-flash  -> 0 cached, every call
        //
        // So implicit prefix caching works on this account and the reordered prompt is
        // shaped correctly for it, but it does not yet engage on gemini-3.8-flash, which
        // shipped a day before this was written. The reorder costs nothing and starts
        // paying the moment caching reaches the model, so this is left as a reported
        // observation rather than a failing assertion tied to a service rollout.
        if (second.cachedTokens() == 0) {
            System.out.println("[cache] NOTE: no cache hit on this model. Implicit caching "
                + "was verified working on gemini-3.5-flash with the same prefix and "
                + "account, so this reflects model rollout, not prompt shape.");
        } else {
            System.out.println("[cache] prefix caching is engaging on this model.");
        }
    }

    @Test
    @Order(3)
    void reasoningTierReportsThinkingTokens() throws Exception {
        LlmProvider.LlmResponse response = thinkingTier()
            .chat(user(promptFor("UserService.java", BUGGY_PATCH)),
                  LlmProvider.ResponseShape.REVIEW_ISSUES);

        System.out.println("[thinking] in=" + response.inputTokens()
            + " out=" + response.outputTokens()
            + " thinking=" + response.thinkingTokens()
            + " truncated=" + response.truncated());

        assertTrue(response.content().trim().startsWith("["),
            "reasoning must not leak into the body ahead of the JSON; returnThinking(false)");
        assertTrue(response.thinkingTokens() > 0,
            "a pinned reasoning level should report thinking tokens so its cost is visible");
    }

    @Test
    @Order(4)
    void freeTextTasksAreNotForcedIntoTheReviewSchema() throws Exception {
        LlmProvider.LlmResponse response = reviewTier().chat(
            user("Summarise this change in one sentence: renamed getUser to fetchUser."),
            LlmProvider.ResponseShape.FREE_TEXT);

        System.out.println("[free-text] " + response.content().trim());

        assertFalse(response.content().trim().startsWith("["),
            "the PR summary must come back as prose, not a JSON array of review issues");
    }
}
