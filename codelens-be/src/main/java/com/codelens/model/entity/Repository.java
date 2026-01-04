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
@Table(name = "repositories")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Repository {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String fullName; // e.g., "owner/repo"

    @Column(nullable = false)
    private String name;

    private String owner;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private GitProvider provider; // GITHUB, GITLAB

    private String providerRepoId;

    private String defaultBranch;

    private String description;

    private String language;

    private Boolean isPrivate;

    @Column(nullable = false)
    @Builder.Default
    private Boolean autoReviewEnabled = true;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @OneToMany(mappedBy = "repository", cascade = CascadeType.ALL)
    @Builder.Default
    private Set<Review> reviews = new HashSet<>();

    // Webhook configuration
    private String webhookId;
    private String webhookSecret;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    public enum GitProvider {
        GITHUB, GITLAB
    }
}
