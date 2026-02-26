package com.codelens.git;

import com.codelens.git.bitbucket.BitbucketService;
import com.codelens.git.github.GitHubService;
import com.codelens.git.gitlab.GitLabService;
import com.codelens.model.entity.Repository.GitProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class GitProviderFactoryTest {

    @Mock private GitHubService gitHubService;
    @Mock private GitLabService gitLabService;
    @Mock private BitbucketService bitbucketService;

    private GitProviderFactory factory;

    @BeforeEach
    void setUp() {
        factory = new GitProviderFactory(gitHubService, gitLabService, bitbucketService);
    }

    // ==================== Provider Routing ====================

    @Nested
    class GetProviderByEnum {
        @Test
        void returnsGitHubService() {
            assertSame(gitHubService, factory.getProvider(GitProvider.GITHUB));
        }

        @Test
        void returnsGitLabService() {
            assertSame(gitLabService, factory.getProvider(GitProvider.GITLAB));
        }

        @Test
        void returnsBitbucketService() {
            assertSame(bitbucketService, factory.getProvider(GitProvider.BITBUCKET));
        }
    }

    @Nested
    class GetProviderByName {
        @Test
        void returnsGitHubByName() {
            assertSame(gitHubService, factory.getProvider("github"));
            assertSame(gitHubService, factory.getProvider("GitHub"));
        }

        @Test
        void returnsGitLabByName() {
            assertSame(gitLabService, factory.getProvider("gitlab"));
            assertSame(gitLabService, factory.getProvider("GitLab"));
        }

        @Test
        void returnsBitbucketByName() {
            assertSame(bitbucketService, factory.getProvider("bitbucket"));
            assertSame(bitbucketService, factory.getProvider("Bitbucket"));
        }

        @Test
        void throwsForUnknownProvider() {
            assertThrows(IllegalArgumentException.class, () -> factory.getProvider("unknown"));
        }
    }

    // ==================== PR URL Parsing ====================

    @Nested
    class ParsePrUrl {
        @Test
        void parsesGitHubPrUrl() {
            var result = factory.parsePrUrl("https://github.com/owner/repo/pull/42");
            assertEquals(GitProvider.GITHUB, result.provider());
            assertEquals("owner", result.owner());
            assertEquals("repo", result.repo());
            assertEquals(42, result.prNumber());
        }

        @Test
        void parsesGitLabMrUrl() {
            var result = factory.parsePrUrl("https://gitlab.com/owner/repo/-/merge_requests/7");
            assertEquals(GitProvider.GITLAB, result.provider());
            assertEquals("owner", result.owner());
            assertEquals("repo", result.repo());
            assertEquals(7, result.prNumber());
        }

        @Test
        void parsesBitbucketPrUrl() {
            var result = factory.parsePrUrl("https://bitbucket.org/workspace/repo/pull-requests/123");
            assertEquals(GitProvider.BITBUCKET, result.provider());
            assertEquals("workspace", result.owner());
            assertEquals("repo", result.repo());
            assertEquals(123, result.prNumber());
        }

        @Test
        void handlesBitbucketUrlWithQueryParams() {
            var result = factory.parsePrUrl("https://bitbucket.org/ws/repo/pull-requests/5?tab=diff");
            assertEquals(GitProvider.BITBUCKET, result.provider());
            assertEquals(5, result.prNumber());
        }

        @Test
        void handlesBitbucketUrlWithFragment() {
            var result = factory.parsePrUrl("https://bitbucket.org/ws/repo/pull-requests/10#comment-1");
            assertEquals(GitProvider.BITBUCKET, result.provider());
            assertEquals(10, result.prNumber());
        }

        @Test
        void throwsForNullUrl() {
            assertThrows(IllegalArgumentException.class, () -> factory.parsePrUrl(null));
        }

        @Test
        void throwsForEmptyUrl() {
            assertThrows(IllegalArgumentException.class, () -> factory.parsePrUrl(""));
        }

        @Test
        void throwsForUnrecognizedUrl() {
            assertThrows(IllegalArgumentException.class, () -> factory.parsePrUrl("https://example.com/pr/1"));
        }
    }

    // ==================== Commit URL Parsing ====================

    @Nested
    class ParseCommitUrl {
        @Test
        void parsesGitHubCommitUrl() {
            var result = factory.parseCommitUrl("https://github.com/owner/repo/commit/abc123");
            assertEquals(GitProvider.GITHUB, result.provider());
            assertEquals("owner", result.owner());
            assertEquals("repo", result.repo());
            assertEquals("abc123", result.commitSha());
        }

        @Test
        void parsesGitLabCommitUrl() {
            var result = factory.parseCommitUrl("https://gitlab.com/owner/repo/-/commit/def456");
            assertEquals(GitProvider.GITLAB, result.provider());
            assertEquals("owner", result.owner());
            assertEquals("repo", result.repo());
            assertEquals("def456", result.commitSha());
        }

        @Test
        void parsesBitbucketCommitUrl() {
            var result = factory.parseCommitUrl("https://bitbucket.org/workspace/repo/commits/abc123def");
            assertEquals(GitProvider.BITBUCKET, result.provider());
            assertEquals("workspace", result.owner());
            assertEquals("repo", result.repo());
            assertEquals("abc123def", result.commitSha());
        }

        @Test
        void handlesBitbucketCommitUrlWithQueryParams() {
            var result = factory.parseCommitUrl("https://bitbucket.org/ws/repo/commits/sha123?at=main");
            assertEquals(GitProvider.BITBUCKET, result.provider());
            assertEquals("sha123", result.commitSha());
        }

        @Test
        void throwsForNullUrl() {
            assertThrows(IllegalArgumentException.class, () -> factory.parseCommitUrl(null));
        }

        @Test
        void throwsForEmptyUrl() {
            assertThrows(IllegalArgumentException.class, () -> factory.parseCommitUrl(""));
        }

        @Test
        void throwsForUnrecognizedUrl() {
            assertThrows(IllegalArgumentException.class, () -> factory.parseCommitUrl("https://example.com/commit/abc"));
        }
    }
}
