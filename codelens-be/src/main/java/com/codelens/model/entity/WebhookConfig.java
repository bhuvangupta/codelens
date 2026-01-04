package com.codelens.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "webhook_configs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WebhookConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, length = 1000)
    private String url;

    // Secret for HMAC signature (optional)
    private String secret;

    // List of events to subscribe to
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private List<String> events;

    @Builder.Default
    private Boolean enabled = true;

    // Track consecutive failures for circuit breaker with exponential backoff
    @Builder.Default
    private Integer failureCount = 0;

    /**
     * When the webhook was auto-disabled due to failures.
     * Used to calculate exponential backoff for retry.
     */
    private LocalDateTime disabledAt;

    /**
     * When to automatically retry the disabled webhook.
     * Calculated using exponential backoff: 1h, 2h, 4h, 8h, 24h max.
     * Null means no auto-retry scheduled (manually disabled or active).
     */
    private LocalDateTime retryAt;

    /**
     * Number of times the webhook has been auto-retried.
     * Resets to 0 on successful delivery or manual re-enable.
     */
    @Builder.Default
    private Integer retryCount = 0;

    @CreationTimestamp
    private LocalDateTime createdAt;

    private LocalDateTime lastDeliveryAt;

    // Event types
    public static final String EVENT_REVIEW_COMPLETED = "review.completed";
    public static final String EVENT_REVIEW_FAILED = "review.failed";
    public static final String EVENT_CRITICAL_ISSUES = "review.critical_issues";
}
