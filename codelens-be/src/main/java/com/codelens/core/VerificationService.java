package com.codelens.core;

import com.codelens.llm.LlmProvider;
import com.codelens.llm.LlmRouter;
import com.codelens.model.entity.ReviewIssue;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Second-pass verification of AI review findings. A skeptic prompt re-checks
 * each finding against the diff; verdicts are applied with a confidence-aware
 * policy: REFUTED findings are dropped, unless the original finding carried
 * HIGH self-reported confidence, in which case it is demoted instead (kept,
 * but with confidence lowered to LOW so it stays out of inline PR comments).
 *
 * Fail-open by design: any failure (LLM error, unparseable response, missing
 * verdict) keeps the findings untouched. Verification may only act on what it
 * affirmatively refuted.
 */
@Slf4j
@Component
public class VerificationService {

    private final LlmRouter llmRouter;
    private final ResourceLoader resourceLoader;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public VerificationService(LlmRouter llmRouter, ResourceLoader resourceLoader) {
        this.llmRouter = llmRouter;
        this.resourceLoader = resourceLoader;
    }

    public record Verdict(int index, String verdict, String reason) {}

    public record Decision(ReviewIssue issue, String reason) {}

    public record VerdictDecisions(List<Decision> dropped, List<Decision> demoted) {
        public static VerdictDecisions none() {
            return new VerdictDecisions(List.of(), List.of());
        }
    }

    public record VerificationOutcome(VerdictDecisions decisions, int inputTokens, int outputTokens) {
        public static VerificationOutcome skipped() {
            return new VerificationOutcome(VerdictDecisions.none(), 0, 0);
        }
    }

    /**
     * Verifies findings with only the diff for context.
     *
     * @param redactedPatch the diff, already passed through SecretRedactor
     */
    public VerificationOutcome verify(String filename, String redactedPatch, List<ReviewIssue> aiIssues) {
        return verify(filename, redactedPatch, null, null, aiIssues);
    }

    /**
     * Verifies findings with the same context the reviewer had.
     *
     * <p>The verifier used to see strictly less than the reviewer: the diff alone, with no
     * extracted file context and no call-graph block. It was nevertheless instructed to
     * refute anything relying on "code defined elsewhere", so a correct finding about a
     * caller or an injected dependency was refutable purely because the evidence had been
     * withheld from it. Giving both passes the same evidence makes the skepticism a
     * judgement about the finding rather than about what happened to be in the prompt.
     *
     * @param redactedPatch  the diff, already passed through SecretRedactor
     * @param fileContext    the extracted file context shown to the reviewer, may be null
     * @param graphContext   the code-intelligence block shown to the reviewer, may be null
     */
    public VerificationOutcome verify(String filename, String redactedPatch, String fileContext,
            String graphContext, List<ReviewIssue> aiIssues) {
        if (aiIssues.isEmpty()) {
            return VerificationOutcome.skipped();
        }
        try {
            String prompt = buildPrompt(filename, redactedPatch, fileContext, graphContext, aiIssues);
            LlmProvider.LlmResponse response = llmRouter.generate(prompt, "verification");
            List<Verdict> verdicts = parseVerdicts(response.content());
            if (verdicts == null) {
                log.warn("Unparseable verification response for {}, keeping all {} findings",
                    filename, aiIssues.size());
                return new VerificationOutcome(VerdictDecisions.none(),
                    response.inputTokens(), response.outputTokens());
            }
            VerdictDecisions decisions = applyVerdicts(aiIssues, verdicts);
            for (Decision d : decisions.dropped()) {
                log.info("Verification refuted finding [{}:{} {}]: {}",
                    filename, d.issue().getLineNumber(), d.issue().getRule(), d.reason());
            }
            for (Decision d : decisions.demoted()) {
                log.info("Verification demoted HIGH-confidence finding [{}:{} {}]: {}",
                    filename, d.issue().getLineNumber(), d.issue().getRule(), d.reason());
            }
            return new VerificationOutcome(decisions, response.inputTokens(), response.outputTokens());
        } catch (Exception e) {
            log.warn("Verification failed for {}, keeping all {} findings: {}",
                filename, aiIssues.size(), e.getMessage());
            return VerificationOutcome.skipped();
        }
    }

