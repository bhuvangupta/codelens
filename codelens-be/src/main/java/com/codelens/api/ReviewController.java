package com.codelens.api;

import com.codelens.api.dto.CommitReviewRequest;
import com.codelens.api.dto.ReviewProgressDto;
import com.codelens.api.dto.ReviewRequest;
import com.codelens.api.dto.ReviewResponse;
import com.codelens.exception.RateLimitExceededException;
import com.codelens.model.entity.Review;
import com.codelens.model.entity.ReviewComment;
import com.codelens.model.entity.ReviewFileDiff;
import com.codelens.model.entity.ReviewIssue;
import com.codelens.model.entity.User;
import com.codelens.repository.ReviewFileDiffRepository;
import com.codelens.repository.ReviewRepository;
import com.codelens.repository.UserRepository;
import com.codelens.security.AuthenticatedUser;
import com.codelens.service.LearningService;
import com.codelens.service.OptimizationService;
import com.codelens.service.ReviewCancellationService;
import com.codelens.service.ReviewService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/reviews")
@Validated
public class ReviewController {

    private final ReviewService reviewService;
    private final ReviewRepository reviewRepository;
    private final OptimizationService optimizationService;
    private final ReviewCancellationService cancellationService;
    private final UserRepository userRepository;
    private final ReviewFileDiffRepository fileDiffRepository;
    private final LearningService learningService;

    public ReviewController(
            ReviewService reviewService,
            ReviewRepository reviewRepository,
            OptimizationService optimizationService,
            ReviewCancellationService cancellationService,
            UserRepository userRepository,
            ReviewFileDiffRepository fileDiffRepository,
            LearningService learningService) {
        this.reviewService = reviewService;
        this.reviewRepository = reviewRepository;
        this.optimizationService = optimizationService;
        this.cancellationService = cancellationService;
        this.userRepository = userRepository;
        this.fileDiffRepository = fileDiffRepository;
        this.learningService = learningService;
    }

    /**
     * Submit a new PR for review
     */
    @PostMapping
    public ResponseEntity<ReviewResponse> submitReview(
            @Valid @RequestBody ReviewRequest request,
            @AuthenticationPrincipal AuthenticatedUser auth) {

        String email = auth != null ? auth.email() : null;
        log.info("Submitting review for PR: {} by user: {} (includeOptimization={})",
            request.prUrl(), email, request.includeOptimization());

        // Build session user info from authenticated user
        ReviewService.SessionUserInfo sessionUser = null;
        if (auth != null) {
            sessionUser = new ReviewService.SessionUserInfo(
                auth.providerId(), auth.email(), auth.name(), auth.picture());
        }

        boolean includeOptimization = Boolean.TRUE.equals(request.includeOptimization());
        Review review = reviewService.submitReview(
            request.prUrl(), sessionUser, includeOptimization,
            request.ticketContent(), request.ticketId());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
            .body(ReviewResponse.from(review));
    }

    /**
     * Submit a single commit for review
     */
    @PostMapping("/commit")
    public ResponseEntity<ReviewResponse> submitCommitReview(
            @Valid @RequestBody CommitReviewRequest request,
            @AuthenticationPrincipal AuthenticatedUser auth) {

        String email = auth != null ? auth.email() : null;
        log.info("Submitting commit review for: {} by user: {} (includeOptimization={})",
            request.commitUrl(), email, request.includeOptimization());

        // Build session user info from authenticated user
        ReviewService.SessionUserInfo sessionUser = null;
        if (auth != null) {
            sessionUser = new ReviewService.SessionUserInfo(
                auth.providerId(), auth.email(), auth.name(), auth.picture());
        }

        boolean includeOptimization = Boolean.TRUE.equals(request.includeOptimization());
        Review review = reviewService.submitCommitReview(
            request.commitUrl(), sessionUser, includeOptimization,
            request.ticketContent(), request.ticketId());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
            .body(ReviewResponse.from(review));
    }

