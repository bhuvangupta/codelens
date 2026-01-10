package com.codelens.repository;

import com.codelens.model.entity.LlmUsage;
import com.codelens.model.entity.Organization;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface LlmUsageRepository extends JpaRepository<LlmUsage, UUID> {

    List<LlmUsage> findByOrganizationOrderByCreatedAtDesc(Organization organization);

    @Query("SELECT SUM(u.estimatedCost) FROM LlmUsage u WHERE u.organization = :org AND u.createdAt >= :since")
    Double sumCostByOrganizationSince(@Param("org") Organization org, @Param("since") LocalDateTime since);

    @Query("SELECT SUM(u.estimatedCost) FROM LlmUsage u WHERE u.createdAt >= :since")
    Double sumEstimatedCostByCreatedAtAfter(@Param("since") LocalDateTime since);

    @Query("SELECT SUM(u.inputTokens + u.outputTokens) FROM LlmUsage u WHERE u.createdAt >= :since")
    Integer sumTotalTokensByCreatedAtAfter(@Param("since") LocalDateTime since);

    @Query("SELECT SUM(u.inputTokens) FROM LlmUsage u WHERE u.createdAt >= :since")
    Integer sumInputTokensByCreatedAtAfter(@Param("since") LocalDateTime since);

    @Query("SELECT SUM(u.outputTokens) FROM LlmUsage u WHERE u.createdAt >= :since")
    Integer sumOutputTokensByCreatedAtAfter(@Param("since") LocalDateTime since);

    @Query("SELECT u.provider, SUM(u.estimatedCost) FROM LlmUsage u WHERE u.createdAt >= :since GROUP BY u.provider")
    List<Object[]> sumCostByProviderAfter(@Param("since") LocalDateTime since);

    @Query("SELECT u.provider, SUM(u.estimatedCost) FROM LlmUsage u WHERE u.organization = :org AND u.createdAt >= :since GROUP BY u.provider")
    List<Object[]> sumCostByProviderSince(@Param("org") Organization org, @Param("since") LocalDateTime since);

    @Query("SELECT u.taskType, COUNT(u), SUM(u.inputTokens), SUM(u.outputTokens), SUM(u.estimatedCost) FROM LlmUsage u WHERE u.organization = :org AND u.createdAt >= :since GROUP BY u.taskType")
    List<Object[]> getUsageStatsByTaskType(@Param("org") Organization org, @Param("since") LocalDateTime since);

    @Query("SELECT AVG(u.latencyMs) FROM LlmUsage u WHERE u.organization = :org AND u.provider = :provider AND u.createdAt >= :since")
    Double getAverageLatencyByProvider(@Param("org") Organization org, @Param("provider") String provider, @Param("since") LocalDateTime since);

    // ============ Organization ID-based queries ============

    @Query("SELECT SUM(u.estimatedCost) FROM LlmUsage u WHERE u.organization.id = :orgId AND u.createdAt >= :since")
    Double sumEstimatedCostByOrganizationAndCreatedAtAfter(@Param("orgId") UUID orgId, @Param("since") LocalDateTime since);

    @Query("SELECT SUM(u.inputTokens + u.outputTokens) FROM LlmUsage u WHERE u.organization.id = :orgId AND u.createdAt >= :since")
    Integer sumTotalTokensByOrganizationAndCreatedAtAfter(@Param("orgId") UUID orgId, @Param("since") LocalDateTime since);

    @Query("SELECT SUM(u.inputTokens) FROM LlmUsage u WHERE u.organization.id = :orgId AND u.createdAt >= :since")
    Integer sumInputTokensByOrganizationAndCreatedAtAfter(@Param("orgId") UUID orgId, @Param("since") LocalDateTime since);

    @Query("SELECT SUM(u.outputTokens) FROM LlmUsage u WHERE u.organization.id = :orgId AND u.createdAt >= :since")
    Integer sumOutputTokensByOrganizationAndCreatedAtAfter(@Param("orgId") UUID orgId, @Param("since") LocalDateTime since);

    @Query("SELECT u.provider, SUM(u.estimatedCost) FROM LlmUsage u WHERE u.organization.id = :orgId AND u.createdAt >= :since GROUP BY u.provider")
    List<Object[]> sumCostByProviderAndOrganizationAfter(@Param("orgId") UUID orgId, @Param("since") LocalDateTime since);
}