    /**
     * Drop policy (project-owner decision):
     * - REFUTED + HIGH self-reported confidence -> demote (the two passes
     *   disagree; surface the uncertainty instead of erasing the finding)
     * - REFUTED + any other confidence -> drop
     * Fail-open invariants (non-negotiable): no verdict, out-of-range index,
     * or unknown verdict string -> the finding is KEPT untouched.
     */
    static VerdictDecisions applyVerdicts(List<ReviewIssue> aiIssues, List<Verdict> verdicts) {
        List<Decision> dropped = new ArrayList<>();
        List<Decision> demoted = new ArrayList<>();
        for (Verdict v : verdicts) {
            if (v.index() < 0 || v.index() >= aiIssues.size()) {
                continue;
            }
            String verdict = v.verdict() == null ? "" : v.verdict().trim();
            if (!"REFUTED".equalsIgnoreCase(verdict)) {
                continue;
            }
            ReviewIssue issue = aiIssues.get(v.index());
            String reason = v.reason() == null ? "no reason given" : v.reason();
            if (issue.getConfidence() == ReviewIssue.Confidence.HIGH) {
                demoted.add(new Decision(issue, reason));
            } else {
                dropped.add(new Decision(issue, reason));
            }
        }
        return new VerdictDecisions(dropped, demoted);
    }

    /** Returns null when no JSON array can be extracted or parsed (caller fails open). */
    List<Verdict> parseVerdicts(String response) {
        String json = extractJsonArray(response);
        if (json == null) {
            return null;
        }
        try {
            List<Map<String, Object>> raw = objectMapper.readValue(
                json, new TypeReference<List<Map<String, Object>>>() {});
            List<Verdict> verdicts = new ArrayList<>();
            for (Map<String, Object> v : raw) {
                if (!(v.get("index") instanceof Number n)) {
                    continue;
                }
                verdicts.add(new Verdict(n.intValue(),
                    (String) v.get("verdict"), (String) v.get("reason")));
            }
            return verdicts;
        } catch (Exception e) {
            return null;
        }
    }

    // Local copy of ReviewEngine's private extraction logic; kept separate
    // deliberately so verification cannot regress review-response parsing.
    private static String extractJsonArray(String response) {
        if (response == null) {
            return null;
        }
        if (response.contains("```json")) {
            int start = response.indexOf("```json") + 7;
            int end = response.indexOf("```", start);
            return (end > start ? response.substring(start, end) : response.substring(start)).trim();
        }
        if (response.contains("```")) {
            int start = response.indexOf("```") + 3;
            int end = response.indexOf("```", start);
            if (end > start) {
                String content = response.substring(start, end).trim();
                if (content.startsWith("[")) {
                    return content;
                }
            }
        }
        String trimmed = response.trim();
        return trimmed.startsWith("[") ? trimmed : null;
    }

    private String buildPrompt(String filename, String redactedPatch, String fileContext,
            String graphContext, List<ReviewIssue> aiIssues) throws IOException {
        List<Map<String, Object>> findings = new ArrayList<>();
        for (int i = 0; i < aiIssues.size(); i++) {
            ReviewIssue issue = aiIssues.get(i);
            Map<String, Object> f = new HashMap<>();
            f.put("index", i);
            f.put("line", issue.getLineNumber());
            f.put("severity", issue.getSeverity() != null ? issue.getSeverity().name() : null);
            f.put("rule", issue.getRule());
            f.put("message", issue.getMessage());
            // The proposed fix is part of the claim. Without it the verifier cannot see
            // that a finding recommends something the surrounding code already does.
            if (issue.getSuggestion() != null) {
                f.put("suggestion", issue.getSuggestion());
            }
            findings.add(f);
        }
        Resource resource = resourceLoader.getResource("classpath:prompts/verify.txt");
        String template = resource.getContentAsString(StandardCharsets.UTF_8);
        return template
            .replace("{{file_context}}", section(
                "## Relevant Context (may be partial)", fileContext))
            .replace("{{graph_context}}", section(null, graphContext))
            .replace("{{filename}}", filename)
            .replace("{{patch}}", redactedPatch == null ? "" : redactedPatch)
            .replace("{{findings}}", objectMapper.writeValueAsString(findings));
    }

    /** Renders an optional prompt section, or nothing at all when absent. */
    private static String section(String heading, String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        return heading == null
            ? "\n" + body + "\n"
            : "\n" + heading + "\n```\n" + body + "\n```\n";
    }
}
