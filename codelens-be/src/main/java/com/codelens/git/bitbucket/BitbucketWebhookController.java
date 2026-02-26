package com.codelens.git.bitbucket;

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
@RequestMapping("/api/webhooks/bitbucket")
public class BitbucketWebhookController {

    private final ReviewService reviewService;
    private final ObjectMapper objectMapper;

    @Value("${codelens.git.bitbucket.webhook-secret:}")
    private String webhookSecret;

    public BitbucketWebhookController(ReviewService reviewService, ObjectMapper objectMapper) {
        this.reviewService = reviewService;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    public ResponseEntity<String> handleWebhook(
            @RequestHeader("X-Event-Key") String eventKey,
            @RequestHeader(value = "X-Hook-UUID", required = false) String hookUuid,
            @RequestBody String payload) {

        log.info("Received Bitbucket webhook: {}", eventKey);

        // Verify webhook secret (Hook UUID) if configured
        if (webhookSecret != null && !webhookSecret.isEmpty()) {
            if (hookUuid == null || !MessageDigest.isEqual(
                    webhookSecret.getBytes(StandardCharsets.UTF_8),
                    hookUuid.getBytes(StandardCharsets.UTF_8))) {
                log.warn("Invalid webhook hook UUID");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid hook UUID");
            }
        }

        try {
            switch (eventKey) {
                case "pullrequest:created", "pullrequest:updated" -> handlePullRequestEvent(payload);
                default -> log.debug("Ignoring event: {}", eventKey);
            }
            return ResponseEntity.ok("OK");
        } catch (Exception e) {
            log.error("Error processing Bitbucket webhook", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body("Error processing webhook: " + e.getMessage());
        }
    }

    private void handlePullRequestEvent(String payload) throws Exception {
        JsonNode root = objectMapper.readTree(payload);
        JsonNode pr = root.get("pullrequest");
        JsonNode repository = root.get("repository");

        String fullName = repository.get("full_name").asText();
        String[] parts = fullName.split("/");
        String workspace = parts[0];
        String repoName = parts.length > 1 ? parts[1] : parts[0];

        int prNumber = pr.get("id").asInt();
        String prUrl = pr.get("links").get("html").get("href").asText();

        log.info("PR event on {}/{}: #{}", workspace, repoName, prNumber);

        reviewService.submitReviewFromWebhook(
            GitProvider.BITBUCKET,
            workspace,
            repoName,
            prNumber,
            prUrl
        );
    }
}
