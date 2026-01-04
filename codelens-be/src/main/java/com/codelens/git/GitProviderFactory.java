package com.codelens.git;

import com.codelens.git.github.GitHubService;
import com.codelens.git.gitlab.GitLabService;
import com.codelens.model.entity.Repository.GitProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class GitProviderFactory {

    private final GitHubService gitHubService;
    private final GitLabService gitLabService;

    @Value("${codelens.git.default-provider:github}")
    private String defaultProvider;

    public GitProviderFactory(GitHubService gitHubService, GitLabService gitLabService) {
        this.gitHubService = gitHubService;
        this.gitLabService = gitLabService;
    }

    /**
     * Get the git provider service for a given provider type
     */
    public com.codelens.git.GitProvider getProvider(GitProvider provider) {
        return switch (provider) {
            case GITHUB -> gitHubService;
            case GITLAB -> gitLabService;
        };
    }

    /**
     * Get the git provider service by name
     */
    public com.codelens.git.GitProvider getProvider(String providerName) {
        return switch (providerName.toLowerCase()) {
            case "github" -> gitHubService;
            case "gitlab" -> gitLabService;
            default -> throw new IllegalArgumentException("Unknown git provider: " + providerName);
        };
    }

    /**
     * Get the default provider
     */
    public com.codelens.git.GitProvider getDefaultProvider() {
        return getProvider(defaultProvider);
    }

    /**
     * Parse a PR URL to determine the provider and extract repo info
     */
    public ParsedPrUrl parsePrUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("PR URL cannot be null or empty");
        }

        if (url.contains("github.com")) {
            // https://github.com/owner/repo/pull/123
            String[] parts = url.replace("https://github.com/", "").split("/");
            if (parts.length >= 4 && parts[2].equals("pull")) {
                try {
                    int prNumber = Integer.parseInt(parts[3].split("[?#]")[0]); // Handle query params/fragments
                    return new ParsedPrUrl(
                        GitProvider.GITHUB,
                        parts[0],
                        parts[1],
                        prNumber
                    );
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Invalid PR number in URL: " + url, e);
                }
            }
        } else if (url.contains("gitlab.com") || url.contains("gitlab")) {
            // https://gitlab.com/owner/repo/-/merge_requests/123
            String path = url.replaceAll("https?://[^/]+/", "");
            String[] parts = path.split("/-/merge_requests/");
            if (parts.length == 2) {
                String[] ownerRepo = parts[0].split("/");
                if (ownerRepo.length < 2) {
                    throw new IllegalArgumentException("Invalid GitLab URL format - missing owner/repo: " + url);
                }
                try {
                    int mrNumber = Integer.parseInt(parts[1].split("[?#]")[0]); // Handle query params/fragments
                    return new ParsedPrUrl(
                        GitProvider.GITLAB,
                        ownerRepo[0],
                        ownerRepo[1],
                        mrNumber
                    );
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Invalid MR number in URL: " + url, e);
                }
            }
        }
        throw new IllegalArgumentException("Unable to parse PR URL: " + url);
    }

    public record ParsedPrUrl(
        GitProvider provider,
        String owner,
        String repo,
        int prNumber
    ) {}

    /**
     * Parse a commit URL to determine the provider and extract repo info
     * Supports formats:
     * - GitHub: https://github.com/owner/repo/commit/sha
     * - GitLab: https://gitlab.com/owner/repo/-/commit/sha
     */
    public ParsedCommitUrl parseCommitUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("Commit URL cannot be null or empty");
        }

        if (url.contains("github.com")) {
            // https://github.com/owner/repo/commit/abc123...
            String[] parts = url.replace("https://github.com/", "").split("/");
            if (parts.length >= 4 && parts[2].equals("commit")) {
                String commitSha = parts[3].split("[?#]")[0]; // Handle query params/fragments
                return new ParsedCommitUrl(
                    GitProvider.GITHUB,
                    parts[0],
                    parts[1],
                    commitSha
                );
            }
        } else if (url.contains("gitlab.com") || url.contains("gitlab")) {
            // https://gitlab.com/owner/repo/-/commit/abc123...
            String path = url.replaceAll("https?://[^/]+/", "");
            String[] parts = path.split("/-/commit/");
            if (parts.length == 2) {
                String[] ownerRepo = parts[0].split("/");
                if (ownerRepo.length < 2) {
                    throw new IllegalArgumentException("Invalid GitLab URL format - missing owner/repo: " + url);
                }
                String commitSha = parts[1].split("[?#]")[0]; // Handle query params/fragments
                return new ParsedCommitUrl(
                    GitProvider.GITLAB,
                    ownerRepo[0],
                    ownerRepo[1],
                    commitSha
                );
            }
        }
        throw new IllegalArgumentException("Unable to parse commit URL: " + url);
    }

    public record ParsedCommitUrl(
        GitProvider provider,
        String owner,
        String repo,
        String commitSha
    ) {}
}
