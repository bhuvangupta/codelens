package com.codelens.service;

import com.codelens.core.CommentFormatter;
import com.codelens.core.LanguageDetector;
import com.codelens.core.ReviewEngine;
import com.codelens.exception.NoOrganizationException;
import com.codelens.git.GitProvider;
import com.codelens.git.GitProviderFactory;
import com.codelens.model.entity.Organization;
import com.codelens.model.entity.Repository;
import com.codelens.model.entity.Review;
import com.codelens.model.entity.User;
import com.codelens.repository.LlmUsageRepository;
import com.codelens.repository.OrganizationRepository;
import com.codelens.repository.RepositoryRepository;
import com.codelens.repository.ReviewCommentRepository;
import com.codelens.repository.ReviewFileDiffRepository;
import com.codelens.repository.ReviewIssueRepository;
import com.codelens.repository.ReviewRepository;
import com.codelens.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The organization gate on review submission must fire AFTER the auto-association
 * attempt, so a user whose email domain matches the repository's organization is
 * enrolled rather than rejected, and BEFORE the review is saved / the async LLM
 * job is triggered, so a genuinely org-less user costs nothing.
 */
class ReviewSubmitOrganizationGateTest {

    private static final String PR_URL = "https://github.com/acme/widgets/pull/7";
    private static final String COMMIT_URL = "https://github.com/acme/widgets/commit/abc123";

    private ReviewRepository reviewRepository;
    private GitProviderFactory gitProviderFactory;
    private MembershipService membershipService;
    private ReviewService service;

