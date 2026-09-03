package com.codelens.intelligence;

import com.codelens.intelligence.model.GraphContext;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The code-intelligence block was the one uncapped input in the review prompt. Repository
 * rules are capped at 5000 characters, learned hints at 2000, the change manifest at 2048,
 * but the graph query applies no LIMIT and the formatter applied no truncation. A change
 * to a widely-called utility could therefore emit an unbounded prompt section, which is
 * both a cost problem and a "lost in the middle" risk for everything after it.
 */
class GraphContextFormattingTest {

    /** formatForPrompt is pure; the collaborators are never touched. */
    private final CodeIntelligenceService service =
        new CodeIntelligenceService(null, null, null);

    private static GraphContext withCallers(int count) {
        return new GraphContext(
            IntStream.range(0, count)
                .mapToObj(n -> new GraphContext.CallerInfo(
                    "caller" + n, "src/Caller" + n + ".java", n, "void caller" + n + "()"))
                .toList(),
            List.of(), List.of(), List.of(), List.of());
    }

    @Nested
    class Truncation {

        @Test
        void capsALongCallerListAndSaysHowManyWereOmitted() {
            String out = service.formatForPrompt(withCallers(500));

            long rendered = out.lines().filter(l -> l.startsWith("- caller")).count();
            assertEquals(CodeIntelligenceService.MAX_ENTRIES_PER_SECTION, rendered,
                "caller list must be capped");
            assertTrue(out.contains("475 more (not shown)"),
                "omission must be disclosed so the model does not read absence as evidence:\n" + out);
        }

        @Test
        void shortListsAreUntouched() {
            String out = service.formatForPrompt(withCallers(3));

            assertEquals(3, out.lines().filter(l -> l.startsWith("- caller")).count());
            assertFalse(out.contains("not shown"));
        }

        @Test
        void oneNoisySectionCannotCrowdOutTheOthers() {
            GraphContext context = new GraphContext(
                withCallers(500).callers(),
                List.of(),
                List.of(new GraphContext.EndpointInfo("POST", "/api/orders", "placeOrder")),
                List.of(new GraphContext.ImplementorInfo("SqlRepo", "Repo", "src/SqlRepo.java")),
                List.of(new GraphContext.InjectorInfo("OrderSvc", "Repo", "src/OrderSvc.java")));

            String out = service.formatForPrompt(context);

            assertTrue(out.contains("/api/orders"), "API impact must survive a noisy caller list");
            assertTrue(out.contains("SqlRepo"), "implementors must survive");
            assertTrue(out.contains("OrderSvc"), "injectors must survive");
        }
    }

    @Nested
    class Content {

        @Test
        void emptyContextProducesNothing() {
            assertEquals("", service.formatForPrompt(
                new GraphContext(List.of(), List.of(), List.of(), List.of(), List.of())));
        }

        @Test
        void missingTestsAreStillReportedAsAWarning() {
            String out = service.formatForPrompt(withCallers(1));

            assertTrue(out.contains("No tests found for changed functions"),
                "absent test coverage is itself a finding and must not be silently omitted");
        }

        @Test
        void keepsTheInstructionFooter() {
            String out = service.formatForPrompt(withCallers(1));

            assertTrue(out.contains("Flag if callers don't handle new error cases"));
        }
    }
}
