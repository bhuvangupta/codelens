package com.codelens.repository;

import com.codelens.model.entity.ReviewFileDiff;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ReviewFileDiffRepository extends JpaRepository<ReviewFileDiff, UUID> {

    List<ReviewFileDiff> findByReviewIdOrderByFilePathAsc(UUID reviewId);

    void deleteByReviewId(UUID reviewId);

    /**
     * Delete file diffs for multiple reviews (batch cleanup)
     */
    @Modifying
    @Query("DELETE FROM ReviewFileDiff d WHERE d.review.id IN :reviewIds")
    int deleteByReviewIds(@Param("reviewIds") List<UUID> reviewIds);
}
