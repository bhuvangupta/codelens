package com.codelens.core;

import com.codelens.llm.LlmProvider;
import com.codelens.llm.LlmRouter;
import com.codelens.model.entity.ReviewIssue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VerificationServiceTest {

    @Mock private LlmRouter llmRouter;
    @Mock private ResourceLoader resourceLoader;
    @Mock private Resource promptResource;

    private VerificationService service;

    @BeforeEach
    void setUp() {
        service = new VerificationService(llmRouter, resourceLoader);
    }

    private static ReviewIssue issue(int line, String rule, ReviewIssue.Confidence confidence) {
        ReviewIssue i = new ReviewIssue();
        i.setLineNumber(line);
        i.setRule(rule);
        i.setSeverity(ReviewIssue.Severity.HIGH);
        i.setMessage("msg-" + rule);
        i.setSource(ReviewIssue.Source.AI);
        i.setAnalyzer("ai");
        i.setConfidence(confidence);
        return i;
    }

    // ==================== applyVerdicts (drop policy) ====================

    @Nested
    class ApplyVerdicts {
        @Test
        void refutedMediumOrNullConfidenceIsDropped() {
            List<ReviewIssue> issues = List.of(
                issue(1, "a", ReviewIssue.Confidence.MEDIUM),
                issue(2, "b", null));
            var verdicts = List.of(
                new VerificationService.Verdict(0, "REFUTED", "not in diff"),
                new VerificationService.Verdict(1, "REFUTED", "speculative"));

            var decisions = VerificationService.applyVerdicts(issues, verdicts);

            assertEquals(2, decisions.dropped().size());
            assertTrue(decisions.demoted().isEmpty());
            assertSame(issues.get(0), decisions.dropped().get(0).issue());
            assertEquals("not in diff", decisions.dropped().get(0).reason());
        }

        @Test
        void refutedHighConfidenceIsDemotedNotDropped() {
            List<ReviewIssue> issues = List.of(issue(1, "a", ReviewIssue.Confidence.HIGH));
            var verdicts = List.of(new VerificationService.Verdict(0, "REFUTED", "disputed"));

            var decisions = VerificationService.applyVerdicts(issues, verdicts);

            assertTrue(decisions.dropped().isEmpty());
            assertEquals(1, decisions.demoted().size());
            assertSame(issues.get(0), decisions.demoted().get(0).issue());
        }

        @Test
        void confirmedIsKeptUntouched() {
            List<ReviewIssue> issues = List.of(issue(1, "a", ReviewIssue.Confidence.MEDIUM));
            var verdicts = List.of(new VerificationService.Verdict(0, "CONFIRMED", "visible"));

            var decisions = VerificationService.applyVerdicts(issues, verdicts);

            assertTrue(decisions.dropped().isEmpty());
            assertTrue(decisions.demoted().isEmpty());
        }

        @Test
        void failsOpenOnMissingOutOfRangeOrUnknownVerdicts() {
            List<ReviewIssue> issues = List.of(
                issue(1, "a", ReviewIssue.Confidence.MEDIUM),
                issue(2, "b", ReviewIssue.Confidence.MEDIUM));
            var verdicts = List.of(
                new VerificationService.Verdict(5, "REFUTED", "out of range"),
                new VerificationService.Verdict(-1, "REFUTED", "negative"),
                new VerificationService.Verdict(0, "MAYBE", "unknown verdict"),
                new VerificationService.Verdict(1, null, "null verdict"));

            var decisions = VerificationService.applyVerdicts(issues, verdicts);

            assertTrue(decisions.dropped().isEmpty());
            assertTrue(decisions.demoted().isEmpty());
        }
    }

    // ==================== parseVerdicts ====================

    @Nested
    class ParseVerdicts {
        @Test
        void parsesPlainAndMarkdownWrappedJson() {
            String plain = "[{\"index\":0,\"verdict\":\"REFUTED\",\"reason\":\"r\"}]";
            String wrapped = "```json\n" + plain + "\n```";

            assertEquals(1, service.parseVerdicts(plain).size());
            assertEquals(1, service.parseVerdicts(wrapped).size());
            assertEquals("REFUTED", service.parseVerdicts(plain).get(0).verdict());
        }

        @Test
        void returnsNullOnGarbage() {
            assertNull(service.parseVerdicts("I think these all look fine!"));
            assertNull(service.parseVerdicts(null));
            assertNull(service.parseVerdicts("{\"not\":\"an array\"}"));
        }

        @Test
        void skipsEntriesWithoutNumericIndex() {
            String json = "[{\"verdict\":\"REFUTED\"},{\"index\":1,\"verdict\":\"REFUTED\",\"reason\":\"r\"}]";
            var verdicts = service.parseVerdicts(json);
            assertEquals(1, verdicts.size());
            assertEquals(1, verdicts.get(0).index());
        }
    }

    // ==================== verify (orchestration, fail-open) ====================

    @Nested
    class Verify {
        @Test
        void dropsRefutedFindings() throws Exception {
            when(resourceLoader.getResource(anyString())).thenReturn(promptResource);
            when(promptResource.getContentAsString(StandardCharsets.UTF_8))
                .thenReturn("{{filename}}|{{patch}}|{{findings}}");
            when(llmRouter.generate(anyString(), eq("verification")))
                .thenReturn(new LlmProvider.LlmResponse(
                    "[{\"index\":0,\"verdict\":\"REFUTED\",\"reason\":\"hallucinated\"}]", 10, 5));

            var outcome = service.verify("A.java", "+ code",
                List.of(issue(1, "a", ReviewIssue.Confidence.MEDIUM)));

            assertEquals(1, outcome.decisions().dropped().size());
            assertEquals(10, outcome.inputTokens());
            assertEquals(5, outcome.outputTokens());
        }

        @Test
        void demotesHighConfidenceRefutedFindings() throws Exception {
            when(resourceLoader.getResource(anyString())).thenReturn(promptResource);
            when(promptResource.getContentAsString(StandardCharsets.UTF_8))
                .thenReturn("{{filename}}|{{patch}}|{{findings}}");
            when(llmRouter.generate(anyString(), eq("verification")))
                .thenReturn(new LlmProvider.LlmResponse(
                    "[{\"index\":0,\"verdict\":\"REFUTED\",\"reason\":\"disputed\"}]", 8, 4));

            var outcome = service.verify("A.java", "+ code",
                List.of(issue(1, "a", ReviewIssue.Confidence.HIGH)));

            assertTrue(outcome.decisions().dropped().isEmpty());
            assertEquals(1, outcome.decisions().demoted().size());
            assertEquals(8, outcome.inputTokens());
        }

        @Test
        void failsOpenWhenLlmThrows() throws Exception {
            when(resourceLoader.getResource(anyString())).thenReturn(promptResource);
            when(promptResource.getContentAsString(StandardCharsets.UTF_8))
                .thenReturn("{{filename}}|{{patch}}|{{findings}}");
            when(llmRouter.generate(anyString(), eq("verification")))
                .thenThrow(new RuntimeException("provider down"));

            var outcome = service.verify("A.java", "+ code",
                List.of(issue(1, "a", ReviewIssue.Confidence.MEDIUM)));

            assertTrue(outcome.decisions().dropped().isEmpty());
            assertTrue(outcome.decisions().demoted().isEmpty());
        }

        @Test
        void failsOpenOnUnparseableResponseAndStillCountsTokens() throws Exception {
            when(resourceLoader.getResource(anyString())).thenReturn(promptResource);
            when(promptResource.getContentAsString(StandardCharsets.UTF_8))
                .thenReturn("{{filename}}|{{patch}}|{{findings}}");
            when(llmRouter.generate(anyString(), eq("verification")))
                .thenReturn(new LlmProvider.LlmResponse("not json at all", 7, 3));

            var outcome = service.verify("A.java", "+ code",
                List.of(issue(1, "a", ReviewIssue.Confidence.MEDIUM)));

            assertTrue(outcome.decisions().dropped().isEmpty());
            assertEquals(7, outcome.inputTokens());
        }

        @Test
        void skipsWhenNoIssues() {
            var outcome = service.verify("A.java", "+ code", List.of());
            assertTrue(outcome.decisions().dropped().isEmpty());
            assertEquals(0, outcome.inputTokens());
        }
    }
}
