package com.codelens.git.github;

import com.codelens.model.entity.Repository;
import com.codelens.service.ReviewService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class GitHubWebhookControllerTest {

    private ReviewService reviewService;
    private GitHubWebhookController controller;

    @BeforeEach
    void setUp() {
        reviewService = mock(ReviewService.class);
        controller = new GitHubWebhookController(reviewService, new ObjectMapper());
    }

    @Test
    void rejectsWebhookWhenSecretIsNotConfigured() {
        ReflectionTestUtils.setField(controller, "webhookSecret", "");

        ResponseEntity<String> response = controller.handleWebhook(
                "pull_request", "sha256=abc", "{}");

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertEquals("Webhook secret not configured", response.getBody());
        verifyNoInteractions(reviewService);
    }

    @Test
    void rejectsInvalidSignatureWhenSecretIsConfigured() {
        ReflectionTestUtils.setField(controller, "webhookSecret", "super-secret");

        ResponseEntity<String> response = controller.handleWebhook(
                "pull_request", "sha256=invalid", "{}");

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertEquals("Invalid signature", response.getBody());
        verifyNoInteractions(reviewService);
    }

    @Test
    void acceptsValidSignatureAndTriggersReview() throws Exception {
        String secret = "super-secret";
        String payload = "{\"action\":\"opened\",\"pull_request\":{\"number\":1,\"html_url\":\"http://example.com\"},\"repository\":{\"name\":\"repo\",\"owner\":{\"login\":\"owner\"}}}";
        String signature = "sha256=" + hmacSha256(payload, secret);

        ReflectionTestUtils.setField(controller, "webhookSecret", secret);

        ResponseEntity<String> response = controller.handleWebhook(
                "pull_request", signature, payload);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(reviewService).submitReviewFromWebhook(
                Repository.GitProvider.GITHUB, "owner", "repo", 1, "http://example.com");
    }

    private String hmacSha256(String payload, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        SecretKeySpec keySpec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        mac.init(keySpec);
        byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(hash);
    }
}
