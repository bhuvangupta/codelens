package com.codelens.git.github;

import com.codelens.model.entity.Repository.GitProvider;
import com.codelens.service.ReviewService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Slf4j
@RestController
@RequestMapping("/api/webhooks/github")
public class GitHubWebhookController {

    private final ReviewService reviewService;
    private final ObjectMapper objectMapper;

    @Value("${codelens.git.github.webhook-secret:}")
    private String webhookSecret;

    public GitHubWebhookController(ReviewService reviewService, ObjectMapper objectMapper) {
        this.reviewService = reviewService;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    public ResponseEntity<String> handleWebhook(
            @RequestHeader("X-GitHub-Event") String eventType,
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
            @RequestBody String payload) {

        log.info("Received GitHub webhook: {}", eventType);

        // Verify signature if webhook secret is configured
        if (webhookSecret != null && !webhookSecret.isEmpty()) {
            if (!verifySignature(payload, signature)) {
                log.warn("Invalid webhook signature");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid signature");
            }
        }

        try {
            switch (eventType) {
                case "pull_request" -> handlePullRequestEvent(payload);
                case "pull_request_review" -> handlePullRequestReviewEvent(payload);
                case "pull_request_review_comment" -> handleReviewCommentEvent(payload);
                case "ping" -> log.info("Received ping event");
                default -> log.debug("Ignoring event type: {}", eventType);
            }
            return ResponseEntity.ok("OK");
        } catch (Exception e) {
            log.error("Error processing webhook", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body("Error processing webhook: " + e.getMessage());
        }
    }

    private void handlePullRequestEvent(String payload) throws Exception {
        JsonNode root = objectMapper.readTree(payload);
        String action = root.get("action").asText();

        if ("opened".equals(action) || "synchronize".equals(action) || "reopened".equals(action)) {
            JsonNode pr = root.get("pull_request");
            JsonNode repo = root.get("repository");

            String owner = repo.get("owner").get("login").asText();
            String repoName = repo.get("name").asText();
            int prNumber = pr.get("number").asInt();
            String prUrl = pr.get("html_url").asText();

            log.info("PR {} on {}/{}: {}", action, owner, repoName, prNumber);

            // Trigger async review
            reviewService.submitReviewFromWebhook(
                GitProvider.GITHUB,
                owner,
                repoName,
                prNumber,
                prUrl
            );
        }
    }

    private void handlePullRequestReviewEvent(String payload) throws Exception {
        JsonNode root = objectMapper.readTree(payload);
        String action = root.get("action").asText();

        if ("submitted".equals(action)) {
            JsonNode review = root.get("review");
            String state = review.get("state").asText();
            log.info("Review submitted with state: {}", state);
        }
    }

    private void handleReviewCommentEvent(String payload) throws Exception {
        JsonNode root = objectMapper.readTree(payload);
        String action = root.get("action").asText();
        log.info("Review comment action: {}", action);
    }

    private boolean verifySignature(String payload, String signature) {
        if (signature == null || !signature.startsWith("sha256=")) {
            return false;
        }

        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKeySpec = new SecretKeySpec(
                webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(secretKeySpec);
            byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            String expectedSignature = "sha256=" + HexFormat.of().formatHex(hash);
            // Use constant-time comparison to prevent timing attacks
            return MessageDigest.isEqual(
                    expectedSignature.getBytes(StandardCharsets.UTF_8),
                    signature.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            log.error("Error verifying signature", e);
            return false;
        }
    }
}