    @BeforeEach
    void setUp() {
        reviewRepository = mock(ReviewRepository.class);
        gitProviderFactory = mock(GitProviderFactory.class);
        membershipService = mock(MembershipService.class);

        ReviewService real = new ReviewService(
                reviewRepository,
                mock(ReviewCommentRepository.class),
                mock(ReviewIssueRepository.class),
                mock(RepositoryRepository.class),
                mock(OrganizationRepository.class),
                mock(LlmUsageRepository.class),
                mock(UserRepository.class),
                mock(ReviewFileDiffRepository.class),
                mock(ReviewEngine.class),
                gitProviderFactory,
                mock(CommentFormatter.class),
                mock(ReviewProgressService.class),
                mock(LanguageDetector.class),
                membershipService,
                mock(ReviewCancellationService.class),
                mock(NotificationService.class));
        service = spy(real);
        service.setReviewAsyncService(mock(ReviewAsyncService.class));

        when(reviewRepository.save(any(Review.class))).thenAnswer(inv -> inv.getArgument(0));

        // submitReview / submitCommitReview register an afterCommit callback; in
        // production the @Transactional proxy has synchronization active.
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    // ---------- fixtures ----------

    private static Organization org(String name, boolean autoApprove) {
        Organization org = new Organization();
        org.setId(UUID.randomUUID());
        org.setName(name);
        org.setAutoApproveMembers(autoApprove);
        return org;
    }

    private static User user(String email, Organization organization) {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail(email);
        user.setProviderId("provider-" + email);
        user.setOrganization(organization);
        return user;
    }

    private static Repository repoIn(Organization organization) {
        Repository repo = new Repository();
        repo.setId(UUID.randomUUID());
        repo.setOwner("acme");
        repo.setName("widgets");
        repo.setFullName("acme/widgets");
        repo.setOrganization(organization);
        return repo;
    }

    private static ReviewService.SessionUserInfo session(String email) {
        return new ReviewService.SessionUserInfo("provider-" + email, email, "Test User", null);
    }

    /** Wire the PR path so control reaches the auto-association block. */
    private void stubPrPath(User resolvedUser, Repository repository) {
        GitProviderFactory.ParsedPrUrl parsed = new GitProviderFactory.ParsedPrUrl(
                Repository.GitProvider.GITHUB, "acme", "widgets", 7);
        when(gitProviderFactory.parsePrUrl(PR_URL)).thenReturn(parsed);

        GitProvider provider = mock(GitProvider.class);
        when(gitProviderFactory.getProvider(Repository.GitProvider.GITHUB)).thenReturn(provider);
        when(provider.getPullRequest(anyString(), anyString(), anyInt())).thenReturn(
                new GitProvider.PullRequestInfo(7, "Title", "Body", PR_URL, "author",
                        "main", "feature", "sha-head", "sha-base", "open", 1, 1, 1));

        when(reviewRepository.findFirstByPrUrlAndHeadCommitShaAndStatusOrderByCreatedAtDesc(
                anyString(), anyString(), any())).thenReturn(Optional.empty());

        if (resolvedUser != null) {
            doReturn(resolvedUser).when(service).findOrCreateUser(any());
        }
        doReturn(repository).when(service)
                .getOrCreateRepository(anyString(), anyString(), any(), any());
    }

    /** Wire the commit path so control reaches the auto-association block. */
    private void stubCommitPath(User resolvedUser, Repository repository) {
        GitProviderFactory.ParsedCommitUrl parsed = new GitProviderFactory.ParsedCommitUrl(
                Repository.GitProvider.GITHUB, "acme", "widgets", "abc123");
        when(gitProviderFactory.parseCommitUrl(COMMIT_URL)).thenReturn(parsed);

        GitProvider provider = mock(GitProvider.class);
        when(gitProviderFactory.getProvider(Repository.GitProvider.GITHUB)).thenReturn(provider);
        when(provider.getCommit(anyString(), anyString(), anyString())).thenReturn(
                new GitProvider.CommitInfo("abc123", "Commit message", "author",
                        "author@acme.com", COMMIT_URL, 1, 1, 1));

        when(reviewRepository.findFirstByCommitUrlAndStatusOrderByCreatedAtDesc(anyString(), any()))
                .thenReturn(Optional.empty());

        if (resolvedUser != null) {
            doReturn(resolvedUser).when(service).findOrCreateUser(any());
        }
        doReturn(repository).when(service)
                .getOrCreateRepository(anyString(), anyString(), any(), any());
    }

    /** Mimic MembershipService auto-approve: it mutates the very User instance passed in. */
    private void stubAutoApproveEnrolment() {
        when(membershipService.requestMembership(any(User.class), any(Organization.class)))
                .thenAnswer(inv -> {
                    User u = inv.getArgument(0);
                    Organization o = inv.getArgument(1);
                    u.setOrganization(o);
                    return true;
                });
    }

    @Nested
    class SubmitReview {

        @Test
        void enrolsOrglessUserWhoseEmailDomainMatchesInsteadOfRejecting() {
            // This is the regression: the controller used to 403 before the service ran
            Organization acme = org("acme", true);
            User newcomer = user("dev@acme.com", null);
            stubPrPath(newcomer, repoIn(acme));
            stubAutoApproveEnrolment();

            Review review = service.submitReview(PR_URL, session("dev@acme.com"), false, null, null);

            assertNotNull(review);
            assertEquals(acme, newcomer.getOrganization(), "auto-approve must enrol the in-memory user");
            verify(membershipService).requestMembership(newcomer, acme);
            verify(reviewRepository).save(any(Review.class));
        }

        @Test
        void attemptsEnrolmentBeforeRejectingWhenOrgRequiresApproval() {
            // Domain matches but org is not auto-approve: a PENDING request is raised,
            // and only then is the still-orgless user rejected.
            Organization acme = org("acme", false);
            User newcomer = user("dev@acme.com", null);
            stubPrPath(newcomer, repoIn(acme));
            when(membershipService.requestMembership(any(User.class), any(Organization.class)))
                    .thenReturn(false);

            assertThrows(NoOrganizationException.class,
                    () -> service.submitReview(PR_URL, session("dev@acme.com"), false, null, null));

            verify(membershipService).requestMembership(newcomer, acme);
            verify(reviewRepository, never()).save(any(Review.class));
        }

        @Test
        void rejectsOrglessUserWhoseEmailDomainDoesNotMatch() {
            Organization acme = org("acme", true);
            User outsider = user("dev@gmail.com", null);
            stubPrPath(outsider, repoIn(acme));

            NoOrganizationException ex = assertThrows(NoOrganizationException.class,
                    () -> service.submitReview(PR_URL, session("dev@gmail.com"), false, null, null));

            assertEquals("Join an organization before submitting reviews", ex.getMessage());
            verify(membershipService, never()).requestMembership(any(), any());
            verify(reviewRepository, never()).save(any(Review.class));
        }

        @Test
        void allowsUserWhoAlreadyHasAnOrganization() {
            Organization acme = org("acme", false);
            User member = user("dev@acme.com", acme);
            stubPrPath(member, repoIn(acme));

            Review review = service.submitReview(PR_URL, session("dev@acme.com"), false, null, null);

            assertNotNull(review);
            verify(membershipService, never()).requestMembership(any(), any());
            verify(reviewRepository).save(any(Review.class));
        }

        @Test
        void allowsReviewWithNoUserAtAll() {
            // Webhook-initiated reviews carry no user (user_id is NULL); the gate must not fire
            stubPrPath(null, repoIn(org("acme", false)));

            Review review = service.submitReview(PR_URL, null, false, null, null);

            assertNotNull(review);
            verify(reviewRepository).save(any(Review.class));
        }
    }

    @Nested
    class SubmitCommitReview {

        @Test
        void enrolsOrglessUserWhoseEmailDomainMatchesInsteadOfRejecting() {
            Organization acme = org("acme", true);
            User newcomer = user("dev@acme.com", null);
            stubCommitPath(newcomer, repoIn(acme));
            stubAutoApproveEnrolment();

            Review review = service.submitCommitReview(COMMIT_URL, session("dev@acme.com"), false, null, null);

            assertNotNull(review);
            assertEquals(acme, newcomer.getOrganization(), "auto-approve must enrol the in-memory user");
            verify(membershipService).requestMembership(newcomer, acme);
            verify(reviewRepository).save(any(Review.class));
        }

        @Test
        void attemptsEnrolmentBeforeRejectingWhenOrgRequiresApproval() {
            Organization acme = org("acme", false);
            User newcomer = user("dev@acme.com", null);
            stubCommitPath(newcomer, repoIn(acme));
            when(membershipService.requestMembership(any(User.class), any(Organization.class)))
                    .thenReturn(false);

            assertThrows(NoOrganizationException.class,
                    () -> service.submitCommitReview(COMMIT_URL, session("dev@acme.com"), false, null, null));

            verify(membershipService).requestMembership(newcomer, acme);
            verify(reviewRepository, never()).save(any(Review.class));
        }

        @Test
        void rejectsOrglessUserWhoseEmailDomainDoesNotMatch() {
            Organization acme = org("acme", true);
            User outsider = user("dev@gmail.com", null);
            stubCommitPath(outsider, repoIn(acme));

            NoOrganizationException ex = assertThrows(NoOrganizationException.class,
                    () -> service.submitCommitReview(COMMIT_URL, session("dev@gmail.com"), false, null, null));

            assertEquals("Join an organization before submitting reviews", ex.getMessage());
            verify(membershipService, never()).requestMembership(any(), any());
            verify(reviewRepository, never()).save(any(Review.class));
        }

        @Test
        void allowsUserWhoAlreadyHasAnOrganization() {
            Organization acme = org("acme", false);
            User member = user("dev@acme.com", acme);
            stubCommitPath(member, repoIn(acme));

            Review review = service.submitCommitReview(COMMIT_URL, session("dev@acme.com"), false, null, null);

            assertNotNull(review);
            verify(membershipService, never()).requestMembership(any(), any());
            verify(reviewRepository).save(any(Review.class));
        }

        @Test
        void allowsReviewWithNoUserAtAll() {
            stubCommitPath(null, repoIn(org("acme", false)));

            Review review = service.submitCommitReview(COMMIT_URL, null, false, null, null);

            assertNotNull(review);
            verify(reviewRepository).save(any(Review.class));
        }
    }
}
