package com.codelens.git;

import java.util.List;

/**
 * Interface for Git providers (GitHub, GitLab)
 */
public interface GitProvider {

    /**
     * Get the provider name
     */
    String getName();

    /**
     * Fetch pull request details
     */
    PullRequestInfo getPullRequest(String owner, String repo, int prNumber);

    /**
     * Get the diff for a pull request
     */
    String getDiff(String owner, String repo, int prNumber);

    /**
     * Get file content at a specific commit
     */
    String getFileContent(String owner, String repo, String path, String commitSha);

    /**
     * Get list of changed files in a PR
     */
    List<ChangedFile> getChangedFiles(String owner, String repo, int prNumber);

    /**
     * Get commit information
     */
    CommitInfo getCommit(String owner, String repo, String commitSha);

    /**
     * Get the diff for a single commit
     */
    String getCommitDiff(String owner, String repo, String commitSha);

    /**
     * Get list of changed files in a commit
     */
    List<ChangedFile> getCommitChangedFiles(String owner, String repo, String commitSha);

    /**
     * Post a comment on a PR
     */
    void postComment(String owner, String repo, int prNumber, String comment);

    /**
     * Post an inline comment on a specific line
     */
    void postInlineComment(String owner, String repo, int prNumber, InlineComment comment);

    /**
     * Get repository information including language
     */
    RepositoryInfo getRepository(String owner, String repo);

    /**
     * Commit information
     */
    record CommitInfo(
        String sha,
        String message,
        String author,
        String authorEmail,
        String url,
        int additions,
        int deletions,
        int changedFiles
    ) {}

    /**
     * Pull request information
     */
    record PullRequestInfo(
        int number,
        String title,
        String description,
        String url,
        String author,
        String baseBranch,
        String headBranch,
        String headCommitSha,
        String baseCommitSha,
        String state,
        int additions,
        int deletions,
        int changedFiles
    ) {}

    /**
     * Repository information
     */
    record RepositoryInfo(
        String fullName,
        String name,
        String owner,
        String description,
        String language,
        String defaultBranch,
        boolean isPrivate,
        String url
    ) {}

    /**
     * Changed file in a PR
     */
    record ChangedFile(
        String filename,
        String status, // added, modified, deleted, renamed
        int additions,
        int deletions,
        String patch
    ) {}

    /**
     * Inline comment for code review
     */
    record InlineComment(
        String filePath,
        int line,
        String body,
        String commitSha
    ) {}
}
