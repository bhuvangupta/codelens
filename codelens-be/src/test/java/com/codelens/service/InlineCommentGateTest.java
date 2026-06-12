package com.codelens.service;

import com.codelens.model.entity.ReviewComment;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InlineCommentGateTest {

    private static ReviewComment comment(ReviewComment.Severity severity, ReviewComment.Confidence confidence) {
        ReviewComment c = new ReviewComment();
        c.setSeverity(severity);
        c.setConfidence(confidence);
        return c;
    }

    @Test
    void postsHighAndCriticalWithSufficientConfidence() {
        assertTrue(ReviewService.shouldPostInline(comment(ReviewComment.Severity.CRITICAL, ReviewComment.Confidence.HIGH)));
        assertTrue(ReviewService.shouldPostInline(comment(ReviewComment.Severity.HIGH, ReviewComment.Confidence.MEDIUM)));
    }

    @Test
    void skipsLowConfidenceEvenWhenSeverityIsHigh() {
        assertFalse(ReviewService.shouldPostInline(comment(ReviewComment.Severity.CRITICAL, ReviewComment.Confidence.LOW)));
        assertFalse(ReviewService.shouldPostInline(comment(ReviewComment.Severity.HIGH, ReviewComment.Confidence.LOW)));
    }

    @Test
    void skipsMediumAndBelowSeverityRegardlessOfConfidence() {
        assertFalse(ReviewService.shouldPostInline(comment(ReviewComment.Severity.MEDIUM, ReviewComment.Confidence.HIGH)));
        assertFalse(ReviewService.shouldPostInline(comment(ReviewComment.Severity.LOW, ReviewComment.Confidence.HIGH)));
        assertFalse(ReviewService.shouldPostInline(comment(ReviewComment.Severity.INFO, null)));
    }

    @Test
    void nullConfidencePostsFailOpen() {
        // Legacy/text-parsed comments have no confidence — they post (fail-open)
        assertTrue(ReviewService.shouldPostInline(comment(ReviewComment.Severity.HIGH, null)));
        assertTrue(ReviewService.shouldPostInline(comment(ReviewComment.Severity.CRITICAL, null)));
    }
}
