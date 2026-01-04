package com.codelens.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "organizations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Organization {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(unique = true)
    private String slug;

    private String description;

    private String logoUrl;

    @OneToMany(mappedBy = "organization", cascade = CascadeType.ALL)
    @Builder.Default
    private Set<User> members = new HashSet<>();

    @OneToMany(mappedBy = "organization", cascade = CascadeType.ALL)
    @Builder.Default
    private Set<Repository> repositories = new HashSet<>();

    // LLM Settings
    private String defaultLlmProvider;

    @Column(columnDefinition = "TEXT")
    private String llmConfigJson; // Store provider-specific settings as JSON

    // Review Settings
    @Column(nullable = false)
    @Builder.Default
    private Boolean autoReviewEnabled = true;

    @Column(nullable = false)
    @Builder.Default
    private Boolean postCommentsEnabled = true;

    // Post inline comments on specific code lines for CRITICAL/HIGH issues
    @Column(nullable = false)
    @Builder.Default
    private Boolean postInlineCommentsEnabled = true;

    @Column(nullable = false)
    @Builder.Default
    private Boolean securityScanEnabled = true;

    @Column(nullable = false)
    @Builder.Default
    private Boolean staticAnalysisEnabled = true;

    // Auto-approve users from matching email domain
    @Column(nullable = false)
    @Builder.Default
    private Boolean autoApproveMembers = false;

    // Git Provider Tokens (encrypted in production)
    @Column(columnDefinition = "TEXT")
    private String githubToken;

    @Column(columnDefinition = "TEXT")
    private String gitlabToken;

    private String gitlabUrl; // For self-hosted GitLab instances

    // Billing
    private Double monthlyBudget;
    private Double currentMonthSpend;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
