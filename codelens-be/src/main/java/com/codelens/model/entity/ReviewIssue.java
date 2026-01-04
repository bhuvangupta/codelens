package com.codelens.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "review_issues")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReviewIssue {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "review_id", nullable = false)
    private Review review;

    @Column(nullable = false)
    private String analyzer; // pmd, eslint, spotbugs, npm-audit, ai

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Severity severity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Category category;

    @Column(nullable = false)
    private String rule; // e.g., "SQL_INJECTION", "react-hooks/exhaustive-deps"

    @Column(nullable = false, columnDefinition = "TEXT")
    private String message;

    private String filePath;

    private Integer startLine;

    private Integer endLine;

    @Enumerated(EnumType.STRING)
    private Source source; // AI, STATIC, or CVE

    // For CVE findings
    private String cveId;
    private Double cvssScore;
    private String affectedPackage;
    private String fixedVersion;

    // AI-enhanced explanation
    @Column(columnDefinition = "TEXT")
    private String aiExplanation;

    @Column(columnDefinition = "TEXT")
    private String suggestedFix;

    @CreationTimestamp
    private LocalDateTime createdAt;

    public enum Severity {
        CRITICAL,
        HIGH,
        MEDIUM,
        LOW,
        INFO
    }

    public enum Category {
        SECURITY,
        BUG,
        SMELL,
        STYLE,
        CVE,
        PERFORMANCE,
        LOGIC,
        OPTIMIZATION
    }

    public enum Source {
        AI,
        STATIC,
        CVE
    }

    // Convenience methods for compatibility
    public Integer getLineNumber() {
        return startLine;
    }

    public void setLineNumber(Integer lineNumber) {
        this.startLine = lineNumber;
    }

    public String getDescription() {
        return message;
    }

    public void setDescription(String description) {
        this.message = description;
    }

    public String getSuggestion() {
        return suggestedFix;
    }

    public void setSuggestion(String suggestion) {
        this.suggestedFix = suggestion;
    }
}
