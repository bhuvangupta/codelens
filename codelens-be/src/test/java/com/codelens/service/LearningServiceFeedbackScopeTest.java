package com.codelens.service;

import com.codelens.model.entity.Review;
import com.codelens.model.entity.ReviewIssue;
import com.codelens.model.entity.User;
import com.codelens.repository.RepoLearningRepository;
import com.codelens.repository.RepoPromptHintRepository;
import com.codelens.repository.ReviewIssueRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LearningServiceFeedbackScopeTest {

    @Mock private RepoLearningRepository learningRepository;
    @Mock private RepoPromptHintRepository hintRepository;
    @Mock private ReviewIssueRepository issueRepository;
    @Mock private FeedbackAggregationService feedbackAggregationService;

    @InjectMocks private LearningService service;

    private static final UUID REVIEW_A = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID REVIEW_B = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
    private static final UUID ISSUE_ID = UUID.fromString("cccccccc-0000-0000-0000-000000000003");

    private static ReviewIssue issueBelongingTo(UUID reviewId) {
        Review review = new Review();
        review.setId(reviewId);
        ReviewIssue issue = new ReviewIssue();
        issue.setId(ISSUE_ID);
        issue.setReview(review);
        issue.setRule("rule-1");
        issue.setAnalyzer("ai");
        issue.setSeverity(ReviewIssue.Severity.HIGH);
        return issue;
    }

    @Test
    void rejectsIssueBelongingToAnotherReview() {
        when(issueRepository.findById(ISSUE_ID))
            .thenReturn(Optional.of(issueBelongingTo(REVIEW_B)));

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () ->
            service.submitFeedback(REVIEW_A, ISSUE_ID,
                new LearningService.FeedbackRequest(false, true, "spam"), new User()));

        assertTrue(thrown.getMessage().contains("does not belong"));
        // The write must not have happened
        verify(issueRepository, never()).save(any());
        verify(feedbackAggregationService, never()).aggregateIfReady(any());
    }

    @Test
    void rejectsUnknownIssue() {
        when(issueRepository.findById(ISSUE_ID)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () ->
            service.submitFeedback(REVIEW_A, ISSUE_ID,
                new LearningService.FeedbackRequest(true, false, null), new User()));

        verify(issueRepository, never()).save(any());
    }

    @Test
    void acceptsIssueBelongingToTheNamedReview() {
        // Repository is null, so updateRepoLearning is skipped — this test pins the
        // scope check only, not the learning-update behaviour.
        when(issueRepository.findById(ISSUE_ID))
            .thenReturn(Optional.of(issueBelongingTo(REVIEW_A)));

        service.submitFeedback(REVIEW_A, ISSUE_ID,
            new LearningService.FeedbackRequest(true, false, "useful"), new User());

        verify(issueRepository).save(any(ReviewIssue.class));
    }
}
