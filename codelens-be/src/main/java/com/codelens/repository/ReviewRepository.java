package com.codelens.repository;

import com.codelens.api.dto.ReviewProgressDto;
import com.codelens.model.entity.Repository;
import com.codelens.model.entity.Review;
import com.codelens.model.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@org.springframework.stereotype.Repository
public interface ReviewRepository extends JpaRepository<Review, UUID> {

    Page<Review> findByRepositoryOrderByCreatedAtDesc(Repository repository, Pageable pageable);

    Page<Review> findByUserOrderByCreatedAtDesc(User user, Pageable pageable);

    List<Review> findByStatus(Review.ReviewStatus status);

    long countByStatus(Review.ReviewStatus status);

    long countByStatusAndCreatedAtAfter(Review.ReviewStatus status, LocalDateTime since);

    Optional<Review> findByRepositoryAndPrNumber(Repository repository, Integer prNumber);

    List<Review> findByPrUrlOrderByCreatedAtDesc(String prUrl);

    List<Review> findTop10ByOrderByCreatedAtDesc();

    List<Review> findByUserIdOrderByCreatedAtDesc(UUID userId);

    // Paginated queries for reviews list
    @Query("SELECT r FROM Review r ORDER BY r.createdAt DESC")
    List<Review> findRecentReviews(Pageable pageable);

    @Query("SELECT r FROM Review r WHERE r.repositoryName = :repoName ORDER BY r.createdAt DESC")
    List<Review> findByRepositoryNameOrderByCreatedAtDesc(@Param("repoName") String repoName, Pageable pageable);

    @Query("SELECT r FROM Review r WHERE r.user.id = :userId AND r.repositoryName = :repoName ORDER BY r.createdAt DESC")
    List<Review> findByUserIdAndRepositoryName(@Param("userId") UUID userId, @Param("repoName") String repoName);

    // Get distinct repository names for filtering
    @Query("SELECT DISTINCT r.repositoryName FROM Review r WHERE r.repositoryName IS NOT NULL ORDER BY r.repositoryName")
    List<String> findDistinctRepositoryNames();

    @Query("SELECT DISTINCT r.repositoryName FROM Review r WHERE r.user.id = :userId AND r.repositoryName IS NOT NULL ORDER BY r.repositoryName")
    List<String> findDistinctRepositoryNamesByUserId(@Param("userId") UUID userId);

    long countByCreatedAtAfter(LocalDateTime since);

    @Query("SELECT r FROM Review r WHERE r.repository.organization.id = :orgId ORDER BY r.createdAt DESC")
    Page<Review> findByOrganizationId(@Param("orgId") UUID orgId, Pageable pageable);

    @Query("SELECT r FROM Review r WHERE r.repository.organization.id = :orgId AND r.status = :status ORDER BY r.createdAt DESC")
    List<Review> findByOrganizationIdAndStatus(@Param("orgId") UUID orgId, @Param("status") Review.ReviewStatus status);

    // Statistics queries
    @Query("SELECT COUNT(r) FROM Review r WHERE r.repository.organization.id = :orgId AND r.createdAt >= :since")
    Long countByOrganizationIdSince(@Param("orgId") UUID orgId, @Param("since") LocalDateTime since);

    @Query("SELECT COUNT(r) FROM Review r WHERE r.repository.organization.id = :orgId AND r.status = :status")
    Long countByOrganizationIdAndStatus(@Param("orgId") UUID orgId, @Param("status") Review.ReviewStatus status);

    @Query("SELECT SUM(r.issuesFound) FROM Review r WHERE r.repository.organization.id = :orgId AND r.createdAt >= :since")
    Long sumIssuesFoundByOrganizationIdSince(@Param("orgId") UUID orgId, @Param("since") LocalDateTime since);

    @Query("SELECT SUM(r.estimatedCost) FROM Review r WHERE r.repository.organization.id = :orgId AND r.createdAt >= :since")
    Double sumCostByOrganizationIdSince(@Param("orgId") UUID orgId, @Param("since") LocalDateTime since);

    @Query("SELECT DATE(r.createdAt), COUNT(r) FROM Review r WHERE r.createdAt >= :since GROUP BY DATE(r.createdAt)")
    List<Object[]> countByDayAfter(@Param("since") LocalDateTime since);

    /**
     * Find the most recent review for the same PR URL and commit SHA
     * Used to prevent duplicate reviews for unchanged PRs
     */
    Optional<Review> findFirstByPrUrlAndHeadCommitShaAndStatusOrderByCreatedAtDesc(
        String prUrl, String headCommitSha, Review.ReviewStatus status);

    /**
     * Find the most recent review for the same commit URL
     * Used to prevent duplicate commit reviews
     */
    Optional<Review> findFirstByCommitUrlAndStatusOrderByCreatedAtDesc(
        String commitUrl, Review.ReviewStatus status);

