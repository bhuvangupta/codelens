package com.codelens.llm;

import com.codelens.model.entity.ReviewIssue;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The review response contract, enforced in three places that must agree: the schema
 * sent to the model, the enums the parser writes into, and the prose in the prompts.
 * They previously did not agree — the Java and JavaScript prompts asked for categories
 * SPRING, JPA, REACT and ASYNC, none of which are enum constants, so every one of those
 * findings was silently filed as SMELL.
 */
class ReviewIssueSchemaTest {

    private static JsonObjectSchema issueSchema() {
        ResponseFormat format = ReviewIssueSchema.responseFormat();
        JsonSchemaElement root = format.jsonSchema().rootElement();
        assertInstanceOf(JsonArraySchema.class, root, "the review contract is an array at the root");
        JsonSchemaElement items = ((JsonArraySchema) root).items();
        assertInstanceOf(JsonObjectSchema.class, items, "array items must be issue objects");
        return (JsonObjectSchema) items;
    }

    private static List<String> enumValues(JsonObjectSchema issue, String property) {
        JsonSchemaElement element = issue.properties().get(property);
        assertInstanceOf(JsonEnumSchema.class, element, property + " must be a constrained enum");
        return ((JsonEnumSchema) element).enumValues();
    }

    @Nested
    class Shape {

        @Test
        void isJsonWithAnArrayRoot() {
            ResponseFormat format = ReviewIssueSchema.responseFormat();
            assertEquals(ResponseFormatType.JSON, format.type());
            assertNotNull(format.jsonSchema());
            assertInstanceOf(JsonArraySchema.class, format.jsonSchema().rootElement());
        }

        @Test
        void declaresTheEightFieldsTheParserReads() {
            Set<String> properties = issueSchema().properties().keySet();
            assertEquals(
                Set.of("line", "severity", "category", "rule", "message",
                       "existing_code", "suggestion", "confidence"),
                properties);
        }

        @Test
        void requiresEverythingExceptExistingCode() {
            // security-config.txt does not ask for existing_code, so it stays optional.
            List<String> required = issueSchema().required();
            assertTrue(required.containsAll(List.of(
                "line", "severity", "category", "rule", "message", "suggestion", "confidence")));
            assertFalse(required.contains("existing_code"));
        }

    }

    @Nested
    class AgreesWithPersistedEnums {

        @Test
        void everySchemaSeverityIsAnEnumConstant() {
            for (String value : enumValues(issueSchema(), "severity")) {
                assertDoesNotThrow(() -> ReviewIssue.Severity.valueOf(value),
                    value + " is offered to the model but is not a ReviewIssue.Severity");
            }
        }

        @Test
        void everySchemaCategoryIsAnEnumConstant() {
            for (String value : enumValues(issueSchema(), "category")) {
                assertDoesNotThrow(() -> ReviewIssue.Category.valueOf(value),
                    value + " is offered to the model but is not a ReviewIssue.Category");
            }
        }

        @Test
        void everySchemaConfidenceIsAnEnumConstant() {
            for (String value : enumValues(issueSchema(), "confidence")) {
                assertDoesNotThrow(() -> ReviewIssue.Confidence.valueOf(value),
                    value + " is offered to the model but is not a ReviewIssue.Confidence");
            }
        }

        @Test
        void severityListCoversTheWholeEnum() {
            assertEquals(
                Arrays.stream(ReviewIssue.Severity.values()).map(Enum::name).collect(Collectors.toSet()),
                Set.copyOf(ReviewIssueSchema.SEVERITIES));
        }
    }

    @Nested
    class AgreesWithPrompts {

        private static final Pattern CATEGORY_LINE =
            Pattern.compile("\"category\"\\s*:\\s*\"([^\"]+)\"");

        private static String load(String name) throws IOException {
            try (InputStream in = ReviewIssueSchemaTest.class.getResourceAsStream("/prompts/" + name)) {
                assertNotNull(in, "missing prompt: " + name);
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }

        @Test
        void noPromptOffersACategoryTheSchemaForbids() throws IOException {
            List<String> allowed = ReviewIssueSchema.CATEGORIES;
            for (String prompt : List.of("review.txt", "review-java.txt", "review-javascript.txt",
                    "review-python.txt", "review-go.txt", "review-rust.txt", "security-config.txt")) {
                Matcher m = CATEGORY_LINE.matcher(load(prompt));
                assertTrue(m.find(), prompt + " should show a category field in its example");
                for (String value : m.group(1).split("\\|")) {
                    assertTrue(allowed.contains(value.trim()),
                        prompt + " offers category '" + value.trim() + "', which the schema forbids "
                            + "and the parser would silently turn into SMELL");
                }
            }
        }

        @Test
        void noPromptOffersASeverityTheSchemaForbids() throws IOException {
            Pattern severityLine = Pattern.compile("\"severity\"\\s*:\\s*\"([^\"]+)\"");
            for (String prompt : List.of("review.txt", "review-java.txt", "review-javascript.txt",
                    "review-python.txt", "review-go.txt", "review-rust.txt", "security-config.txt")) {
                Matcher m = severityLine.matcher(load(prompt));
                assertTrue(m.find(), prompt + " should show a severity field in its example");
                for (String value : m.group(1).split("\\|")) {
                    assertTrue(ReviewIssueSchema.SEVERITIES.contains(value.trim()),
                        prompt + " offers severity '" + value.trim() + "', which the schema forbids");
                }
            }
        }
    }
}
