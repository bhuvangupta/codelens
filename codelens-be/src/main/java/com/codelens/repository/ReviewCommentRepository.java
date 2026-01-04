package com.codelens.repository;

import com.codelens.model.entity.Review;
import com.codelens.model.entity.ReviewComment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ReviewCommentRepository extends JpaRepository<ReviewComment, UUID> {

    List<ReviewComment> findByReviewOrderByStartLineAsc(Review review);

    List<ReviewComment> findByReviewIdOrderByFilePathAscStartLineAsc(UUID reviewId);

    List<ReviewComment> findByReviewAndFilePath(Review review, String filePath);

    List<ReviewComment> findByReviewAndPostedToPrFalse(Review review);

    long countByReviewAndSeverity(Review review, ReviewComment.Severity severity);
}
