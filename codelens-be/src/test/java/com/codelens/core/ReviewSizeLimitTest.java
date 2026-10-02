package com.codelens.core;

import com.codelens.analysis.CombinedAnalysisService;
import com.codelens.analysis.LintConfigBundle;
import com.codelens.analysis.LintConfigService;
import com.codelens.git.GitProvider;
import com.codelens.git.GitProviderFactory;
import com.codelens.intelligence.CodeIntelligenceService;
import com.codelens.llm.LlmRouter;
import com.codelens.model.entity.Repository;
import com.codelens.service.LearningService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ResourceLoader;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * An over-limit change must be rejected before any expensive work. The PR path used to check
 * the size only after the code-intelligence graph update, so smeassist #5161 (15k lines) waited
 * almost 9 minutes just to be told it was too large.
 */
class ReviewSizeLimitTest {

    private static final UUID REPO_ID = UUID.randomUUID();

    private GitProvider gitProvider;
    private LintConfigService lintConfigService;
    private LearningService learningService;
    private CodeIntelligenceService codeIntelligenceService;
    private ReviewEngine engine;

    @BeforeEach
    void setUp() {
        gitProvider = mock(GitProvider.class);
        GitProviderFactory gitProviderFactory = mock(GitProviderFactory.class);
        when(gitProviderFactory.getProvider(any(Repository.GitProvider.class))).thenReturn(gitProvider);
        lintConfigService = mock(LintConfigService.class);
        when(lintConfigService.fetchAll(any(), any(), any(), any())).thenReturn(LintConfigBundle.empty());
        learningService = mock(LearningService.class);
        when(learningService.buildReviewContext(any())).thenReturn(LearningService.RepoLearningContext.EMPTY);
        codeIntelligenceService = mock(CodeIntelligenceService.class);

        engine = new ReviewEngine(gitProviderFactory, mock(LlmRouter.class), new DiffParser(),
            mock(CommentFormatter.class), mock(IgnoreCommentParser.class), mock(ResourceLoader.class),
            mock(CombinedAnalysisService.class), mock(LanguageDetector.class), new SmartContextExtractor(),
            mock(SecretRedactor.class), learningService, lintConfigService, mock(VerificationService.class));
        ReflectionTestUtils.setField(engine, "maxDiffLines", 10);
        ReflectionTestUtils.setField(engine, "intelligenceEnabled", true);
        ReflectionTestUtils.setField(engine, "codeIntelligenceService", codeIntelligenceService);
    }

    private static List<GitProvider.ChangedFile> elevenLinesOfJava() {
        return List.of(new GitProvider.ChangedFile("src/main/java/A.java", "added", 11, 0, "@@ -0,0 +1,11 @@"));
    }

    @Test
    void oversizedPrIsRejectedBeforeAnyOtherWork() {
        when(gitProvider.getPullRequest("acme", "widgets", 7)).thenReturn(new GitProvider.PullRequestInfo(
            7, "t", "", "url", "dev", "main", "feature", "head", "base", "open", 11, 0, 1));
        when(gitProvider.getChangedFiles("acme", "widgets", 7)).thenReturn(elevenLinesOfJava());

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> engine.executeReview(
            new ReviewEngine.ReviewRequest(Repository.GitProvider.GITHUB, "acme", "widgets", 7,
                null, REPO_ID, null, null)));

        assertTrue(e.getMessage().startsWith("PR too large: 11 lines"), e.getMessage());
        verifyNoInteractions(codeIntelligenceService, lintConfigService, learningService);
    }

    @Test
    void oversizedCommitIsRejectedBeforeAnyOtherWork() {
        when(gitProvider.getCommit("acme", "widgets", "abc123")).thenReturn(new GitProvider.CommitInfo(
            "abc123", "msg", "dev", "dev@acme.com", "url", 11, 0, 1));
        when(gitProvider.getCommitChangedFiles("acme", "widgets", "abc123")).thenReturn(elevenLinesOfJava());

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> engine.executeCommitReview(
            new ReviewEngine.CommitReviewRequest(Repository.GitProvider.GITHUB, "acme", "widgets", "abc123",
                null, REPO_ID, null, null)));

        assertTrue(e.getMessage().startsWith("Commit too large: 11 lines"), e.getMessage());
        verifyNoInteractions(codeIntelligenceService, lintConfigService, learningService);
    }
}
