package com.codelens.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "llm_usage")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LlmUsage {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id")
    private Organization organization;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "review_id")
    private Review review;

    @Column(nullable = false)
    private String provider; // glm, claude, gemini, ollama, openai

    @Column(nullable = false)
    private String model;

    @Column(nullable = false)
    private String taskType; // summary, review, security, describe

    @Column(nullable = false)
    private Integer inputTokens;

    @Column(nullable = false)
    private Integer outputTokens;

    /** Input tokens served from a prompt cache. */
    private Integer cachedTokens;

    /** Reasoning tokens, billed as output. */
    private Integer thinkingTokens;

    @Column(nullable = false)
    private Double estimatedCost;

    private Long latencyMs;

    private Boolean success;
    private String errorMessage;

    @CreationTimestamp
    private LocalDateTime createdAt;
}
