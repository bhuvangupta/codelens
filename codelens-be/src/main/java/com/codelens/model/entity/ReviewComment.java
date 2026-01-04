package com.codelens.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "review_comments")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReviewComment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "review_id", nullable = false)
    private Review review;

    @Column(nullable = false)
    private String filePath;

    private Integer startLine;

    private Integer endLine;

    private String commitSha;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String body;

    private String category;

    @Column(columnDefinition = "TEXT")
    private String suggestion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private CommentType type = CommentType.SUGGESTION;

    @Enumerated(EnumType.STRING)
    private Severity severity;

    // For tracking if comment was posted to PR
    private Boolean postedToPr;
    private String externalCommentId;

    // For tracking developer feedback
    private Boolean wasHelpful;
    private Boolean wasIgnored;
    private String ignoreReason;

    @CreationTimestamp
    private LocalDateTime createdAt;

    public enum CommentType {
        ISSUE,
        SUGGESTION,
        PRAISE,
        QUESTION,
        SECURITY
    }

    public enum Severity {
        CRITICAL,
        HIGH,
        MEDIUM,
        LOW,
        INFO
    }

    // Convenience methods for compatibility
    public Integer getLineNumber() {
        return startLine;
    }

    public void setLineNumber(Integer lineNumber) {
        this.startLine = lineNumber;
    }

    public boolean isPosted() {
        return Boolean.TRUE.equals(postedToPr);
    }
}
