package com.codelens.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "reviews")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Review {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // PR number is null for commit reviews
    private Integer prNumber;

    private String prTitle;

    @Column(columnDefinition = "TEXT")
    private String prDescription;

    // Ticket scope validation
    @Column(columnDefinition = "TEXT")
    private String ticketContent;

    private String ticketId;

    @Column(columnDefinition = "TEXT")
    private String ticketScopeResult;

    private Boolean ticketScopeAligned;

    private String prUrl;

    // Commit URL for single commit reviews (null for PR reviews)
    private String commitUrl;

    private String baseBranch;

    private String headBranch;

    private String headCommitSha;

    private String prAuthor;

    // Denormalized repository name for analytics (extracted from PR URL)
    private String repositoryName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private ReviewStatus status = ReviewStatus.PENDING;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "repository_id")
    private Repository repository;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    // Review results
    @Column(columnDefinition = "TEXT")
    private String summary;

    @Column(columnDefinition = "TEXT")
    private String aiReviewContent;

    @OneToMany(mappedBy = "review", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<ReviewComment> comments = new ArrayList<>();

    @OneToMany(mappedBy = "review", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<ReviewIssue> issues = new ArrayList<>();

    // Statistics
    private Integer filesChanged;
    private Integer linesAdded;
    private Integer linesDeleted;
    private Integer issuesFound;
    private Integer criticalIssues;
    private Integer highIssues;
    private Integer mediumIssues;
    private Integer lowIssues;

    // LLM usage
    private String llmProvider;
    private Integer inputTokens;
    private Integer outputTokens;
    private Double estimatedCost;

    // Processing info
    private Long processingTimeMs;
    private String errorMessage;

    // Progress tracking
    private Integer totalFilesToReview;
    private Integer filesReviewedCount;
    private String currentFile;  // Currently being reviewed

    // Optimization analysis
    @Builder.Default
    private Boolean includeOptimization = false;
    @Builder.Default
    private Boolean optimizationCompleted = false;
    @Column(columnDefinition = "TEXT")
    private String optimizationSummary;

    // Optimization progress tracking
    @Builder.Default
    private Boolean optimizationInProgress = false;
    private Integer optimizationTotalFiles;
    private Integer optimizationFilesAnalyzed;
    private String optimizationCurrentFile;
    private LocalDateTime optimizationStartedAt;

    // Diff storage for diff viewer
    @Column(name = "raw_diff", columnDefinition = "LONGTEXT")
    private String rawDiff;

    @OneToMany(mappedBy = "review", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<ReviewFileDiff> fileDiffs = new ArrayList<>();

    private LocalDateTime startedAt;
    private LocalDateTime completedAt;

    // Cancellation tracking
    @Column(columnDefinition = "TEXT")
    private String cancellationReason;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cancelled_by_id")
    private User cancelledBy;

    private LocalDateTime cancelledAt;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    /**
     * Version field for optimistic locking.
     * Prevents race conditions when updating review status
     * (e.g., cancellation racing with completion).
     */
    @Version
    private Long version;

    public enum ReviewStatus {
        PENDING,
        IN_PROGRESS,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    // Convenience methods for compatibility
    public Integer getFilesReviewed() {
        return filesChanged;
    }

    public void setFilesReviewed(Integer filesReviewed) {
        this.filesChanged = filesReviewed;
    }

    public Integer getLinesRemoved() {
        return linesDeleted;
    }

    public void setLinesRemoved(Integer linesRemoved) {
        this.linesDeleted = linesRemoved;
    }

    public Integer getTotalIssues() {
        return issuesFound;
    }

    public void setTotalIssues(Integer totalIssues) {
        this.issuesFound = totalIssues;
    }
}
