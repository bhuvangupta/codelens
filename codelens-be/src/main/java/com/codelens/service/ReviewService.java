package com.codelens.service;

import com.codelens.core.CommentFormatter;
import com.codelens.core.DiffParser;
import com.codelens.core.LanguageDetector;
import com.codelens.core.ReviewEngine;
import com.codelens.git.GitProvider.InlineComment;
import com.codelens.git.GitProvider.RepositoryInfo;
import com.codelens.git.GitProviderFactory;
import com.codelens.model.entity.*;
import com.codelens.model.entity.Repository.GitProvider;
import com.codelens.repository.*;
import jakarta.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
public class ReviewService implements ReviewExecutor {

    @Value("${codelens.frontend-url:}")
    private String frontendUrl;

    private final ReviewRepository reviewRepository;
    private final ReviewCommentRepository commentRepository;
    private final ReviewIssueRepository issueRepository;
    private final RepositoryRepository repositoryRepository;
    private final OrganizationRepository organizationRepository;
    private final LlmUsageRepository llmUsageRepository;
    private final UserRepository userRepository;
    private final ReviewFileDiffRepository fileDiffRepository;
    private final ReviewEngine reviewEngine;
    private final GitProviderFactory gitProviderFactory;
    private final CommentFormatter commentFormatter;
    private final ReviewProgressService progressService;
    private final LanguageDetector languageDetector;
    private final MembershipService membershipService;
    private final ReviewCancellationService cancellationService;
    private final NotificationService notificationService;
    private OptimizationService optimizationService;
    private ReviewAsyncService reviewAsyncService;

    public ReviewService(
            ReviewRepository reviewRepository,
            ReviewCommentRepository commentRepository,
            ReviewIssueRepository issueRepository,
            RepositoryRepository repositoryRepository,
            OrganizationRepository organizationRepository,
            LlmUsageRepository llmUsageRepository,
            UserRepository userRepository,
            ReviewFileDiffRepository fileDiffRepository,
            ReviewEngine reviewEngine,
            GitProviderFactory gitProviderFactory,
            CommentFormatter commentFormatter,
            ReviewProgressService progressService,
            LanguageDetector languageDetector,
            MembershipService membershipService,
            ReviewCancellationService cancellationService,
            NotificationService notificationService) {
        this.reviewRepository = reviewRepository;
        this.commentRepository = commentRepository;
        this.issueRepository = issueRepository;
        this.repositoryRepository = repositoryRepository;
        this.organizationRepository = organizationRepository;
        this.llmUsageRepository = llmUsageRepository;
        this.userRepository = userRepository;
        this.fileDiffRepository = fileDiffRepository;
        this.reviewEngine = reviewEngine;
        this.gitProviderFactory = gitProviderFactory;
        this.commentFormatter = commentFormatter;
        this.progressService = progressService;
        this.languageDetector = languageDetector;
        this.membershipService = membershipService;
        this.cancellationService = cancellationService;
        this.notificationService = notificationService;
    }

