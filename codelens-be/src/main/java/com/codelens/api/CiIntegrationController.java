package com.codelens.api;

import com.codelens.api.dto.CiReviewRequest;
import com.codelens.api.dto.CiReviewResponse;
import com.codelens.model.entity.Review;
import com.codelens.service.ReviewService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * CI/CD Integration Controller
 *
 * Provides endpoints for Jenkins, GitHub Actions, GitLab CI, and other CI/CD tools
 * to trigger PR reviews programmatically.
 *
 * Protected by API key authentication and optional IP whitelisting.
 */
@Slf4j
@RestController
@RequestMapping("/api/ci")
public class CiIntegrationController {

    private final ReviewService reviewService;

    public CiIntegrationController(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    /**
     * Trigger a PR review from CI/CD pipeline
     *
     * Example Jenkins pipeline usage:
     * <pre>
     * curl -X POST https://codelens.example.com/api/ci/review \
     *   -H "X-API-Key: your-api-key" \
     *   -H "Content-Type: application/json" \
     *   -d '{"prUrl": "https://github.com/owner/repo/pull/123"}'
     * </pre>
     */
    @PostMapping("/review")
    public ResponseEntity<CiReviewResponse> triggerReview(
            @Valid @RequestBody CiReviewRequest request,
            @RequestHeader(value = "X-API-Key", required = false) String apiKey,
            @RequestHeader(value = "X-Forwarded-For", required = false) String forwardedFor,
            @RequestAttribute(value = "clientIp", required = false) String clientIp) {

        log.info("CI review request received for PR: {} from IP: {}",
            request.getPrUrl(), clientIp != null ? clientIp : forwardedFor);

        try {
            Review review = reviewService.submitReview(request.getPrUrl(), null);

            // Determine if this is an existing review or a new one
            String message;
            if (review.getStatus() == Review.ReviewStatus.COMPLETED) {
                message = "Review already completed for this commit";
            } else if (review.getStatus() == Review.ReviewStatus.IN_PROGRESS) {
                message = "Review already in progress for this commit";
            } else {
                message = "Review triggered successfully";
            }

            CiReviewResponse response = CiReviewResponse.builder()
                .reviewId(review.getId())
                .status(review.getStatus().name())
                .message(message)
                .prUrl(request.getPrUrl())
                .issuesFound(review.getIssuesFound())
                .criticalIssues(review.getCriticalIssues())
                .highIssues(review.getHighIssues())
                .summary(review.getSummary())
                .build();

            log.info("CI review request processed: reviewId={}, status={}", review.getId(), review.getStatus());
            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            // Client error - safe to include message (e.g., invalid PR URL)
            log.warn("Invalid CI review request for PR {}: {}", request.getPrUrl(), e.getMessage());

            CiReviewResponse response = CiReviewResponse.builder()
                .status("FAILED")
                .message(e.getMessage())
                .prUrl(request.getPrUrl())
                .build();

            return ResponseEntity.badRequest().body(response);
        } catch (Exception e) {
            // Internal error - don't leak exception details
            log.error("Failed to trigger CI review for PR: {}", request.getPrUrl(), e);

            CiReviewResponse response = CiReviewResponse.builder()
                .status("FAILED")
                .message("Failed to trigger review. Please check the PR URL and try again.")
                .prUrl(request.getPrUrl())
                .build();

            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * Get review status by ID (for CI/CD polling)
     */
    @GetMapping("/review/{reviewId}")
    public ResponseEntity<CiReviewResponse> getReviewStatus(@PathVariable UUID reviewId) {
        return reviewService.getReview(reviewId)
            .map(review -> {
                CiReviewResponse response = CiReviewResponse.builder()
                    .reviewId(review.getId())
                    .status(review.getStatus().name())
                    .prUrl(review.getPrUrl())
                    .summary(review.getSummary())
                    .issuesFound(review.getIssuesFound())
                    .criticalIssues(review.getCriticalIssues())
                    .highIssues(review.getHighIssues())
                    .message(review.getStatus().name().equals("COMPLETED")
                        ? "Review completed"
                        : "Review " + review.getStatus().name().toLowerCase())
                    .build();
                return ResponseEntity.ok(response);
            })
            .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Health check endpoint for CI/CD monitoring
     */
    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("OK");
    }
}
