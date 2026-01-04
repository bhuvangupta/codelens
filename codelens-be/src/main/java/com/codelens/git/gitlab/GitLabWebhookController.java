package com.codelens.git.gitlab;

import com.codelens.model.entity.Repository.GitProvider;
import com.codelens.service.ReviewService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Slf4j
@RestController
@RequestMapping("/api/webhooks/gitlab")
public class GitLabWebhookController {

    private final ReviewService reviewService;
    private final ObjectMapper objectMapper;

    @Value("${codelens.git.gitlab.webhook-secret:}")
    private String webhookSecret;

    public GitLabWebhookController(ReviewService reviewService, ObjectMapper objectMapper) {
        this.reviewService = reviewService;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    public ResponseEntity<String> handleWebhook(
            @RequestHeader("X-Gitlab-Event") String eventType,
            @RequestHeader(value = "X-Gitlab-Token", required = false) String token,
            @RequestBody String payload) {

        log.info("Received GitLab webhook: {}", eventType);

        // Verify token if webhook secret is configured
        // Use constant-time comparison to prevent timing attacks
        if (webhookSecret != null && !webhookSecret.isEmpty()) {
            if (token == null || !MessageDigest.isEqual(
                    webhookSecret.getBytes(StandardCharsets.UTF_8),
                    token.getBytes(StandardCharsets.UTF_8))) {
                log.warn("Invalid webhook token");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid token");
            }
        }

        try {
            switch (eventType) {
                case "Merge Request Hook" -> handleMergeRequestEvent(payload);
                case "Note Hook" -> handleNoteEvent(payload);
                case "System Hook" -> handleSystemHook(payload);
                default -> log.debug("Ignoring event type: {}", eventType);
            }
            return ResponseEntity.ok("OK");
        } catch (Exception e) {
            log.error("Error processing webhook", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body("Error processing webhook: " + e.getMessage());
        }
    }

    private void handleMergeRequestEvent(String payload) throws Exception {
        JsonNode root = objectMapper.readTree(payload);
        JsonNode objectAttributes = root.get("object_attributes");
        String action = objectAttributes.get("action").asText();

        if ("open".equals(action) || "update".equals(action) || "reopen".equals(action)) {
            JsonNode project = root.get("project");

            String pathWithNamespace = project.get("path_with_namespace").asText();
            String[] parts = pathWithNamespace.split("/");
            String owner = parts[0];
            String repoName = parts.length > 1 ? parts[1] : parts[0];

            int mrNumber = objectAttributes.get("iid").asInt();
            String mrUrl = objectAttributes.get("url").asText();

            log.info("MR {} on {}/{}: {}", action, owner, repoName, mrNumber);

            // Trigger async review
            reviewService.submitReviewFromWebhook(
                GitProvider.GITLAB,
                owner,
                repoName,
                mrNumber,
                mrUrl
            );
        }
    }

    private void handleNoteEvent(String payload) throws Exception {
        JsonNode root = objectMapper.readTree(payload);
        String noteableType = root.get("object_attributes").get("noteable_type").asText();

        if ("MergeRequest".equals(noteableType)) {
            log.info("Note added to merge request");
        }
    }

    private void handleSystemHook(String payload) throws Exception {
        JsonNode root = objectMapper.readTree(payload);
        String eventName = root.has("event_name") ? root.get("event_name").asText() : "unknown";
        log.info("System hook event: {}", eventName);
    }
}
