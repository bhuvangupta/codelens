package com.codelens.core;

import com.codelens.llm.LlmProvider;
import com.codelens.llm.LlmRouter;
import com.codelens.model.entity.ReviewIssue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.ResourceLoader;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The verifier used to receive strictly less evidence than the reviewer: the diff alone,
 * with no extracted file context and no call-graph block. It was nevertheless instructed
 * to refute anything relying on "code defined elsewhere", so a correct finding about a
 * caller or an injected dependency was refutable purely because the evidence had been
 * withheld from it.
 *
 * <p>These tests use the real prompt template, so they also catch a placeholder being
 * renamed in the template without the code being updated, which would otherwise ship a
 * literal "{{graph_context}}" to the model.
 */
@ExtendWith(MockitoExtension.class)
class VerificationContextParityTest {

    @Mock private LlmRouter llmRouter;

    private VerificationService service;
    private final ResourceLoader realLoader = new DefaultResourceLoader();

    @BeforeEach
    void setUp() {
        service = new VerificationService(llmRouter, realLoader);
    }

    private static ReviewIssue issue() {
        ReviewIssue i = new ReviewIssue();
        i.setLineNumber(42);
        i.setRule("missing-null-check");
        i.setSeverity(ReviewIssue.Severity.HIGH);
        i.setMessage("user may be null here");
        i.setSuggestion("if (user == null) return Optional.empty();");
        i.setConfidence(ReviewIssue.Confidence.MEDIUM);
        return i;
    }

    private String capturePrompt() {
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(llmRouter).generate(prompt.capture(), anyString());
        return prompt.getValue();
    }

    @Nested
    class PromptContents {

        @BeforeEach
        void stubModel() {
            when(llmRouter.generate(anyString(), anyString()))
                .thenReturn(new LlmProvider.LlmResponse(
                    "[{\"index\":0,\"verdict\":\"CONFIRMED\",\"reason\":\"visible\"}]", 10, 5));
        }

        @Test
        void carriesTheExtractedFileContext() {
            service.verify("src/A.java", "@@ -1 +1 @@\n+code",
                "class A { private final Repo repo; }", null, List.of(issue()));

            assertTrue(capturePrompt().contains("class A { private final Repo repo; }"),
                "the verifier must see the same extracted context as the reviewer");
        }

        @Test
        void carriesTheCodeIntelligenceBlock() {
            service.verify("src/A.java", "@@ -1 +1 @@\n+code", null,
                "### Callers of changed functions\n- OrderService.place (src/Order.java:12)",
                List.of(issue()));

            assertTrue(capturePrompt().contains("OrderService.place"),
                "call-graph relationships are the evidence for cross-file findings");
        }

        @Test
        void carriesTheProposedFixSoItCanBeCheckedAgainstTheCode() {
            service.verify("src/A.java", "@@ -1 +1 @@\n+code", null, null, List.of(issue()));

            assertTrue(capturePrompt().contains("if (user == null) return Optional.empty();"),
                "a fix the code already performs is grounds to refute, but only if shown");
        }

        @Test
        void stillCarriesTheDiffAndTheFinding() {
            service.verify("src/A.java", "@@ -1 +1 @@\n+risky()", null, null, List.of(issue()));

            String prompt = capturePrompt();
            assertTrue(prompt.contains("+risky()"));
            assertTrue(prompt.contains("missing-null-check"));
            assertTrue(prompt.contains("src/A.java"));
        }

        @Test
        void leavesNoUnsubstitutedPlaceholders() {
            service.verify("src/A.java", "@@ -1 +1 @@\n+code", "ctx", "graph", List.of(issue()));

            String prompt = capturePrompt();
            assertFalse(prompt.contains("{{"),
                "an unsubstituted placeholder would be sent to the model verbatim:\n" + prompt);
        }

        @Test
        void omitsContextSectionsEntirelyWhenAbsent() {
            service.verify("src/A.java", "@@ -1 +1 @@\n+code", null, null, List.of(issue()));

            String prompt = capturePrompt();
            assertFalse(prompt.contains("{{"), "placeholders must still be cleared");
            assertFalse(prompt.contains("Relevant Context"),
                "an empty context heading is noise that invites speculation");
        }

        @Test
        void blankContextIsTreatedAsAbsent() {
            service.verify("src/A.java", "@@ -1 +1 @@\n+code", "   ", "  ", List.of(issue()));

            assertFalse(capturePrompt().contains("Relevant Context"));
        }
    }

    @Nested
    class Compatibility {

        @Test
        void theDiffOnlyOverloadStillWorks() {
            when(llmRouter.generate(anyString(), anyString()))
                .thenReturn(new LlmProvider.LlmResponse(
                    "[{\"index\":0,\"verdict\":\"REFUTED\",\"reason\":\"not visible\"}]", 10, 5));

            VerificationService.VerificationOutcome outcome =
                service.verify("src/A.java", "@@ -1 +1 @@\n+code", List.of(issue()));

            assertEquals(1, outcome.decisions().dropped().size(),
                "the older three-argument call must keep behaving exactly as before");
        }

        @Test
        void contextDoesNotDisableFailOpen() {
            when(llmRouter.generate(anyString(), anyString()))
                .thenThrow(new RuntimeException("provider down"));

            VerificationService.VerificationOutcome outcome =
                service.verify("src/A.java", "patch", "ctx", "graph", List.of(issue()));

            assertTrue(outcome.decisions().dropped().isEmpty(),
                "verification must never remove findings when it could not run");
            assertTrue(outcome.decisions().demoted().isEmpty());
        }
    }
}
