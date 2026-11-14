package com.codelens.git.github;

import com.codelens.gate.SecurityGateBypassService;
import com.codelens.gate.SecurityGateService;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

class GitHubWebhookControllerTest {

    private ReviewService reviewService;
    private SecurityGateService securityGateService;
    private SecurityGateBypassService bypassService;
    private GitHubWebhookController controller;

    @BeforeEach
    void setUp() {
        reviewService = mock(ReviewService.class);
        securityGateService = mock(SecurityGateService.class);
        bypassService = mock(SecurityGateBypassService.class);
        controller = new GitHubWebhookController(reviewService, securityGateService, bypassService, new ObjectMapper());
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
        // Payload has no head SHA, so there is nothing to put a status on
        verifyNoInteractions(securityGateService);
    }

    @Test
    void pullRequestTriggersSecurityGateOnHeadCommit() throws Exception {
        handleSigned(PR_WITH_SHAS);

        verify(securityGateService).runForPullRequest(
                Repository.GitProvider.GITHUB, "owner", "repo", 1, "head111", "base222");
        verify(reviewService).submitReviewFromWebhook(
                Repository.GitProvider.GITHUB, "owner", "repo", 1, "http://example.com");
    }

    @Test
    void gateDispatchFailureDoesNotDropTheReview() throws Exception {
        doThrow(new RuntimeException("pool exhausted")).when(securityGateService)
                .runForPullRequest(any(), any(), any(), anyInt(), any(), any());

        ResponseEntity<String> response = handleSigned(PR_WITH_SHAS);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(reviewService).submitReviewFromWebhook(
                Repository.GitProvider.GITHUB, "owner", "repo", 1, "http://example.com");
    }

    @Test
    void newPullRequestCommentIsPassedToTheBypassService() throws Exception {
        handleSigned("issue_comment", comment("created", true));

        verify(bypassService).handlePullRequestComment(
                Repository.GitProvider.GITHUB, "owner", "repo", 9, "bhuvan", "/codelens bypass prod is down");
    }

    @Test
    void editedCommentsAndPlainIssueCommentsAreIgnored() throws Exception {
        handleSigned("issue_comment", comment("edited", true));
        handleSigned("issue_comment", comment("created", false));

        verifyNoInteractions(bypassService);
    }

    @Test
    void unsignedCommentNeverReachesTheBypassService() {
        ReflectionTestUtils.setField(controller, "webhookSecret", "super-secret");

        ResponseEntity<String> response = controller.handleWebhook(
                "issue_comment", "sha256=forged", comment("created", true));

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        verifyNoInteractions(bypassService);
    }

    private static String comment(String action, boolean onPullRequest) {
        return "{\"action\":\"" + action + "\",\"issue\":{\"number\":9"
            + (onPullRequest ? ",\"pull_request\":{\"url\":\"x\"}" : "") + "},"
            + "\"comment\":{\"body\":\"/codelens bypass prod is down\",\"user\":{\"login\":\"bhuvan\"}},"
            + "\"repository\":{\"name\":\"repo\",\"owner\":{\"login\":\"owner\"}}}";
    }

    private static final String PR_WITH_SHAS = "{\"action\":\"synchronize\",\"pull_request\":{\"number\":1,"
            + "\"html_url\":\"http://example.com\",\"head\":{\"sha\":\"head111\"},\"base\":{\"sha\":\"base222\"}},"
            + "\"repository\":{\"name\":\"repo\",\"owner\":{\"login\":\"owner\"}}}";

    private ResponseEntity<String> handleSigned(String payload) throws Exception {
        return handleSigned("pull_request", payload);
    }

    private ResponseEntity<String> handleSigned(String event, String payload) throws Exception {
        String secret = "super-secret";
        ReflectionTestUtils.setField(controller, "webhookSecret", secret);
        return controller.handleWebhook(event, "sha256=" + hmacSha256(payload, secret), payload);
    }

    private String hmacSha256(String payload, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        SecretKeySpec keySpec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        mac.init(keySpec);
        byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(hash);
    }
}