    /**
     * Get a specific review by ID
     */
    @GetMapping("/{id}")
    public ResponseEntity<ReviewResponse> getReview(@PathVariable UUID id) {
        return reviewService.getReview(id)
            .map(review -> {
                List<ReviewIssue> issues = reviewService.getIssuesForReview(id);
                List<ReviewComment> comments = reviewService.getCommentsForReview(id);
                return ResponseEntity.ok(ReviewResponse.from(review, issues, comments));
            })
            .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Get review status with progress details.
     * Uses lightweight projection query to avoid fetching large fields (raw_diff).
     * This endpoint is polled every 2 seconds during active reviews.
     */
    @GetMapping("/{id}/status")
    public ResponseEntity<Map<String, Object>> getReviewStatus(@PathVariable UUID id) {
        return reviewRepository.getProgress(id)
            .map(progress -> {
                Map<String, Object> response = new java.util.HashMap<>();
                response.put("id", progress.id());
                response.put("status", progress.status());
                response.put("progress", progress.getProgress());

                // Progress details
                if (progress.totalFiles() != null) {
                    response.put("totalFiles", progress.totalFiles());
                }
                if (progress.filesReviewed() != null) {
                    response.put("filesReviewed", progress.filesReviewed());
                }
                if (progress.currentFile() != null) {
                    response.put("currentFile", progress.currentFile());
                }

                // Timing
                if (progress.startedAt() != null) {
                    response.put("startedAt", progress.startedAt().toString());
                    response.put("elapsedMs", progress.getElapsedMs());
                }

                // Optimization progress
                if (Boolean.TRUE.equals(progress.optimizationInProgress())) {
                    response.put("optimizationInProgress", true);
                    if (progress.optimizationTotalFiles() != null) {
                        response.put("optimizationTotalFiles", progress.optimizationTotalFiles());
                    }
                    if (progress.optimizationFilesAnalyzed() != null) {
                        response.put("optimizationFilesAnalyzed", progress.optimizationFilesAnalyzed());
                    }
                    if (progress.optimizationCurrentFile() != null) {
                        response.put("optimizationCurrentFile", progress.optimizationCurrentFile());
                    }
                }

                return ResponseEntity.ok(response);
            })
            .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Get issues for a review
     */
    @GetMapping("/{id}/issues")
    public ResponseEntity<List<ReviewResponse.IssueDto>> getReviewIssues(@PathVariable UUID id) {
        List<ReviewIssue> issues = reviewService.getIssuesForReview(id);
        return ResponseEntity.ok(issues.stream().map(ReviewResponse.IssueDto::from).toList());
    }

    /**
     * Submit feedback for a review issue
     */
    @PostMapping("/{reviewId}/issues/{issueId}/feedback")
    public ResponseEntity<?> submitIssueFeedback(
            @PathVariable UUID reviewId,
            @PathVariable UUID issueId,
            @RequestBody IssueFeedbackRequest request,
            @AuthenticationPrincipal AuthenticatedUser auth) {

        if (auth == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        Optional<User> user = userRepository.findByEmail(auth.email());
        if (user.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        // Verify the issue belongs to the specified review
        Optional<Review> review = reviewService.getReview(reviewId);
        if (review.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        try {
            learningService.submitFeedback(
                    issueId,
                    new LearningService.FeedbackRequest(
                            request.isHelpful(),
                            request.isFalsePositive(),
                            request.note()
                    ),
                    user.get()
            );
            return ResponseEntity.ok(Map.of("message", "Feedback submitted successfully"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    public record IssueFeedbackRequest(
            Boolean isHelpful,
            Boolean isFalsePositive,
            String note
    ) {}

    /**
     * Get comments for a review
     */
    @GetMapping("/{id}/comments")
    public ResponseEntity<List<ReviewResponse.CommentDto>> getReviewComments(@PathVariable UUID id) {
        List<ReviewComment> comments = reviewService.getCommentsForReview(id);
        return ResponseEntity.ok(comments.stream().map(ReviewResponse.CommentDto::from).toList());
    }

    /**
     * Run optimization analysis on a completed review
     */
    @PostMapping("/{id}/optimize")
    public ResponseEntity<Map<String, Object>> runOptimization(@PathVariable UUID id) {
        return reviewService.getReview(id)
            .map(review -> {
                if (review.getStatus() != Review.ReviewStatus.COMPLETED) {
                    return ResponseEntity.badRequest().<Map<String, Object>>body(
                        Map.of("error", "Review must be completed before running optimization analysis"));
                }

                if (Boolean.TRUE.equals(review.getOptimizationCompleted())) {
                    // Already completed, return existing results
                    List<ReviewIssue> optimizations = reviewService.getOptimizationsForReview(id);
                    return ResponseEntity.ok(Map.of(
                        "status", "completed",
                        "summary", review.getOptimizationSummary() != null ? review.getOptimizationSummary() : "",
                        "optimizations", optimizations.stream().map(ReviewResponse.IssueDto::from).toList()
                    ));
                }

                try {
                    // Start optimization analysis async
                    optimizationService.analyzeOptimizationsAsync(id);
                    return ResponseEntity.accepted().<Map<String, Object>>body(
                        Map.of("status", "started", "message", "Optimization analysis started"));
                } catch (RateLimitExceededException e) {
                    return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).<Map<String, Object>>body(
                        Map.of("error", e.getMessage()));
                }
            })
            .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Get optimization results for a review (includes progress info)
     */
    @GetMapping("/{id}/optimizations")
    public ResponseEntity<Map<String, Object>> getOptimizations(@PathVariable UUID id) {
        return reviewService.getReview(id)
            .map(review -> {
                List<ReviewIssue> optimizations = reviewService.getOptimizationsForReview(id);

                Map<String, Object> response = new java.util.HashMap<>();
                response.put("completed", Boolean.TRUE.equals(review.getOptimizationCompleted()));
                response.put("inProgress", Boolean.TRUE.equals(review.getOptimizationInProgress()));
                response.put("summary", review.getOptimizationSummary() != null ? review.getOptimizationSummary() : "");
                response.put("optimizations", optimizations.stream().map(ReviewResponse.IssueDto::from).toList());

                // Progress info
                if (Boolean.TRUE.equals(review.getOptimizationInProgress())) {
                    response.put("totalFiles", review.getOptimizationTotalFiles());
                    response.put("filesAnalyzed", review.getOptimizationFilesAnalyzed());
                    response.put("currentFile", review.getOptimizationCurrentFile());
                    if (review.getOptimizationStartedAt() != null) {
                        long elapsedMs = java.time.Duration.between(
                            review.getOptimizationStartedAt(), LocalDateTime.now()).toMillis();
                        response.put("elapsedMs", elapsedMs);
                    }
                    // Calculate progress percentage
                    if (review.getOptimizationTotalFiles() != null && review.getOptimizationTotalFiles() > 0) {
                        int filesAnalyzed = review.getOptimizationFilesAnalyzed() != null ? review.getOptimizationFilesAnalyzed() : 0;
                        response.put("progress", (int) ((filesAnalyzed * 100.0) / review.getOptimizationTotalFiles()));
                    } else {
                        response.put("progress", 0);
                    }
                }

                return ResponseEntity.ok(response);
            })
            .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Get recent reviews (all users in same organization) with optional repository filter
     */
    @GetMapping("/recent")
    public ResponseEntity<List<ReviewResponse>> getRecentReviews(
            @AuthenticationPrincipal AuthenticatedUser auth,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit,
            @RequestParam(required = false) String repository) {

        UUID orgId = null;
        if (auth != null) {
            User user = userRepository.findByEmail(auth.email()).orElse(null);
            if (user != null && user.getOrganization() != null) {
                orgId = user.getOrganization().getId();
            }
        }

        List<Review> reviews = reviewService.getRecentReviews(limit, repository, orgId);
        return ResponseEntity.ok(reviews.stream().map(ReviewResponse::from).toList());
    }

    /**
     * Get reviews for current user with optional repository filter
     */
    @GetMapping("/my")
    public ResponseEntity<List<ReviewResponse>> getMyReviews(
            @AuthenticationPrincipal AuthenticatedUser auth,
            @RequestParam(required = false) String repository) {
        if (auth == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        Optional<User> user = userRepository.findByEmail(auth.email());
        if (user.isEmpty()) {
            return ResponseEntity.ok(List.of());
        }
        List<Review> reviews = reviewService.getReviewsForUser(user.get().getId(), repository);
        return ResponseEntity.ok(reviews.stream().map(ReviewResponse::from).toList());
    }

    /**
     * Get distinct repository names for filtering (filtered by organization)
     */
    @GetMapping("/repositories")
    public ResponseEntity<List<String>> getRepositories(
            @AuthenticationPrincipal AuthenticatedUser auth,
            @RequestParam(defaultValue = "false") boolean all) {
        if (auth == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        Optional<User> userOpt = userRepository.findByEmail(auth.email());
        if (userOpt.isEmpty()) {
            return ResponseEntity.ok(List.of());
        }
        User user = userOpt.get();

        if (all) {
            // Get all repos in user's organization
            UUID orgId = user.getOrganization() != null ? user.getOrganization().getId() : null;
            return ResponseEntity.ok(reviewService.getDistinctRepositoryNames(orgId));
        }
        // Get repos for current user only
        return ResponseEntity.ok(reviewService.getDistinctRepositoryNamesForUser(user.getId()));
    }

    /**
     * Cancel a running review
     */
    @PostMapping("/{id}/cancel")
    public ResponseEntity<?> cancelReview(
            @PathVariable UUID id,
            @RequestBody(required = false) CancelRequest request,
            @AuthenticationPrincipal AuthenticatedUser auth) {

        // Get the user who is cancelling
        User cancelledBy = null;
        if (auth != null) {
            cancelledBy = userRepository.findByEmail(auth.email()).orElse(null);
        }

        if (cancelledBy == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        // Verify review belongs to user's organization
        Review existingReview = reviewService.getReview(id).orElse(null);
        if (existingReview == null) {
            return ResponseEntity.notFound().build();
        }

        UUID userOrgId = cancelledBy.getOrganization() != null ? cancelledBy.getOrganization().getId() : null;
        UUID reviewOrgId = (existingReview.getRepository() != null && existingReview.getRepository().getOrganization() != null)
                ? existingReview.getRepository().getOrganization().getId() : null;

        if (reviewOrgId != null && !reviewOrgId.equals(userOrgId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "You can only cancel reviews in your organization"));
        }

        String reason = request != null ? request.reason() : null;

        try {
            Review review = cancellationService.cancelReview(id, cancelledBy, reason);
            return ResponseEntity.ok(Map.of(
                    "id", review.getId(),
                    "status", review.getStatus().name(),
                    "message", "Review cancelled successfully",
                    "cancelledAt", review.getCancelledAt().toString()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", e.getMessage()
            ));
        }
    }

    public record CancelRequest(String reason) {}

    /**
     * Get diff for a review
     */
    @GetMapping("/{id}/diff")
    public ResponseEntity<DiffResponse> getReviewDiff(@PathVariable UUID id) {
        return reviewService.getReview(id)
            .map(review -> {
                List<ReviewFileDiff> fileDiffs = fileDiffRepository.findByReviewIdOrderByFilePathAsc(id);
                return ResponseEntity.ok(new DiffResponse(
                    review.getRawDiff(),
                    fileDiffs.stream().map(FileDiffDto::from).toList()
                ));
            })
            .orElse(ResponseEntity.notFound().build());
    }

    public record DiffResponse(
        String rawDiff,
        List<FileDiffDto> files
    ) {}

    public record FileDiffDto(
        String id,
        String filePath,
        String oldPath,
        String status,
        int additions,
        int deletions,
        String patch
    ) {
        public static FileDiffDto from(ReviewFileDiff diff) {
            return new FileDiffDto(
                diff.getId().toString(),
                diff.getFilePath(),
                diff.getOldPath(),
                diff.getStatus().name(),
                diff.getAdditions() != null ? diff.getAdditions() : 0,
                diff.getDeletions() != null ? diff.getDeletions() : 0,
                diff.getPatch()
            );
        }
    }

    private int calculateProgress(Review review) {
        return switch (review.getStatus()) {
            case PENDING -> 0;
            case IN_PROGRESS -> {
                // Calculate actual progress from file counts
                if (review.getTotalFilesToReview() != null && review.getTotalFilesToReview() > 0
                        && review.getFilesReviewedCount() != null) {
                    yield (int) ((review.getFilesReviewedCount() * 100.0) / review.getTotalFilesToReview());
                }
                yield 10; // Default if no progress info yet
            }
            case COMPLETED, FAILED, CANCELLED -> 100;
        };
    }
}
