package com.codelens.service;

import com.codelens.core.CommentFormatter;
import com.codelens.core.DiffParser;
import com.codelens.core.LanguageDetector;
import com.codelens.core.ReviewEngine;
import com.codelens.git.GitProviderFactory;
import com.codelens.model.entity.Organization;
import com.codelens.model.entity.Repository;
import com.codelens.model.entity.Review;
import com.codelens.model.entity.ReviewComment;
import com.codelens.model.entity.ReviewFileDiff;
import com.codelens.model.entity.ReviewIssue;
import com.codelens.repository.LlmUsageRepository;
import com.codelens.repository.OrganizationRepository;
import com.codelens.repository.RepositoryRepository;
import com.codelens.repository.ReviewCommentRepository;
import com.codelens.repository.ReviewFileDiffRepository;
import com.codelens.repository.ReviewIssueRepository;
import com.codelens.repository.ReviewRepository;
import com.codelens.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * A review must never be visible as COMPLETED without its issues. The status flip and the
 * issue/comment/diff rows commit together in ReviewProgressService's REQUIRES_NEW transaction,
 * before the slow PR posting runs. Previously the rows were saved in the outer transaction and
 * only committed after posting finished, so the UI saw "71 issues" with an empty list, and a
 * restart mid-posting lost them for good.
 */
class ReviewResultPersistenceTest {

    private static final UUID REVIEW_ID = UUID.randomUUID();

    private static ReviewEngine.ReviewResult result(List<ReviewIssue> issues, List<ReviewComment> comments,
                                                    List<DiffParser.FileDiff> diffs) {
        return new ReviewEngine.ReviewResult("summary", issues, comments, 1, 2, 0, 100, 50,
            "gemini", 0.01, "raw diff", diffs, null, null);
    }

    private static ReviewIssue issue(ReviewIssue.Severity severity) {
        ReviewIssue issue = new ReviewIssue();
        issue.setSeverity(severity);
        return issue;
    }

    private static DiffParser.FileDiff addedFile(String path) {
        List<DiffParser.DiffLine> lines = new ArrayList<>(List.of(
            new DiffParser.DiffLine(DiffParser.DiffLine.Type.ADDITION, 1, "class A {"),
            new DiffParser.DiffLine(DiffParser.DiffLine.Type.ADDITION, 2, "}")));
        DiffParser.Hunk hunk = new DiffParser.Hunk(0, 0, 1, 2, "", lines);
        return new DiffParser.FileDiff("/dev/null", path, new ArrayList<>(List.of(hunk)));
    }

    @Nested
    class SaveReviewResults {

        private ReviewIssueRepository issueRepository;
        private ReviewCommentRepository commentRepository;
        private ReviewFileDiffRepository fileDiffRepository;
        private ReviewProgressService service;
        private Review review;

        @BeforeEach
        void setUp() {
            ReviewRepository reviewRepository = mock(ReviewRepository.class);
            issueRepository = mock(ReviewIssueRepository.class);
            commentRepository = mock(ReviewCommentRepository.class);
            fileDiffRepository = mock(ReviewFileDiffRepository.class);
            service = new ReviewProgressService(reviewRepository, issueRepository, commentRepository, fileDiffRepository);

            review = new Review();
            review.setId(REVIEW_ID);
            review.setStatus(Review.ReviewStatus.IN_PROGRESS);
            when(reviewRepository.findById(REVIEW_ID)).thenReturn(Optional.of(review));
        }

        @Test
        @SuppressWarnings("unchecked")
        void completesReviewTogetherWithItsIssuesCommentsAndDiffs() {
            List<ReviewIssue> issues = List.of(issue(ReviewIssue.Severity.CRITICAL), issue(ReviewIssue.Severity.LOW));
            List<ReviewComment> comments = List.of(new ReviewComment());

            service.saveReviewResults(REVIEW_ID, result(issues, comments, List.of(addedFile("src/A.java"))));

            assertEquals(Review.ReviewStatus.COMPLETED, review.getStatus());
            assertEquals(2, review.getIssuesFound());
            assertEquals(1, review.getCriticalIssues());
            assertEquals(1, review.getLowIssues());

            ArgumentCaptor<List<ReviewIssue>> savedIssues = ArgumentCaptor.forClass(List.class);
            verify(issueRepository).saveAll(savedIssues.capture());
            assertEquals(2, savedIssues.getValue().size());
            savedIssues.getValue().forEach(i -> assertSame(review, i.getReview()));

            ArgumentCaptor<List<ReviewComment>> savedComments = ArgumentCaptor.forClass(List.class);
            verify(commentRepository).saveAll(savedComments.capture());
            assertEquals(1, savedComments.getValue().size());
            assertSame(review, savedComments.getValue().get(0).getReview());

            ArgumentCaptor<List<ReviewFileDiff>> savedDiffs = ArgumentCaptor.forClass(List.class);
            verify(fileDiffRepository).saveAll(savedDiffs.capture());
            ReviewFileDiff diff = savedDiffs.getValue().get(0);
            assertSame(review, diff.getReview());
            assertEquals("src/A.java", diff.getFilePath());
            assertEquals(ReviewFileDiff.FileStatus.ADDED, diff.getStatus());
            assertEquals(2, diff.getAdditions());
            assertEquals("@@ -0,0 +1,2 @@\n+class A {\n+}\n", diff.getPatch());
        }
    }

