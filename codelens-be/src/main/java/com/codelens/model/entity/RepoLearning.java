package com.codelens.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "repo_learning", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"repository_id", "rule_id", "analyzer"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RepoLearning {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "repository_id", nullable = false)
    private Repository repository;

    @Column(name = "rule_id", nullable = false)
    private String ruleId; // e.g., "HiddenField", "no-console", "SQL_INJECTION"

    @Column(nullable = false, length = 50)
    private String analyzer; // e.g., "checkstyle", "eslint", "pmd", "ai"

    @Column(length = 50)
    private String category; // e.g., "STYLE", "SMELL", "SECURITY"

    @Column(nullable = false)
    @Builder.Default
    private Integer falsePositiveCount = 0;

    @Column(nullable = false)
    @Builder.Default
    private Integer notHelpfulCount = 0;

    @Column(nullable = false)
    @Builder.Default
    private Boolean autoSuppressed = false;

    @Column(length = 20)
    private String severityOverride; // Downgrade severity for this repo (e.g., "LOW", "INFO")

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    /**
     * Check if this rule should be suppressed based on false positive threshold.
     * Default threshold is 5 false positives.
     */
    public boolean shouldSuppress(int threshold) {
        return autoSuppressed || falsePositiveCount >= threshold;
    }

    /**
     * Increment false positive count and check if auto-suppression should be triggered.
     */
    public void incrementFalsePositive(int autoSuppressThreshold) {
        this.falsePositiveCount++;
        if (this.falsePositiveCount >= autoSuppressThreshold) {
            this.autoSuppressed = true;
        }
    }

    /**
     * Increment not helpful count and check if severity should be downgraded.
     */
    public void incrementNotHelpful(int severityDowngradeThreshold) {
        this.notHelpfulCount++;
        // Severity downgrade logic can be implemented by caller
    }
}
