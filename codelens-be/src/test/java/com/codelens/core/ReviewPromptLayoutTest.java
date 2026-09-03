package com.codelens.core;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guards the prompt layout that makes implicit prefix caching possible.
 *
 * <p>Providers only discount a prompt when a long <em>leading</em> prefix is byte
 * identical to an earlier request. These templates therefore have to keep everything
 * that is constant across the files of one pull request (persona, guidelines, response
 * format, then project rules and learned hints) ahead of everything that varies per
 * file (filename, diff, extracted context, graph, manifest).
 *
 * <p>Moving the diff back towards the top would silently cost cache hits on every
 * review without failing anything else, so it is asserted here.
 */
class ReviewPromptLayoutTest {

    private static final String PROMPT_DIR = "/prompts/";

    private static String load(String name) throws IOException {
        try (InputStream in = ReviewPromptLayoutTest.class.getResourceAsStream(PROMPT_DIR + name)) {
            assertNotNull(in, "prompt template not found on classpath: " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Nested
    class Placeholders {

        @ParameterizedTest
        @ValueSource(strings = {"review.txt", "review-java.txt", "review-javascript.txt",
            "review-python.txt", "review-go.txt", "review-rust.txt"})
        void everyTemplateDeclaresEachPlaceholderExactlyOnce(String template) throws IOException {
            String body = load(template);
            for (String placeholder : List.of("{{filename}}", "{{patch}}", "{{file_content}}",
                    "{{project_context}}", "{{file_context}}")) {
                assertEquals(1, countOf(body, placeholder),
                    template + " must contain " + placeholder + " exactly once");
            }
        }

        private int countOf(String haystack, String needle) {
            int count = 0;
            int at = haystack.indexOf(needle);
            while (at >= 0) {
                count++;
                at = haystack.indexOf(needle, at + needle.length());
            }
            return count;
        }
    }

    @Nested
    class CacheableOrdering {

        @ParameterizedTest
        @ValueSource(strings = {"review.txt", "review-java.txt", "review-javascript.txt",
            "review-python.txt", "review-go.txt", "review-rust.txt"})
        void prInvariantContentPrecedesPerFileContent(String template) throws IOException {
            String body = load(template);

            int projectContext = body.indexOf("{{project_context}}");
            int filename = body.indexOf("{{filename}}");
            int patch = body.indexOf("{{patch}}");
            int fileContent = body.indexOf("{{file_content}}");
            int fileContext = body.indexOf("{{file_context}}");

            assertTrue(projectContext < filename,
                template + ": project context must precede the filename");
            assertTrue(projectContext < patch,
                template + ": project context must precede the diff");
            assertTrue(projectContext < fileContent,
                template + ": project context must precede the extracted file context");
            assertTrue(patch < fileContext,
                template + ": per-file graph/manifest context comes after the diff");
        }

        @ParameterizedTest
        @ValueSource(strings = {"review.txt", "review-java.txt", "review-javascript.txt",
            "review-python.txt", "review-go.txt", "review-rust.txt"})
        void sharedPrefixIsSubstantial(String template) throws IOException {
            String body = load(template);
            int firstVariable = body.indexOf("{{project_context}}");
            assertTrue(firstVariable > 1000,
                template + ": only " + firstVariable + " constant chars precede the first "
                    + "substitution; the cacheable prefix should be most of the template");
        }
    }

    @Nested
    class Accuracy {

        @ParameterizedTest
        @ValueSource(strings = {"review-python.txt", "review-go.txt", "review-rust.txt"})
        void doesNotClaimToSendTheWholeFile(String template) throws IOException {
            // SmartContextExtractor never sends full file bodies; a template that says it
            // does encourages the model to reason about code it cannot see.
            assertFalse(load(template).contains("Full File Content"),
                template + " must not promise full file content");
        }
    }
}
