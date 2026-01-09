package com.codelens.api.dto;

import com.codelens.model.entity.Review;
import com.codelens.model.entity.ReviewComment;
import com.codelens.model.entity.ReviewIssue;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record ReviewResponse(
    UUID id,
    String prUrl,
    String commitUrl,
    Integer prNumber,
    String prTitle,
    String prAuthor,
    String submittedBy,
    String status,
    String errorMessage,
    String summary,
    Integer filesReviewed,
    Integer linesAdded,
    Integer linesRemoved,
    Integer totalIssues,
    Integer criticalIssues,
    Integer highIssues,
    Integer mediumIssues,
    Integer lowIssues,
    String llmProvider,
    Integer inputTokens,
    Integer outputTokens,
    Double estimatedCost,
    LocalDateTime createdAt,
    LocalDateTime completedAt,
    // Ticket scope validation
    String ticketContent,
    String ticketId,
    String ticketScopeResult,
    Boolean ticketScopeAligned,
    List<IssueDto> issues,
    List<CommentDto> comments
) {
    public static ReviewResponse from(Review review) {
        return new ReviewResponse(
            review.getId(),
            review.getPrUrl(),
            review.getCommitUrl(),
            review.getPrNumber(),
            review.getPrTitle(),
            review.getPrAuthor(),
            review.getUser() != null ? review.getUser().getName() : null,
            review.getStatus().name(),
            review.getErrorMessage(),
            review.getSummary(),
            review.getFilesReviewed(),
            review.getLinesAdded(),
            review.getLinesRemoved(),
            review.getTotalIssues(),
            review.getCriticalIssues(),
            review.getHighIssues(),
            review.getMediumIssues(),
            review.getLowIssues(),
            review.getLlmProvider(),
            review.getInputTokens(),
            review.getOutputTokens(),
            review.getEstimatedCost(),
            review.getCreatedAt(),
            review.getCompletedAt(),
            review.getTicketContent(),
            review.getTicketId(),
            review.getTicketScopeResult(),
            review.getTicketScopeAligned(),
            null,
            null
        );
    }

    public static ReviewResponse from(Review review, List<ReviewIssue> issues, List<ReviewComment> comments) {
        return new ReviewResponse(
            review.getId(),
            review.getPrUrl(),
            review.getCommitUrl(),
            review.getPrNumber(),
            review.getPrTitle(),
            review.getPrAuthor(),
            review.getUser() != null ? review.getUser().getName() : null,
            review.getStatus().name(),
            review.getErrorMessage(),
            review.getSummary(),
            review.getFilesReviewed(),
            review.getLinesAdded(),
            review.getLinesRemoved(),
            review.getTotalIssues(),
            review.getCriticalIssues(),
            review.getHighIssues(),
            review.getMediumIssues(),
            review.getLowIssues(),
            review.getLlmProvider(),
            review.getInputTokens(),
            review.getOutputTokens(),
            review.getEstimatedCost(),
            review.getCreatedAt(),
            review.getCompletedAt(),
            review.getTicketContent(),
            review.getTicketId(),
            review.getTicketScopeResult(),
            review.getTicketScopeAligned(),
            issues != null ? issues.stream().map(IssueDto::from).toList() : null,
            comments != null ? comments.stream().map(CommentDto::from).toList() : null
        );
    }

    public record IssueDto(
        UUID id,
        String filePath,
        Integer lineNumber,
        String severity,
        String category,
        String description,
        String suggestion,
        String source,
        String cveId,
        Double cvssScore,
        // Feedback fields
        Boolean isHelpful,
        Boolean isFalsePositive,
        LocalDateTime feedbackAt
    ) {
        public static IssueDto from(ReviewIssue issue) {
            return new IssueDto(
                issue.getId(),
                issue.getFilePath(),
                issue.getLineNumber(),
                issue.getSeverity().name(),
                issue.getCategory() != null ? issue.getCategory().name() : null,
                issue.getDescription(),
                issue.getSuggestion(),
                issue.getSource() != null ? issue.getSource().name() : null,
                issue.getCveId(),
                issue.getCvssScore(),
                issue.getIsHelpful(),
                issue.getIsFalsePositive(),
                issue.getFeedbackAt()
            );
        }
    }

    public record CommentDto(
        UUID id,
        String filePath,
        Integer lineNumber,
        String body,
        String severity,
        String category,
        String suggestion,
        boolean posted
    ) {
        public static CommentDto from(ReviewComment comment) {
            return new CommentDto(
                comment.getId(),
                comment.getFilePath(),
                comment.getLineNumber(),
                comment.getBody(),
                comment.getSeverity() != null ? comment.getSeverity().name() : null,
                comment.getCategory(),
                comment.getSuggestion(),
                comment.isPosted()
            );
        }
    }
}
