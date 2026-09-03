package com.codelens.llm;

import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchema;

import java.util.List;

/**
 * The single canonical contract for a review response: a JSON array of issue objects.
 *
 * <p>The review prompts have always asked for this shape in prose, and the response was
 * recovered afterwards by scraping fenced code blocks. Providers that support native
 * structured output are given this schema instead, so the shape is enforced rather than
 * requested.
 *
 * <p>This is deliberately the only place the allowed severity, category and confidence
 * values are written down for the model. The category list matches
 * {@code ReviewIssue.Category}; earlier prompts emitted framework-specific values such as
 * SPRING and REACT that no enum constant matched, and those silently became SMELL, filing
 * framework bugs as code smells. The framework signal now lives in {@code rule}.
 */
public final class ReviewIssueSchema {

    /** Matches ReviewIssue.Severity. */
    public static final List<String> SEVERITIES =
        List.of("CRITICAL", "HIGH", "MEDIUM", "LOW", "INFO");

    /** The review-relevant subset of ReviewIssue.Category. */
    public static final List<String> CATEGORIES =
        List.of("SECURITY", "BUG", "LOGIC", "PERFORMANCE", "SMELL");

    /** Matches ReviewIssue.Confidence. */
    public static final List<String> CONFIDENCES =
        List.of("HIGH", "MEDIUM", "LOW");

    private static final ResponseFormat REVIEW_ISSUES_FORMAT = buildFormat();

    private ReviewIssueSchema() {}

    public static ResponseFormat responseFormat() {
        return REVIEW_ISSUES_FORMAT;
    }

    private static ResponseFormat buildFormat() {
        JsonObjectSchema issue = JsonObjectSchema.builder()
            .description("One reviewable problem found on a changed line.")
            .addIntegerProperty("line", "Line number in the new version of the file.")
            .addEnumProperty("severity", SEVERITIES, "Impact if this ships.")
            .addEnumProperty("category", CATEGORIES, "Kind of problem.")
            .addStringProperty("rule", "Short kebab-case identifier, e.g. missing-null-check.")
            .addStringProperty("message", "What is wrong and why it matters.")
            .addStringProperty("existing_code", "The problematic code, verbatim.")
            .addStringProperty("suggestion", "The corrected code.")
            .addEnumProperty("confidence", CONFIDENCES, "Certainty this is a real problem.")
            // existing_code is omitted: the security-scan prompt does not ask for it.
            .required("line", "severity", "category", "rule", "message", "suggestion", "confidence")
            .build();

        return ResponseFormat.builder()
            .type(ResponseFormatType.JSON)
            .jsonSchema(JsonSchema.builder()
                .name("ReviewIssues")
                .rootElement(JsonArraySchema.builder()
                    .description("Every issue found. Empty when the change is acceptable.")
                    .items(issue)
                    .build())
                .build())
            .build();
    }
}