    @Autowired
    @org.springframework.context.annotation.Lazy
    public void setReviewAsyncService(ReviewAsyncService reviewAsyncService) {
        this.reviewAsyncService = reviewAsyncService;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public void setOptimizationService(OptimizationService optimizationService) {
        this.optimizationService = optimizationService;
    }

    /**
     * User info from session headers
     */
    public record SessionUserInfo(
        String providerId,
        String email,
        String name,
        String avatarUrl
    ) {}

    /**
     * Submit a PR for review (used by API)
     */
    @Transactional
    public Review submitReview(String prUrl, SessionUserInfo sessionUser) {
        return submitReview(prUrl, sessionUser, false, null, null);
    }

    /**
     * Submit a PR for review with optimization option
     */
    public Review submitReview(String prUrl, SessionUserInfo sessionUser, boolean includeOptimization) {
        return submitReview(prUrl, sessionUser, includeOptimization, null, null);
    }

    /**
     * Submit a PR for review with optimization and ticket scope validation
     */
    @Transactional
    public Review submitReview(String prUrl, SessionUserInfo sessionUser, boolean includeOptimization,
            String ticketContent, String ticketId) {
        GitProviderFactory.ParsedPrUrl parsed = gitProviderFactory.parsePrUrl(prUrl);

        // Fetch PR details from Git provider
        com.codelens.git.GitProvider gitProvider = gitProviderFactory.getProvider(parsed.provider());
        com.codelens.git.GitProvider.PullRequestInfo prInfo = gitProvider.getPullRequest(
            parsed.owner(), parsed.repo(), parsed.prNumber());

        // Check if we already have a completed review for this exact commit
        Optional<Review> existingReview = reviewRepository.findFirstByPrUrlAndHeadCommitShaAndStatusOrderByCreatedAtDesc(
            prUrl, prInfo.headCommitSha(), Review.ReviewStatus.COMPLETED);
        if (existingReview.isPresent()) {
            log.info("Review already completed for {} at commit {}, returning existing review",
                prUrl, prInfo.headCommitSha());
            return existingReview.get();
        }

        // Check if there's a review in progress for this commit
        Optional<Review> inProgressReview = reviewRepository.findFirstByPrUrlAndHeadCommitShaAndStatusOrderByCreatedAtDesc(
            prUrl, prInfo.headCommitSha(), Review.ReviewStatus.IN_PROGRESS);
        if (inProgressReview.isPresent()) {
            log.info("Review already in progress for {} at commit {}, returning existing review",
                prUrl, prInfo.headCommitSha());
            return inProgressReview.get();
        }

        // Check if there's a pending review for this commit
        Optional<Review> pendingReview = reviewRepository.findFirstByPrUrlAndHeadCommitShaAndStatusOrderByCreatedAtDesc(
            prUrl, prInfo.headCommitSha(), Review.ReviewStatus.PENDING);
        if (pendingReview.isPresent()) {
            log.info("Review already pending for {} at commit {}, returning existing review",
                prUrl, prInfo.headCommitSha());
            return pendingReview.get();
        }

        Review review = new Review();
        review.setPrUrl(prUrl);
        review.setPrNumber(parsed.prNumber());
        review.setPrTitle(prInfo.title());
        review.setPrDescription(prInfo.description());
        review.setPrAuthor(prInfo.author());
        review.setBaseBranch(prInfo.baseBranch());
        review.setHeadBranch(prInfo.headBranch());
        review.setHeadCommitSha(prInfo.headCommitSha());
        review.setRepositoryName(parsed.owner() + "/" + parsed.repo());
        review.setStatus(Review.ReviewStatus.IN_PROGRESS);
        review.setIncludeOptimization(includeOptimization);
        review.setTicketContent(ticketContent);
        review.setTicketId(ticketId);
        review.setCreatedAt(LocalDateTime.now());

        // Find or create user from session info
        User user = null;
        if (sessionUser != null && sessionUser.providerId() != null) {
            user = findOrCreateUser(sessionUser);
            review.setUser(user);
        }

        // Get or create repository - use user's org if available to avoid duplicate orgs
        Repository repository = getOrCreateRepository(parsed.owner(), parsed.repo(), parsed.provider(), user);
        review.setRepository(repository);

        // Auto-associate user with organization only if email domain matches org name
        // Uses membership service to handle auto-approve vs pending request
        if (review.getUser() != null && repository.getOrganization() != null) {
            User reviewUser = review.getUser();
            if (user.getOrganization() == null) {
                Organization org = repository.getOrganization();
                if (isEmailDomainMatchingOrg(user.getEmail(), org.getName())) {
                    boolean autoApproved = membershipService.requestMembership(user, org);
                    if (autoApproved) {
                        log.info("Auto-approved user {} to organization {} (email domain match)",
                            user.getEmail(), org.getName());
                    } else {
                        log.info("Created pending membership request for user {} to organization {} (email domain match)",
                            user.getEmail(), org.getName());
                    }
                } else {
                    log.debug("User {} not auto-associated with org {} (email domain doesn't match)",
                        user.getEmail(), org.getName());
                }
            }
        }

        review = reviewRepository.save(review);

        // Trigger async review AFTER transaction commits (so the review is visible to async thread)
        UUID finalReviewId = review.getId();
        GitProvider finalProvider = parsed.provider();
        String finalOwner = parsed.owner();
        String finalRepo = parsed.repo();
        int finalPrNumber = parsed.prNumber();

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                java.util.concurrent.CompletableFuture<Void> future = reviewAsyncService.executeReviewAsync(
                    finalReviewId, finalProvider, finalOwner, finalRepo, finalPrNumber);
                cancellationService.registerRunningReview(finalReviewId, future);
            }
        });

        return review;
    }

    /**
     * Find existing user or create new one from session info
     */
    @Transactional
    protected User findOrCreateUser(SessionUserInfo sessionUser) {
        // Try to find by provider ID first
        Optional<User> existingUser = userRepository.findByProviderAndProviderId("google", sessionUser.providerId());
        if (existingUser.isPresent()) {
            User user = existingUser.get();
            // Update last login and any changed info
            user.setLastLoginAt(LocalDateTime.now());
            if (sessionUser.name() != null) user.setName(sessionUser.name());
            if (sessionUser.avatarUrl() != null) user.setAvatarUrl(sessionUser.avatarUrl());
            return userRepository.save(user);
        }

        // Try to find by email as fallback
        if (sessionUser.email() != null) {
            Optional<User> userByEmail = userRepository.findByEmail(sessionUser.email());
            if (userByEmail.isPresent()) {
                User user = userByEmail.get();
                // Update provider ID if not set
                if (user.getProviderId() == null) {
                    user.setProviderId(sessionUser.providerId());
                }
                user.setLastLoginAt(LocalDateTime.now());
                return userRepository.save(user);
            }
        }

        // Create new user
        User newUser = User.builder()
            .email(sessionUser.email())
            .name(sessionUser.name() != null ? sessionUser.name() : "User")
            .avatarUrl(sessionUser.avatarUrl())
            .provider("google")
            .providerId(sessionUser.providerId())
            .role(User.UserRole.MEMBER)
            .lastLoginAt(LocalDateTime.now())
            .build();

        newUser = userRepository.save(newUser);
        log.info("Created new user: {} (id={})", newUser.getEmail(), newUser.getId());
        return newUser;
    }

    /**
     * Submit a review from webhook
     */
    @Transactional
    public void submitReviewFromWebhook(GitProvider provider, String owner, String repo, int prNumber, String prUrl) {
        log.info("Webhook triggered review for {}/{} #{}", owner, repo, prNumber);

        // Fetch PR details from Git provider
        com.codelens.git.GitProvider gitProvider = gitProviderFactory.getProvider(provider);
        com.codelens.git.GitProvider.PullRequestInfo prInfo = gitProvider.getPullRequest(owner, repo, prNumber);

        // Check if we already have a review for this exact commit (any non-failed status)
        Optional<Review> existingCompleted = reviewRepository.findFirstByPrUrlAndHeadCommitShaAndStatusOrderByCreatedAtDesc(
            prUrl, prInfo.headCommitSha(), Review.ReviewStatus.COMPLETED);
        if (existingCompleted.isPresent()) {
            log.info("Review already completed for {} at commit {}, skipping", prUrl, prInfo.headCommitSha());
            return;
        }

        Optional<Review> existingInProgress = reviewRepository.findFirstByPrUrlAndHeadCommitShaAndStatusOrderByCreatedAtDesc(
            prUrl, prInfo.headCommitSha(), Review.ReviewStatus.IN_PROGRESS);
        if (existingInProgress.isPresent()) {
            log.info("Review already in progress for {} at commit {}, skipping", prUrl, prInfo.headCommitSha());
            return;
        }

        Optional<Review> existingPending = reviewRepository.findFirstByPrUrlAndHeadCommitShaAndStatusOrderByCreatedAtDesc(
            prUrl, prInfo.headCommitSha(), Review.ReviewStatus.PENDING);
        if (existingPending.isPresent()) {
            log.info("Review already pending for {} at commit {}, skipping", prUrl, prInfo.headCommitSha());
            return;
        }

        // Create new review
        Review review = new Review();
        review.setPrUrl(prUrl);
        review.setPrNumber(prNumber);
        review.setPrTitle(prInfo.title());
        review.setPrDescription(prInfo.description());
        review.setPrAuthor(prInfo.author());
        review.setBaseBranch(prInfo.baseBranch());
        review.setHeadBranch(prInfo.headBranch());
        review.setHeadCommitSha(prInfo.headCommitSha());
        review.setRepositoryName(owner + "/" + repo);
        review.setStatus(Review.ReviewStatus.IN_PROGRESS);
        review.setCreatedAt(LocalDateTime.now());

        // Get or create repository
        Repository repository = getOrCreateRepository(owner, repo, provider);
        review.setRepository(repository);

        review = reviewRepository.save(review);

        // Trigger async review AFTER transaction commits
        UUID finalReviewId = review.getId();
        GitProvider finalProvider = provider;
        String finalOwner = owner;
        String finalRepo = repo;
        int finalPrNumber = prNumber;

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                java.util.concurrent.CompletableFuture<Void> future = reviewAsyncService.executeReviewAsync(
                    finalReviewId, finalProvider, finalOwner, finalRepo, finalPrNumber);
                cancellationService.registerRunningReview(finalReviewId, future);
            }
        });
    }

    @Override
    public void handleReviewFailure(UUID reviewId, String errorMessage) {
        log.error("Review {} failed: {}", reviewId, errorMessage);
        progressService.updateStatusFailed(reviewId, errorMessage);
        progressService.clearProgressState(reviewId);  // Clean up progress tracking
        reviewRepository.findById(reviewId).ifPresent(notificationService::notifyReviewFailed);
        cancellationService.unregisterReview(reviewId);
    }

    @Override
    @Transactional
    public void executeReview(UUID reviewId, GitProvider provider, String owner, String repo, int prNumber) {
        log.info("Executing review {} for {}/{} #{}", reviewId, owner, repo, prNumber);

        // Initialize progress tracking state for this review
        progressService.clearProgressState(reviewId);

        Review review = reviewRepository.findById(reviewId)
            .orElseThrow(() -> new IllegalArgumentException("Review not found: " + reviewId));

        // Get organization ID for custom rules
        UUID organizationId = review.getRepository() != null && review.getRepository().getOrganization() != null
            ? review.getRepository().getOrganization().getId()
            : null;

        // Execute the review with progress tracking
        ReviewEngine.ReviewRequest request = new ReviewEngine.ReviewRequest(
            provider, owner, repo, prNumber, organizationId,
            review.getTicketContent(), review.getTicketId());
        ReviewEngine.ReviewResult result;
        try {
            result = reviewEngine.executeReview(request, progress -> {
                // Update progress in database via separate service (for proper transaction handling)
                progressService.updateProgress(reviewId, progress);
            });
        } catch (com.codelens.core.ReviewCancelledException e) {
            log.info("Review {} was cancelled", reviewId);
            progressService.clearProgressState(reviewId);  // Clean up progress tracking
            // The cancellation status is already set by ReviewCancellationService
            return;
        }

        // Save results
        review.setSummary(result.summary());
        review.setFilesChanged(result.filesReviewed());
        review.setLinesAdded(result.linesAdded());
        review.setLinesDeleted(result.linesRemoved());
        review.setIssuesFound(result.issues().size());
        review.setCriticalIssues((int) result.issues().stream()
            .filter(i -> i.getSeverity() == ReviewIssue.Severity.CRITICAL).count());
        review.setHighIssues((int) result.issues().stream()
            .filter(i -> i.getSeverity() == ReviewIssue.Severity.HIGH).count());
        review.setMediumIssues((int) result.issues().stream()
            .filter(i -> i.getSeverity() == ReviewIssue.Severity.MEDIUM).count());
        review.setLowIssues((int) result.issues().stream()
            .filter(i -> i.getSeverity() == ReviewIssue.Severity.LOW).count());
        review.setInputTokens(result.totalInputTokens());
        review.setOutputTokens(result.totalOutputTokens());
        review.setTicketScopeResult(result.ticketScopeResult());
        review.setTicketScopeAligned(result.ticketScopeAligned());
        review.setStatus(Review.ReviewStatus.COMPLETED);
        review.setCompletedAt(LocalDateTime.now());
        reviewRepository.save(review);

        // Save issues
        for (ReviewIssue issue : result.issues()) {
            issue.setReview(review);
            issueRepository.save(issue);
        }

        // Save comments
        for (ReviewComment comment : result.comments()) {
            comment.setReview(review);
            commentRepository.save(comment);
        }

        // Set LLM provider used
        review.setLlmProvider(result.llmProvider());
        review.setEstimatedCost(result.estimatedCost());

        // Store the diff for later viewing
        review.setRawDiff(result.rawDiff());
        reviewRepository.save(review);

        // Save parsed file diffs
        if (result.parsedDiffs() != null) {
            for (DiffParser.FileDiff fileDiff : result.parsedDiffs()) {
                // Calculate additions/deletions for this file
                int additions = 0;
                int deletions = 0;
                for (DiffParser.Hunk hunk : fileDiff.hunks()) {
                    for (DiffParser.DiffLine line : hunk.lines()) {
                        if (line.type() == DiffParser.DiffLine.Type.ADDITION) additions++;
                        else if (line.type() == DiffParser.DiffLine.Type.DELETION) deletions++;
                    }
                }

                // Determine file status
                ReviewFileDiff.FileStatus status = ReviewFileDiff.FileStatus.MODIFIED;
                if (fileDiff.oldPath() == null || fileDiff.oldPath().equals("/dev/null")) {
                    status = ReviewFileDiff.FileStatus.ADDED;
                } else if (fileDiff.newPath() == null || fileDiff.newPath().equals("/dev/null")) {
                    status = ReviewFileDiff.FileStatus.DELETED;
                } else if (!fileDiff.oldPath().equals(fileDiff.newPath())) {
                    status = ReviewFileDiff.FileStatus.RENAMED;
                }

                // Build patch string from hunks
                StringBuilder patchBuilder = new StringBuilder();
                for (DiffParser.Hunk hunk : fileDiff.hunks()) {
                    patchBuilder.append("@@ -").append(hunk.oldStart()).append(",").append(hunk.oldCount())
                        .append(" +").append(hunk.newStart()).append(",").append(hunk.newCount()).append(" @@");
                    if (hunk.context() != null && !hunk.context().isEmpty()) {
                        patchBuilder.append(" ").append(hunk.context());
                    }
                    patchBuilder.append("\n");
                    for (DiffParser.DiffLine line : hunk.lines()) {
                        switch (line.type()) {
                            case ADDITION -> patchBuilder.append("+");
                            case DELETION -> patchBuilder.append("-");
                            case CONTEXT -> patchBuilder.append(" ");
                        }
                        patchBuilder.append(line.content()).append("\n");
                    }
                }

                ReviewFileDiff reviewFileDiff = ReviewFileDiff.builder()
                    .review(review)
                    .filePath(fileDiff.getPath())
                    .oldPath(fileDiff.oldPath())
                    .status(status)
                    .additions(additions)
                    .deletions(deletions)
                    .patch(patchBuilder.toString())
                    .build();
                fileDiffRepository.save(reviewFileDiff);
            }
        }

        // Track LLM usage with actual provider and cost
        trackLlmUsage(review, result);

        // Post results to PR
        postReviewToPr(provider, owner, repo, prNumber, review, result);

        // Update repository language based on PR files if not set from API
        updateRepositoryLanguageFromFiles(review.getRepository(), provider, owner, repo, prNumber);

        log.info("Review {} completed with {} issues", reviewId, result.issues().size());

        // Clean up progress tracking state
        progressService.clearProgressState(reviewId);

        // Send notifications
        notificationService.notifyReviewCompleted(review);

        // Run optimization analysis if requested
        if (Boolean.TRUE.equals(review.getIncludeOptimization()) && optimizationService != null) {
            log.info("Starting optimization analysis for review {} (includeOptimization=true)", reviewId);
            optimizationService.analyzeOptimizationsAsync(reviewId);
        }
    }

    /**
     * Update repository language based on files in the PR if not already set from API
     */
    private void updateRepositoryLanguageFromFiles(Repository repository, GitProvider provider,
                                                    String owner, String repoName, int prNumber) {
        if (repository == null) {
            return;
        }

        // Only update if language is not already set from API
        if (repository.getLanguage() != null && !repository.getLanguage().isEmpty()) {
            log.debug("Repository {} already has language: {}", repository.getFullName(), repository.getLanguage());
            return;
        }

        try {
            // Fetch changed files from Git provider
            com.codelens.git.GitProvider gitProvider = gitProviderFactory.getProvider(provider);
            List<com.codelens.git.GitProvider.ChangedFile> changedFiles = gitProvider.getChangedFiles(owner, repoName, prNumber);

            List<String> filenames = changedFiles.stream()
                .map(com.codelens.git.GitProvider.ChangedFile::filename)
                .filter(path -> path != null && !path.isEmpty())
                .toList();

            if (filenames.isEmpty()) {
                log.debug("No files to detect language from for repository {}", repository.getFullName());
                return;
            }

            String detectedLanguage = languageDetector.detectPrimaryLanguage(filenames);
            if (detectedLanguage != null) {
                repository.setLanguage(detectedLanguage);
                repositoryRepository.save(repository);
                log.info("Updated repository {} language to {} based on {} PR files",
                    repository.getFullName(), detectedLanguage, filenames.size());
            }
        } catch (Exception e) {
            log.warn("Could not detect language from PR files for {}: {}", repository.getFullName(), e.getMessage());
        }
    }

    private void postReviewToPr(GitProvider provider, String owner, String repo, int prNumber,
                                 Review review, ReviewEngine.ReviewResult result) {
        // Check if posting comments to PR is enabled for this organization
        Organization org = review.getRepository() != null ? review.getRepository().getOrganization() : null;
        boolean postCommentsEnabled = org != null && Boolean.TRUE.equals(org.getPostCommentsEnabled());
        boolean postInlineCommentsEnabled = org != null && Boolean.TRUE.equals(org.getPostInlineCommentsEnabled());

        if (!postCommentsEnabled) {
            log.info("Posting comments to PR is disabled for organization {}. " +
                "Review results are available in CodeLens UI only.",
                org != null ? org.getName() : "unknown");
            return;
        }

        try {
            com.codelens.git.GitProvider gitProvider = gitProviderFactory.getProvider(provider);

            // Build review URL for the CodeLens dashboard
            String reviewUrl = buildReviewUrl(review.getId());

            // Post summary comment (table of issues)
            String summary = commentFormatter.formatSummary(
                result.summary(),
                result.issues(),
                new CommentFormatter.ReviewStats(
                    result.filesReviewed(),
                    result.linesAdded(),
                    result.linesRemoved()
                ),
                reviewUrl
            );
            gitProvider.postComment(owner, repo, prNumber, summary);

            // Post inline comments for high/critical issues (only if enabled)
            if (postInlineCommentsEnabled) {
                for (ReviewComment comment : result.comments()) {
                    if (comment.getSeverity() == ReviewComment.Severity.CRITICAL ||
                        comment.getSeverity() == ReviewComment.Severity.HIGH) {
                        try {
                            gitProvider.postInlineComment(owner, repo, prNumber,
                                new InlineComment(
                                    comment.getFilePath(),
                                    comment.getLineNumber(),
                                    commentFormatter.formatInlineComment(comment),
                                    comment.getCommitSha()
                                )
                            );
                        } catch (Exception e) {
                            log.warn("Failed to post inline comment at {}:{}", comment.getFilePath(), comment.getLineNumber(), e);
                        }
                    }
                }
            } else {
                log.debug("Inline comments disabled for organization {}, skipping {} comments",
                    org != null ? org.getName() : "unknown", result.comments().size());
            }

            // Post CVE/security vulnerability issues as separate comments
            List<ReviewIssue> cveIssues = result.issues().stream()
                .filter(issue -> issue.getCveId() != null ||
                        issue.getSource() == ReviewIssue.Source.CVE)
                .toList();

            if (!cveIssues.isEmpty()) {
                StringBuilder cveComment = new StringBuilder();
                cveComment.append("## :shield: Security Vulnerabilities Found\n\n");
                for (ReviewIssue issue : cveIssues) {
                    cveComment.append(commentFormatter.formatCveIssue(issue));
                    cveComment.append("\n---\n\n");
                }
                gitProvider.postComment(owner, repo, prNumber, cveComment.toString());
            }
        } catch (Exception e) {
            log.error("Failed to post review to PR", e);
        }
    }

    private void trackLlmUsage(Review review, ReviewEngine.ReviewResult result) {
        LlmUsage usage = new LlmUsage();
        usage.setReview(review);
        // Use user's organization for consistency with org-filtered analytics queries
        Organization org = null;
        if (review.getUser() != null && review.getUser().getOrganization() != null) {
            org = review.getUser().getOrganization();
        } else if (review.getRepository() != null) {
            org = review.getRepository().getOrganization();
        }
        usage.setOrganization(org);
        usage.setProvider(result.llmProvider());
        usage.setModel(result.llmProvider()); // Could be enhanced to track specific model
        usage.setTaskType("review");
        usage.setInputTokens(result.totalInputTokens());
        usage.setOutputTokens(result.totalOutputTokens());
        usage.setEstimatedCost(result.estimatedCost());
        usage.setSuccess(true);
        llmUsageRepository.save(usage);
    }

    /**
     * Build the review URL for the CodeLens dashboard
     */
    private String buildReviewUrl(UUID reviewId) {
        if (frontendUrl == null || frontendUrl.isEmpty()) {
            return null;
        }
        String baseUrl = frontendUrl.endsWith("/") ? frontendUrl.substring(0, frontendUrl.length() - 1) : frontendUrl;
        return baseUrl + "/reviews/" + reviewId;
    }

    /**
     * Get existing repository or create a new one (with organization if needed)
     * Fetches repository metadata (language, description, etc.) from Git provider API
     */
    @Transactional
    protected Repository getOrCreateRepository(String owner, String repoName, GitProvider provider) {
        return getOrCreateRepository(owner, repoName, provider, null);
    }

    /**
     * Get existing repository or create a new one with metadata from Git provider API.
     * If user has an organization, uses that instead of creating from GitHub org name.
     * This prevents duplicate orgs when GitHub org name differs from email domain org.
     */
    @Transactional
    protected Repository getOrCreateRepository(String owner, String repoName, GitProvider provider, User user) {
        String fullName = owner + "/" + repoName;

        // Try to find existing repository
        Optional<Repository> existingRepo = repositoryRepository.findByFullNameAndProvider(fullName, provider);
        if (existingRepo.isPresent()) {
            log.debug("Found existing repository: {}", fullName);
            return existingRepo.get();
        }

        // Determine organization: prefer user's org, fall back to creating from GitHub org name
        Organization organization;
        if (user != null && user.getOrganization() != null) {
            organization = user.getOrganization();
            log.info("Using user's organization '{}' for new repository {} (GitHub org: {})",
                organization.getName(), fullName, owner);
        } else {
            organization = getOrCreateOrganization(owner);
        }

        // Fetch repository info from Git provider API
        RepositoryInfo repoInfo = null;
        try {
            com.codelens.git.GitProvider gitProvider = gitProviderFactory.getProvider(provider);
            repoInfo = gitProvider.getRepository(owner, repoName);
            log.debug("Fetched repository info from API: language={}", repoInfo.language());
        } catch (Exception e) {
            log.warn("Could not fetch repository info from API for {}/{}: {}", owner, repoName, e.getMessage());
        }

        // Create new repository with API metadata
        Repository repository = Repository.builder()
            .fullName(fullName)
            .name(repoName)
            .owner(owner)
            .provider(provider)
            .autoReviewEnabled(true)
            .organization(organization)
            .language(repoInfo != null ? repoInfo.language() : null)
            .description(repoInfo != null ? repoInfo.description() : null)
            .defaultBranch(repoInfo != null ? repoInfo.defaultBranch() : null)
            .isPrivate(repoInfo != null ? repoInfo.isPrivate() : null)
            .build();

        repository = repositoryRepository.save(repository);
        log.info("Created new repository: {} (id={}, org={}, language={})",
            fullName, repository.getId(), organization.getName(), repository.getLanguage());

        return repository;
    }

    /**
     * Get existing organization or create a new one
     */
    @Transactional
    protected Organization getOrCreateOrganization(String name) {
        Optional<Organization> existingOrg = organizationRepository.findByName(name);
        if (existingOrg.isPresent()) {
            log.debug("Found existing organization: {}", name);
            return existingOrg.get();
        }

        // Create new organization
        Organization organization = Organization.builder()
            .name(name)
            .slug(name.toLowerCase().replaceAll("[^a-z0-9-]", "-"))
            .autoReviewEnabled(true)
            .postCommentsEnabled(true)
            .securityScanEnabled(true)
            .staticAnalysisEnabled(true)
            .build();

        organization = organizationRepository.save(organization);
        log.info("Created new organization: {} (id={})", name, organization.getId());

        return organization;
    }

    /**
     * Check if user's email domain contains the organization name.
     * Examples:
     * - user@acme.com with org "acme" -> true
     * - user@acme-corp.com with org "acme" -> true
     * - user@myacme.io with org "acme" -> true
     * - user@gmail.com with org "acme" -> false
     */
    private boolean isEmailDomainMatchingOrg(String email, String orgName) {
        if (email == null || orgName == null) {
            return false;
        }

        // Extract domain from email
        int atIndex = email.indexOf('@');
        if (atIndex == -1 || atIndex == email.length() - 1) {
            return false;
        }
        String domain = email.substring(atIndex + 1).toLowerCase();

        // Normalize org name (remove special chars, lowercase)
        String normalizedOrg = orgName.toLowerCase().replaceAll("[^a-z0-9]", "");

        // Check if domain contains the org name
        String normalizedDomain = domain.replaceAll("[^a-z0-9]", "");
        return normalizedDomain.contains(normalizedOrg);
    }

    /**
     * Get review by ID
     */
    public Optional<Review> getReview(UUID id) {
        return reviewRepository.findById(id);
    }

    /**
     * Get reviews for a user by UUID
     */
    public List<Review> getReviewsForUser(UUID userId) {
        return reviewRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    /**
     * Get reviews for a user by provider ID
     */
    public List<Review> getReviewsForProvider(String provider, String providerId) {
        Optional<User> user = userRepository.findByProviderAndProviderId(provider, providerId);
        if (user.isEmpty()) {
            return List.of();
        }
        return reviewRepository.findByUserIdOrderByCreatedAtDesc(user.get().getId());
    }

    /**
     * Get recent reviews with optional repository filter (filtered by organization)
     */
    public List<Review> getRecentReviews(int limit, String repositoryName, UUID organizationId) {
        if (organizationId != null) {
            if (repositoryName != null && !repositoryName.isEmpty()) {
                return reviewRepository.findByOrganizationAndRepositoryName(
                    organizationId, repositoryName, org.springframework.data.domain.PageRequest.of(0, limit));
            }
            return reviewRepository.findRecentReviewsByOrganization(
                organizationId, org.springframework.data.domain.PageRequest.of(0, limit));
        }
        // Fallback for users without organization (shouldn't happen in production)
        if (repositoryName != null && !repositoryName.isEmpty()) {
            return reviewRepository.findByRepositoryNameOrderByCreatedAtDesc(
                repositoryName, org.springframework.data.domain.PageRequest.of(0, limit));
        }
        return reviewRepository.findRecentReviews(org.springframework.data.domain.PageRequest.of(0, limit));
    }

    /**
     * Get recent reviews with optional repository filter (legacy - no org filter)
     * @deprecated Use getRecentReviews(int, String, UUID) instead
     */
    @Deprecated
    public List<Review> getRecentReviews(int limit, String repositoryName) {
        return getRecentReviews(limit, repositoryName, null);
    }

    /**
     * Get reviews for a user with optional repository filter
     */
    public List<Review> getReviewsForUser(UUID userId, String repositoryName) {
        if (repositoryName != null && !repositoryName.isEmpty()) {
            return reviewRepository.findByUserIdAndRepositoryName(userId, repositoryName);
        }
        return reviewRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    /**
     * Get distinct repository names filtered by organization
     */
    public List<String> getDistinctRepositoryNames(UUID organizationId) {
        if (organizationId != null) {
            return reviewRepository.findDistinctRepositoryNamesByOrganization(organizationId);
        }
        return reviewRepository.findDistinctRepositoryNames();
    }

    /**
     * Get distinct repository names (all - no org filter)
     * @deprecated Use getDistinctRepositoryNames(UUID) instead
     */
    @Deprecated
    public List<String> getDistinctRepositoryNames() {
        return getDistinctRepositoryNames(null);
    }

    /**
     * Get distinct repository names for a user
     */
    public List<String> getDistinctRepositoryNamesForUser(UUID userId) {
        return reviewRepository.findDistinctRepositoryNamesByUserId(userId);
    }

    /**
     * Get issues for a review
     */
    public List<ReviewIssue> getIssuesForReview(UUID reviewId) {
        return issueRepository.findByReviewIdOrderBySeverityAscStartLineAsc(reviewId);
    }

    /**
     * Get comments for a review
     */
    public List<ReviewComment> getCommentsForReview(UUID reviewId) {
        return commentRepository.findByReviewIdOrderByFilePathAscStartLineAsc(reviewId);
    }

    /**
     * Get optimization issues for a review
     */
    public List<ReviewIssue> getOptimizationsForReview(UUID reviewId) {
        return issueRepository.findByReviewIdAndCategory(reviewId, ReviewIssue.Category.OPTIMIZATION);
    }

    /**
     * Submit a single commit for review
     */
    @Transactional
    public Review submitCommitReview(String commitUrl, SessionUserInfo sessionUser, boolean includeOptimization) {
        return submitCommitReview(commitUrl, sessionUser, includeOptimization, null, null);
    }

    /**
     * Submit a single commit for review with ticket scope validation
     */
    @Transactional
    public Review submitCommitReview(String commitUrl, SessionUserInfo sessionUser, boolean includeOptimization,
            String ticketContent, String ticketId) {
        GitProviderFactory.ParsedCommitUrl parsed = gitProviderFactory.parseCommitUrl(commitUrl);

        // Fetch commit details from Git provider
        com.codelens.git.GitProvider gitProvider = gitProviderFactory.getProvider(parsed.provider());
        com.codelens.git.GitProvider.CommitInfo commitInfo = gitProvider.getCommit(
            parsed.owner(), parsed.repo(), parsed.commitSha());

        // Check if we already have a completed review for this exact commit
        Optional<Review> existingReview = reviewRepository.findFirstByCommitUrlAndStatusOrderByCreatedAtDesc(
            commitUrl, Review.ReviewStatus.COMPLETED);
        if (existingReview.isPresent()) {
            log.info("Review already completed for commit {}, returning existing review", commitUrl);
            return existingReview.get();
        }

        // Check if there's a review in progress for this commit
        Optional<Review> inProgressReview = reviewRepository.findFirstByCommitUrlAndStatusOrderByCreatedAtDesc(
            commitUrl, Review.ReviewStatus.IN_PROGRESS);
        if (inProgressReview.isPresent()) {
            log.info("Review already in progress for commit {}, returning existing review", commitUrl);
            return inProgressReview.get();
        }

        Review review = new Review();
        review.setCommitUrl(commitUrl);
        review.setHeadCommitSha(parsed.commitSha());
        review.setPrTitle(truncateCommitMessage(commitInfo.message(), 100));
        review.setPrDescription(commitInfo.message());
        review.setPrAuthor(commitInfo.author());
        review.setRepositoryName(parsed.owner() + "/" + parsed.repo());
        review.setStatus(Review.ReviewStatus.IN_PROGRESS);
        review.setIncludeOptimization(includeOptimization);
        review.setTicketContent(ticketContent);
        review.setTicketId(ticketId);
        review.setCreatedAt(LocalDateTime.now());

        // Find or create user from session info
        User user = null;
        if (sessionUser != null && sessionUser.providerId() != null) {
            user = findOrCreateUser(sessionUser);
            review.setUser(user);
        }

        // Get or create repository - use user's org if available to avoid duplicate orgs
        Repository repository = getOrCreateRepository(parsed.owner(), parsed.repo(), parsed.provider(), user);
        review.setRepository(repository);

        // Auto-associate user with organization only if email domain matches org name
        if (review.getUser() != null && repository.getOrganization() != null) {
            User reviewUser = review.getUser();
            if (user.getOrganization() == null) {
                Organization org = repository.getOrganization();
                if (isEmailDomainMatchingOrg(user.getEmail(), org.getName())) {
                    boolean autoApproved = membershipService.requestMembership(user, org);
                    if (autoApproved) {
                        log.info("Auto-approved user {} to organization {} (email domain match)",
                            user.getEmail(), org.getName());
                    }
                }
            }
        }

        review = reviewRepository.save(review);

        // Trigger async review AFTER transaction commits
        UUID finalReviewId = review.getId();
        GitProvider finalProvider = parsed.provider();
        String finalOwner = parsed.owner();
        String finalRepo = parsed.repo();
        String finalCommitSha = parsed.commitSha();

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                java.util.concurrent.CompletableFuture<Void> future = reviewAsyncService.executeCommitReviewAsync(
                    finalReviewId, finalProvider, finalOwner, finalRepo, finalCommitSha);
                cancellationService.registerRunningReview(finalReviewId, future);
            }
        });

        return review;
    }

    private String truncateCommitMessage(String message, int maxLength) {
        if (message == null) return "Commit review";
        String firstLine = message.split("\n")[0];
        if (firstLine.length() <= maxLength) return firstLine;
        return firstLine.substring(0, maxLength - 3) + "...";
    }

    @Override
    @Transactional
    public void executeCommitReview(UUID reviewId, GitProvider provider, String owner, String repo, String commitSha) {
        log.info("Executing commit review {} for {}/{} commit {}", reviewId, owner, repo, commitSha);

        // Initialize progress tracking state for this review
        progressService.clearProgressState(reviewId);

        Review review = reviewRepository.findById(reviewId)
            .orElseThrow(() -> new IllegalArgumentException("Review not found: " + reviewId));

        // Get organization ID for custom rules
        UUID organizationId = review.getRepository() != null && review.getRepository().getOrganization() != null
            ? review.getRepository().getOrganization().getId()
            : null;

        // Execute the commit review with progress tracking
        ReviewEngine.CommitReviewRequest request = new ReviewEngine.CommitReviewRequest(
            provider, owner, repo, commitSha, organizationId,
            review.getTicketContent(), review.getTicketId());
        ReviewEngine.ReviewResult result;
        try {
            result = reviewEngine.executeCommitReview(request, progress -> {
                progressService.updateProgress(reviewId, progress);
            });
        } catch (com.codelens.core.ReviewCancelledException e) {
            log.info("Commit review {} was cancelled", reviewId);
            progressService.clearProgressState(reviewId);
            return;
        }

        // Save results
        review.setSummary(result.summary());
        review.setFilesChanged(result.filesReviewed());
        review.setLinesAdded(result.linesAdded());
        review.setLinesDeleted(result.linesRemoved());
        review.setIssuesFound(result.issues().size());
        review.setCriticalIssues((int) result.issues().stream()
            .filter(i -> i.getSeverity() == ReviewIssue.Severity.CRITICAL).count());
        review.setHighIssues((int) result.issues().stream()
            .filter(i -> i.getSeverity() == ReviewIssue.Severity.HIGH).count());
        review.setMediumIssues((int) result.issues().stream()
            .filter(i -> i.getSeverity() == ReviewIssue.Severity.MEDIUM).count());
        review.setLowIssues((int) result.issues().stream()
            .filter(i -> i.getSeverity() == ReviewIssue.Severity.LOW).count());
        review.setInputTokens(result.totalInputTokens());
        review.setOutputTokens(result.totalOutputTokens());
        review.setTicketScopeResult(result.ticketScopeResult());
        review.setTicketScopeAligned(result.ticketScopeAligned());
        review.setStatus(Review.ReviewStatus.COMPLETED);
        review.setCompletedAt(LocalDateTime.now());
        reviewRepository.save(review);

        // Save issues
        for (ReviewIssue issue : result.issues()) {
            issue.setReview(review);
            issueRepository.save(issue);
        }

        // Save comments
        for (ReviewComment comment : result.comments()) {
            comment.setReview(review);
            commentRepository.save(comment);
        }

        // Set LLM provider used
        review.setLlmProvider(result.llmProvider());
        review.setEstimatedCost(result.estimatedCost());

        // Store the diff for later viewing
        review.setRawDiff(result.rawDiff());
        reviewRepository.save(review);

        // Save parsed file diffs
        if (result.parsedDiffs() != null) {
            for (DiffParser.FileDiff fileDiff : result.parsedDiffs()) {
                int additions = 0;
                int deletions = 0;
                for (DiffParser.Hunk hunk : fileDiff.hunks()) {
                    for (DiffParser.DiffLine line : hunk.lines()) {
                        if (line.type() == DiffParser.DiffLine.Type.ADDITION) additions++;
                        else if (line.type() == DiffParser.DiffLine.Type.DELETION) deletions++;
                    }
                }

                ReviewFileDiff.FileStatus status = ReviewFileDiff.FileStatus.MODIFIED;
                if (fileDiff.oldPath() == null || fileDiff.oldPath().equals("/dev/null")) {
                    status = ReviewFileDiff.FileStatus.ADDED;
                } else if (fileDiff.newPath() == null || fileDiff.newPath().equals("/dev/null")) {
                    status = ReviewFileDiff.FileStatus.DELETED;
                } else if (!fileDiff.oldPath().equals(fileDiff.newPath())) {
                    status = ReviewFileDiff.FileStatus.RENAMED;
                }

                StringBuilder patchBuilder = new StringBuilder();
                for (DiffParser.Hunk hunk : fileDiff.hunks()) {
                    patchBuilder.append("@@ -").append(hunk.oldStart()).append(",").append(hunk.oldCount())
                        .append(" +").append(hunk.newStart()).append(",").append(hunk.newCount()).append(" @@");
                    if (hunk.context() != null && !hunk.context().isEmpty()) {
                        patchBuilder.append(" ").append(hunk.context());
                    }
                    patchBuilder.append("\n");
                    for (DiffParser.DiffLine line : hunk.lines()) {
                        switch (line.type()) {
                            case ADDITION -> patchBuilder.append("+");
                            case DELETION -> patchBuilder.append("-");
                            case CONTEXT -> patchBuilder.append(" ");
                        }
                        patchBuilder.append(line.content()).append("\n");
                    }
                }

                ReviewFileDiff reviewFileDiff = ReviewFileDiff.builder()
                    .review(review)
                    .filePath(fileDiff.getPath())
                    .oldPath(fileDiff.oldPath())
                    .status(status)
                    .additions(additions)
                    .deletions(deletions)
                    .patch(patchBuilder.toString())
                    .build();
                fileDiffRepository.save(reviewFileDiff);
            }
        }

        // Track LLM usage
        trackLlmUsage(review, result);

        log.info("Commit review {} completed with {} issues", reviewId, result.issues().size());

        // Clean up progress tracking state
        progressService.clearProgressState(reviewId);

        // Send notifications
        notificationService.notifyReviewCompleted(review);

        // Run optimization analysis if requested
        if (Boolean.TRUE.equals(review.getIncludeOptimization()) && optimizationService != null) {
            log.info("Starting optimization analysis for commit review {} (includeOptimization=true)", reviewId);
            optimizationService.analyzeOptimizationsAsync(reviewId);
        }
    }
}