    @Nested
    class ReviewPaths {

        private ReviewIssueRepository issueRepository;
        private ReviewCommentRepository commentRepository;
        private ReviewFileDiffRepository fileDiffRepository;
        private ReviewProgressService progressService;
        private ReviewEngine reviewEngine;
        private com.codelens.git.GitProvider gitProvider;
        private ReviewService service;

        @BeforeEach
        void setUp() {
            ReviewRepository reviewRepository = mock(ReviewRepository.class);
            issueRepository = mock(ReviewIssueRepository.class);
            commentRepository = mock(ReviewCommentRepository.class);
            fileDiffRepository = mock(ReviewFileDiffRepository.class);
            progressService = mock(ReviewProgressService.class);
            reviewEngine = mock(ReviewEngine.class);
            gitProvider = mock(com.codelens.git.GitProvider.class);
            GitProviderFactory gitProviderFactory = mock(GitProviderFactory.class);
            when(gitProviderFactory.getProvider(any(Repository.GitProvider.class))).thenReturn(gitProvider);

            service = new ReviewService(
                reviewRepository,
                commentRepository,
                issueRepository,
                mock(RepositoryRepository.class),
                mock(OrganizationRepository.class),
                mock(LlmUsageRepository.class),
                mock(UserRepository.class),
                fileDiffRepository,
                reviewEngine,
                gitProviderFactory,
                mock(CommentFormatter.class),
                progressService,
                mock(LanguageDetector.class),
                mock(MembershipService.class),
                mock(ReviewCancellationService.class),
                mock(NotificationService.class));

            Organization org = new Organization();
            org.setPostCommentsEnabled(true);
            org.setPostInlineCommentsEnabled(true);
            Repository repository = new Repository();
            repository.setOrganization(org);
            repository.setLanguage("Java");
            Review review = new Review();
            review.setId(REVIEW_ID);
            review.setRepository(repository);
            when(reviewRepository.findById(REVIEW_ID)).thenReturn(Optional.of(review));
        }

        private ReviewEngine.ReviewResult someResult() {
            return result(List.of(issue(ReviewIssue.Severity.HIGH)), List.of(new ReviewComment()),
                List.of(addedFile("src/A.java")));
        }

        @Test
        void prReviewPersistsResultsBeforePostingToThePr() {
            ReviewEngine.ReviewResult result = someResult();
            when(reviewEngine.executeReview(any(ReviewEngine.ReviewRequest.class), any())).thenReturn(result);

            service.executeReview(REVIEW_ID, Repository.GitProvider.GITHUB, "acme", "widgets", 7);

            InOrder order = inOrder(progressService, gitProvider);
            order.verify(progressService).saveReviewResults(REVIEW_ID, result);
            order.verify(gitProvider).postComment(anyString(), anyString(), anyInt(), any());
            verifyNoInteractions(issueRepository, commentRepository, fileDiffRepository);
        }

        @Test
        void commitReviewPersistsResultsThroughTheSameTransaction() {
            ReviewEngine.ReviewResult result = someResult();
            when(reviewEngine.executeCommitReview(any(ReviewEngine.CommitReviewRequest.class), any())).thenReturn(result);

            service.executeCommitReview(REVIEW_ID, Repository.GitProvider.GITHUB, "acme", "widgets", "abc123");

            verify(progressService).saveReviewResults(REVIEW_ID, result);
            verifyNoInteractions(issueRepository, commentRepository, fileDiffRepository);
        }
    }
}
