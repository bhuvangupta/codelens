package com.codelens.repository;

import com.codelens.model.entity.Review;
import com.codelens.model.entity.ReviewIssue;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface ReviewIssueRepository extends JpaRepository<ReviewIssue, UUID> {

    List<ReviewIssue> findByReview(Review review);

    List<ReviewIssue> findByReviewIdOrderBySeverityAscStartLineAsc(UUID reviewId);

    List<ReviewIssue> findByReviewAndCategory(Review review, ReviewIssue.Category category);

    List<ReviewIssue> findByReviewIdAndCategory(UUID reviewId, ReviewIssue.Category category);

    List<ReviewIssue> findByReviewAndSeverity(Review review, ReviewIssue.Severity severity);

    List<ReviewIssue> findByReviewAndAnalyzer(Review review, String analyzer);

    long countByReviewAndSeverity(Review review, ReviewIssue.Severity severity);

    long countBySeverity(ReviewIssue.Severity severity);

    long countByReviewAndCategory(Review review, ReviewIssue.Category category);

    long countByCveIdNotNull();

    @Query("SELECT ri FROM ReviewIssue ri WHERE ri.review = :review AND ri.category = 'CVE' ORDER BY ri.cvssScore DESC")
    List<ReviewIssue> findCveIssuesByReview(@Param("review") Review review);

    @Query("SELECT DATE(ri.createdAt), COUNT(ri) FROM ReviewIssue ri WHERE ri.createdAt >= :since GROUP BY DATE(ri.createdAt)")
    List<Object[]> countByDayAfter(@Param("since") LocalDateTime since);

    @Query("SELECT ri.source, COUNT(ri) FROM ReviewIssue ri WHERE ri.createdAt >= :since GROUP BY ri.source")
    List<Object[]> countBySourceAfter(@Param("since") LocalDateTime since);

    @Query("SELECT ri.category, COUNT(ri) FROM ReviewIssue ri WHERE ri.createdAt >= :since GROUP BY ri.category")
    List<Object[]> countByCategoryAfter(@Param("since") LocalDateTime since);

    /**
     * Get top categories/issue types by count, ordered by count descending
     */
    @Query(value = """
        SELECT ri.category, COUNT(ri.id) as cnt
        FROM review_issues ri
        WHERE ri.created_at >= :since
        GROUP BY ri.category
        ORDER BY cnt DESC
        LIMIT :limit
        """, nativeQuery = true)
    List<Object[]> findTopCategoriesByCount(@Param("since") LocalDateTime since, @Param("limit") int limit);

    // ============ Feedback Analytics ============

    @Query("SELECT COUNT(ri) FROM ReviewIssue ri WHERE ri.feedbackAt >= :since")
    long countByFeedbackAtAfter(@Param("since") LocalDateTime since);

    @Query("SELECT COUNT(ri) FROM ReviewIssue ri WHERE ri.isFalsePositive = true AND ri.feedbackAt >= :since")
    long countByIsFalsePositiveTrueAndFeedbackAtAfter(@Param("since") LocalDateTime since);

    @Query("SELECT COUNT(ri) FROM ReviewIssue ri WHERE ri.isHelpful = true AND ri.feedbackAt >= :since")
    long countByIsHelpfulTrueAndFeedbackAtAfter(@Param("since") LocalDateTime since);
}