    /**
     * Get top repositories by review count with issue stats
     */
    @Query(value = """
        SELECT r.repository_name as repo_name,
               COUNT(r.id) as review_count,
               COALESCE(SUM(r.issues_found), 0) as issue_count,
               COALESCE(SUM(r.critical_issues), 0) as critical_count
        FROM reviews r
        WHERE r.created_at >= :since AND r.repository_name IS NOT NULL
        GROUP BY r.repository_name
        ORDER BY review_count DESC
        LIMIT :limit
        """, nativeQuery = true)
    List<Object[]> findTopRepositoriesByReviewCount(@Param("since") LocalDateTime since, @Param("limit") int limit);

    /**
     * Find review IDs with diffs older than retention period (for cleanup)
     */
    @Query("SELECT r.id FROM Review r WHERE r.rawDiff IS NOT NULL AND r.createdAt < :cutoff")
    List<UUID> findReviewIdsWithDiffsOlderThan(@Param("cutoff") LocalDateTime cutoff);

    /**
     * Clear raw diff for a specific review (bulk update)
     */
    @org.springframework.data.jpa.repository.Modifying
    @Query("UPDATE Review r SET r.rawDiff = NULL WHERE r.id IN :reviewIds")
    int clearRawDiffByIds(@Param("reviewIds") List<UUID> reviewIds);

    /**
     * Atomically update review status to CANCELLED only if currently cancellable.
     * Prevents race condition where review completes between status check and cancellation.
     *
     * @param reviewId The review ID to cancel (as bytes for native query)
     * @param cancelledAt The cancellation timestamp
     * @param cancelledById The ID of the user who cancelled (as bytes, can be null)
     * @param reason The cancellation reason (can be null)
     * @return Number of rows updated (0 if not cancellable, 1 if cancelled)
     */
    @org.springframework.data.jpa.repository.Modifying
    @Query(value = """
        UPDATE reviews
        SET status = 'CANCELLED',
            cancelled_at = :cancelledAt,
            cancelled_by_id = :cancelledById,
            cancellation_reason = :reason
        WHERE id = :reviewId
        AND status IN ('PENDING', 'IN_PROGRESS')
        """, nativeQuery = true)
    int updateStatusToCancelledIfCancellable(
            @Param("reviewId") byte[] reviewId,
            @Param("cancelledAt") LocalDateTime cancelledAt,
            @Param("cancelledById") byte[] cancelledById,
            @Param("reason") String reason);

    // ============ Atomic Progress Updates ============

    /**
     * Atomically increment optimization files analyzed counter.
     * Prevents lost updates when multiple files complete simultaneously in parallel processing.
     *
     * @param reviewId The review ID
     * @param currentFile The current file being processed (for display)
     * @return Number of rows updated
     */
    @org.springframework.data.jpa.repository.Modifying
    @Query(value = """
        UPDATE reviews
        SET optimization_files_analyzed = optimization_files_analyzed + 1,
            optimization_current_file = :currentFile
        WHERE id = :reviewId
        """, nativeQuery = true)
    int incrementOptimizationProgress(
            @Param("reviewId") byte[] reviewId,
            @Param("currentFile") String currentFile);

    /**
     * Atomically increment review files completed counter.
     * Prevents lost updates when multiple files complete simultaneously in parallel processing.
     *
     * @param reviewId The review ID
     * @param currentFile The current file being processed (for display)
     * @return Number of rows updated
     */
    @org.springframework.data.jpa.repository.Modifying
    @Query(value = """
        UPDATE reviews
        SET files_reviewed_count = files_reviewed_count + 1,
            current_file = :currentFile
        WHERE id = :reviewId
        """, nativeQuery = true)
    int incrementReviewProgress(
            @Param("reviewId") byte[] reviewId,
            @Param("currentFile") String currentFile);

    /**
     * Set the total files to review at the start of a review.
     * Only called once when review starts.
     *
     * @param reviewId The review ID
     * @param totalFiles Total number of files to review
     * @return Number of rows updated
     */
    @org.springframework.data.jpa.repository.Modifying
    @Query(value = """
        UPDATE reviews
        SET total_files_to_review = :totalFiles,
            files_reviewed_count = 0
        WHERE id = :reviewId
        """, nativeQuery = true)
    int setReviewTotalFiles(
            @Param("reviewId") byte[] reviewId,
            @Param("totalFiles") int totalFiles);

    /**
     * Update current file being processed without changing counter.
     * Used to show which file is currently being analyzed.
     */
    @org.springframework.data.jpa.repository.Modifying
    @Query(value = """
        UPDATE reviews
        SET optimization_current_file = :currentFile
        WHERE id = :reviewId
        """, nativeQuery = true)
    int updateOptimizationCurrentFile(
            @Param("reviewId") byte[] reviewId,
            @Param("currentFile") String currentFile);

