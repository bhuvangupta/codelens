package dev.langchain4j.model.googleai;

import com.codelens.llm.ReviewIssueSchema;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Lives in LangChain4j's own package because {@code SchemaMapper} and {@code GeminiSchema}
 * are package-private there.
 *
 * <p>The review contract is a JSON array at the root. Whether the pinned LangChain4j
 * version can translate an array root into Gemini's schema type decides the whole design:
 * if it could not, the contract would have to become a wrapped object such as
 * {@code {"issues": [...]}} and the response parser would have to change with it. That is
 * an assumption about a third-party library, so it is asserted rather than trusted.
 */
class GeminiSchemaMappingTest {

    @Test
    void arrayRootedReviewSchemaTranslatesToGeminiSchema() {
        GeminiSchema mapped = assertDoesNotThrow(() ->
            SchemaMapper.fromJsonSchemaToGSchema(ReviewIssueSchema.responseFormat().jsonSchema()));

        assertNotNull(mapped, "mapper returned no schema");
        assertNotNull(mapped.getItems(), "array root lost its item schema in translation");
        assertNotNull(mapped.getItems().getProperties(), "issue object lost its properties");
        assertTrue(mapped.getItems().getProperties().keySet().containsAll(
                java.util.Set.of("line", "severity", "category", "rule",
                                 "message", "suggestion", "confidence")),
            "issue properties did not survive translation: " + mapped.getItems().getProperties().keySet());
    }
}
