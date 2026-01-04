package com.codelens.repository;

import com.codelens.model.entity.WebhookConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface WebhookConfigRepository extends JpaRepository<WebhookConfig, UUID> {

    List<WebhookConfig> findByOrganizationIdOrderByCreatedAtDesc(UUID organizationId);

    List<WebhookConfig> findByOrganizationIdAndEnabledTrue(UUID organizationId);

    // Note: Filtering by event is done in service layer since events is a JSON column
    @Query("SELECT w FROM WebhookConfig w WHERE w.organization.id = :orgId AND w.enabled = true")
    List<WebhookConfig> findEnabledByOrganizationId(UUID orgId);

    /**
     * Find webhooks that are disabled but ready for auto-retry.
     * These are webhooks where:
     * - enabled = false (disabled)
     * - retryAt is not null (scheduled for retry)
     * - retryAt <= now (retry time has passed)
     */
    @Query("SELECT w FROM WebhookConfig w WHERE w.enabled = false AND w.retryAt IS NOT NULL AND w.retryAt <= :now")
    List<WebhookConfig> findWebhooksReadyForRetry(@Param("now") LocalDateTime now);

    /**
     * Atomically increment retry count when re-enabling webhook for retry.
     * Prevents lost updates when multiple retry attempts occur.
     */
    @org.springframework.data.jpa.repository.Modifying
    @Query("UPDATE WebhookConfig w SET w.retryCount = w.retryCount + 1, w.enabled = true, " +
           "w.failureCount = 0, w.retryAt = null WHERE w.id = :webhookId")
    int incrementRetryCountAndEnable(@Param("webhookId") UUID webhookId);
}