    /**
     * Update current file being reviewed without changing counter.
     * Used to show which file is currently being reviewed (before increment).
     */
    @org.springframework.data.jpa.repository.Modifying
    @Query(value = """
        UPDATE reviews
        SET current_file = :currentFile
        WHERE id = :reviewId
        """, nativeQuery = true)
    int updateReviewCurrentFile(
            @Param("reviewId") byte[] reviewId,
            @Param("currentFile") String currentFile);

    // ============ Lightweight Progress Query ============

    /**
     * Get lightweight review progress for polling.
     * Only fetches fields needed for progress display, avoiding large fields like raw_diff.
     * Used by GET /api/reviews/{id}/status endpoint which is polled every 2 seconds.
     *
     * @param reviewId The review ID
     * @return ReviewProgressDto with only progress fields, or empty if not found
     */
    @Query("""
        SELECT new com.codelens.api.dto.ReviewProgressDto(
            r.id,
            CAST(r.status AS string),
            r.filesReviewedCount,
            r.totalFilesToReview,
            r.currentFile,
            r.startedAt,
            r.optimizationInProgress,
            r.optimizationFilesAnalyzed,
            r.optimizationTotalFiles,
            r.optimizationCurrentFile
        )
        FROM Review r
        WHERE r.id = :reviewId
        """)
    Optional<ReviewProgressDto> getProgress(@Param("reviewId") UUID reviewId);

    // ============ Trend Analytics ============

    /**
     * Get monthly trend metrics for an organization.
     * Returns review counts, average issues, and critical/high issue counts per month.
     */
    @Query(value = """
        SELECT DATE_FORMAT(r.created_at, '%Y-%m') as month,
               COUNT(*) as reviewCount,
               COALESCE(AVG(r.issues_found), 0) as avgIssues,
               COALESCE(SUM(r.critical_issues), 0) as criticalIssues,
               COALESCE(SUM(r.high_issues), 0) as highIssues
        FROM reviews r
        JOIN repositories repo ON r.repository_id = repo.id
        WHERE repo.organization_id = :orgId
          AND r.created_at >= :since
          AND r.status = 'COMPLETED'
        GROUP BY DATE_FORMAT(r.created_at, '%Y-%m')
        ORDER BY month
        """, nativeQuery = true)
    List<MonthlyMetrics> getMonthlyTrend(@Param("orgId") UUID orgId, @Param("since") LocalDateTime since);

    /**
     * Get monthly trend metrics for a specific user (for personal dashboard).
     */
    @Query(value = """
        SELECT DATE_FORMAT(r.created_at, '%Y-%m') as month,
               COUNT(*) as reviewCount,
               COALESCE(AVG(r.issues_found), 0) as avgIssues,
               COALESCE(SUM(r.critical_issues), 0) as criticalIssues,
               COALESCE(SUM(r.high_issues), 0) as highIssues
        FROM reviews r
        WHERE r.user_id = :userId
          AND r.created_at >= :since
          AND r.status = 'COMPLETED'
        GROUP BY DATE_FORMAT(r.created_at, '%Y-%m')
        ORDER BY month
        """, nativeQuery = true)
    List<MonthlyMetrics> getMonthlyTrendByUser(@Param("userId") UUID userId, @Param("since") LocalDateTime since);

    /**
     * Get issue category breakdown for an organization.
     */
    @Query(value = """
        SELECT ri.category, COUNT(*) as count
        FROM review_issues ri
        JOIN reviews r ON ri.review_id = r.id
        JOIN repositories repo ON r.repository_id = repo.id
        WHERE repo.organization_id = :orgId
          AND r.created_at >= :since
        GROUP BY ri.category
        ORDER BY count DESC
        """, nativeQuery = true)
    List<Object[]> getIssueCategoryBreakdown(@Param("orgId") UUID orgId, @Param("since") LocalDateTime since);

    /**
     * Get monthly trend metrics for ALL reviews (admin view).
     * No filtering by user or organization.
     */
    @Query(value = """
        SELECT DATE_FORMAT(r.created_at, '%Y-%m') as month,
               COUNT(*) as reviewCount,
               COALESCE(AVG(r.issues_found), 0) as avgIssues,
               COALESCE(SUM(r.critical_issues), 0) as criticalIssues,
               COALESCE(SUM(r.high_issues), 0) as highIssues
        FROM reviews r
        WHERE r.created_at >= :since
          AND r.status = 'COMPLETED'
        GROUP BY DATE_FORMAT(r.created_at, '%Y-%m')
        ORDER BY month
        """, nativeQuery = true)
    List<MonthlyMetrics> getMonthlyTrendAll(@Param("since") LocalDateTime since);
}
