package com.codelens.git.gitlab;

import com.codelens.config.EncryptionService;
import com.codelens.core.DiffParser;
import com.codelens.git.GitProvider;
import lombok.extern.slf4j.Slf4j;
import org.gitlab4j.api.GitLabApi;
import org.gitlab4j.api.GitLabApiException;
import org.gitlab4j.api.models.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
public class GitLabService implements GitProvider {

    private final DiffParser diffParser;
    private final EncryptionService encryptionService;

    @Value("${codelens.git.gitlab.url:https://gitlab.com}")
    private String gitLabUrl;

    @Value("${codelens.git.gitlab.token:}")
    private String token;

    private GitLabApi gitLabApi;
    private boolean initialized = false;

    public GitLabService(DiffParser diffParser, EncryptionService encryptionService) {
        this.diffParser = diffParser;
        this.encryptionService = encryptionService;
    }

    @PostConstruct
    public void init() {
        try {
            if (token != null && !token.isEmpty()) {
                gitLabApi = new GitLabApi(gitLabUrl, token);
                initialized = true;
                log.info("GitLab service initialized for {}", gitLabUrl);
            } else {
                log.warn("No GitLab token configured, GitLab integration disabled");
            }
        } catch (Exception e) {
            log.error("Failed to initialize GitLab service", e);
        }
    }

    @Override
    public String getName() {
        return "gitlab";
    }

    @Override
    public PullRequestInfo getPullRequest(String owner, String repo, int prNumber) {
        try {
            String projectPath = owner + "/" + repo;
            MergeRequest mr = gitLabApi.getMergeRequestApi()
                .getMergeRequest(projectPath, (long) prNumber);

            return new PullRequestInfo(
                mr.getIid().intValue(),
                mr.getTitle(),
                mr.getDescription(),
                mr.getWebUrl(),
                mr.getAuthor().getUsername(),
                mr.getTargetBranch(),
                mr.getSourceBranch(),
                mr.getSha(),
                mr.getDiffRefs().getBaseSha(),
                mr.getState(),
                0, // GitLab API needs separate call for stats
                0,
                0
            );
        } catch (GitLabApiException e) {
            log.error("Failed to get MR {}/{} !{}", owner, repo, prNumber, e);
            throw new RuntimeException("Failed to get merge request", e);
        }
    }

    @Override
    public String getDiff(String owner, String repo, int prNumber) {
        try {
            String projectPath = owner + "/" + repo;
            List<Diff> diffs = gitLabApi.getMergeRequestApi()
                .getMergeRequestChanges(projectPath, (long) prNumber)
                .getChanges();

            StringBuilder diffBuilder = new StringBuilder();
            for (Diff diff : diffs) {
                diffBuilder.append("diff --git a/").append(diff.getOldPath())
                    .append(" b/").append(diff.getNewPath()).append("\n");
                diffBuilder.append(diff.getDiff()).append("\n");
            }
            return diffBuilder.toString();
        } catch (GitLabApiException e) {
            log.error("Failed to get diff for {}/{} !{}", owner, repo, prNumber, e);
            throw new RuntimeException("Failed to get diff", e);
        }
    }

    @Override
    public String getFileContent(String owner, String repo, String path, String commitSha) {
        try {
            String projectPath = owner + "/" + repo;
            RepositoryFile file = gitLabApi.getRepositoryFileApi()
                .getFile(projectPath, path, commitSha);
            return new String(java.util.Base64.getDecoder().decode(file.getContent()));
        } catch (GitLabApiException e) {
            if (e.getHttpStatus() == 404) {
                // File not found is expected when checking for optional files (e.g., ESLint configs)
                log.debug("File not found: {}/{}/{} at {}", owner, repo, path, commitSha);
                throw new RuntimeException("File not found: " + path, e);
            }
            log.error("Failed to get file content for {}/{}/{} at {}", owner, repo, path, commitSha, e);
            throw new RuntimeException("Failed to get file content", e);
        }
    }

    @Override
    public List<ChangedFile> getChangedFiles(String owner, String repo, int prNumber) {
        try {
            String projectPath = owner + "/" + repo;
            List<Diff> diffs = gitLabApi.getMergeRequestApi()
                .getMergeRequestChanges(projectPath, (long) prNumber)
                .getChanges();

            return diffs.stream()
                .map(diff -> new ChangedFile(
                    diff.getNewPath(),
                    diff.getNewFile() ? "added" : (diff.getDeletedFile() ? "deleted" : "modified"),
                    0, // GitLab doesn't provide line counts in diff
                    0,
                    diff.getDiff()
                ))
                .collect(Collectors.toList());
        } catch (GitLabApiException e) {
            log.error("Failed to get changed files for {}/{} !{}", owner, repo, prNumber, e);
            throw new RuntimeException("Failed to get changed files", e);
        }
    }

    @Override
    public CommitInfo getCommit(String owner, String repo, String commitSha) {
        try {
            String projectPath = owner + "/" + repo;
            org.gitlab4j.api.models.Commit commit = gitLabApi.getCommitsApi()
                .getCommit(projectPath, commitSha);

            return new CommitInfo(
                commit.getId(),
                commit.getMessage(),
                commit.getAuthorName(),
                commit.getAuthorEmail(),
                commit.getWebUrl(),
                commit.getStats() != null ? commit.getStats().getAdditions() : 0,
                commit.getStats() != null ? commit.getStats().getDeletions() : 0,
                0  // GitLab doesn't provide file count in commit stats
            );
        } catch (GitLabApiException e) {
            log.error("Failed to get commit {}/{} {}", owner, repo, commitSha, e);
            throw new RuntimeException("Failed to get commit", e);
        }
    }

    @Override
    public String getCommitDiff(String owner, String repo, String commitSha) {
        try {
            String projectPath = owner + "/" + repo;
            List<Diff> diffs = gitLabApi.getCommitsApi().getDiff(projectPath, commitSha);

            StringBuilder diff = new StringBuilder();
            for (Diff d : diffs) {
                diff.append("diff --git a/").append(d.getOldPath())
                    .append(" b/").append(d.getNewPath()).append("\n");

                if (d.getDiff() != null) {
                    if (d.getNewFile()) {
                        diff.append("new file mode 100644\n");
                        diff.append("--- /dev/null\n");
                        diff.append("+++ b/").append(d.getNewPath()).append("\n");
                    } else if (d.getDeletedFile()) {
                        diff.append("deleted file mode 100644\n");
                        diff.append("--- a/").append(d.getOldPath()).append("\n");
                        diff.append("+++ /dev/null\n");
                    } else {
                        diff.append("--- a/").append(d.getOldPath()).append("\n");
                        diff.append("+++ b/").append(d.getNewPath()).append("\n");
                    }
                    diff.append(d.getDiff()).append("\n");
                }
            }
            return diff.toString();
        } catch (GitLabApiException e) {
            log.error("Failed to get commit diff for {}/{} {}", owner, repo, commitSha, e);
            throw new RuntimeException("Failed to get commit diff", e);
        }
    }

    @Override
    public List<ChangedFile> getCommitChangedFiles(String owner, String repo, String commitSha) {
        try {
            String projectPath = owner + "/" + repo;
            List<Diff> diffs = gitLabApi.getCommitsApi().getDiff(projectPath, commitSha);

            return diffs.stream()
                .map(diff -> new ChangedFile(
                    diff.getNewPath(),
                    diff.getNewFile() ? "added" : (diff.getDeletedFile() ? "deleted" : "modified"),
                    0,
                    0,
                    diff.getDiff()
                ))
                .collect(Collectors.toList());
        } catch (GitLabApiException e) {
            log.error("Failed to get commit changed files for {}/{} {}", owner, repo, commitSha, e);
            throw new RuntimeException("Failed to get commit changed files", e);
        }
    }

    @Override
    public void postComment(String owner, String repo, int prNumber, String comment) {
        try {
            String projectPath = owner + "/" + repo;
            gitLabApi.getNotesApi().createMergeRequestNote(
                projectPath, (long) prNumber, comment);
            log.info("Posted comment on {}/{} !{}", owner, repo, prNumber);
        } catch (GitLabApiException e) {
            log.error("Failed to post comment on {}/{} !{}", owner, repo, prNumber, e);
            throw new RuntimeException("Failed to post comment", e);
        }
    }

    @Override
    public void postInlineComment(String owner, String repo, int prNumber, InlineComment comment) {
        try {
            String projectPath = owner + "/" + repo;

            // Fetch the MR changes to validate the line is in the diff
            MergeRequest mrWithChanges = gitLabApi.getMergeRequestApi()
                .getMergeRequestChanges(projectPath, (long) prNumber);

            // Find the file's diff
            String fileDiffContent = null;
            for (Diff diff : mrWithChanges.getChanges()) {
                if (diff.getNewPath().equals(comment.filePath()) ||
                    diff.getOldPath().equals(comment.filePath())) {
                    fileDiffContent = diff.getDiff();
                    break;
                }
            }

            if (fileDiffContent == null) {
                log.warn("File {} not found in MR diff, skipping inline comment at line {}",
                    comment.filePath(), comment.line());
                return;
            }

            // Parse the diff to check if the line is in the diff
            String diffString = "diff --git a/" + comment.filePath() + " b/" + comment.filePath() + "\n" + fileDiffContent;
            List<DiffParser.FileDiff> fileDiffs = diffParser.parse(diffString);

            if (fileDiffs.isEmpty()) {
                log.warn("Could not parse diff for {}, skipping inline comment at line {}",
                    comment.filePath(), comment.line());
                return;
            }

            DiffParser.FileDiff fileDiff = fileDiffs.get(0);
            if (!diffParser.isLineInDiff(fileDiff, comment.line())) {
                log.warn("Line {} is not in the diff for {}, skipping inline comment. " +
                    "GitLab only allows comments on changed lines.",
                    comment.line(), comment.filePath());
                return;
            }

            // Get the MR again for diff refs (or use the one we already have)
            Position position = new Position()
                .withBaseSha(mrWithChanges.getDiffRefs().getBaseSha())
                .withHeadSha(mrWithChanges.getDiffRefs().getHeadSha())
                .withStartSha(mrWithChanges.getDiffRefs().getStartSha())
                .withNewPath(comment.filePath())
                .withOldPath(comment.filePath())
                .withNewLine(comment.line())
                .withPositionType(Position.PositionType.TEXT);

            gitLabApi.getDiscussionsApi().createMergeRequestDiscussion(
                projectPath, (long) prNumber, comment.body(), null, null, position);

            log.info("Posted inline comment on {}/{} !{} at {}:{}",
                owner, repo, prNumber, comment.filePath(), comment.line());
        } catch (GitLabApiException e) {
            log.error("Failed to post inline comment on {}/{} !{}", owner, repo, prNumber, e);
            throw new RuntimeException("Failed to post inline comment", e);
        }
    }

    @Override
    public RepositoryInfo getRepository(String owner, String repo) {
        try {
            String projectPath = owner + "/" + repo;
            Project project = gitLabApi.getProjectApi().getProject(projectPath);

            // GitLab doesn't have a direct "language" field like GitHub
            // We'll get the most common language from the languages API if available
            String primaryLanguage = null;
            try {
                var languages = gitLabApi.getProjectApi().getProjectLanguages(projectPath);
                if (languages != null && !languages.isEmpty()) {
                    // Get the language with the highest percentage
                    primaryLanguage = languages.entrySet().stream()
                        .max((a, b) -> Float.compare(a.getValue(), b.getValue()))
                        .map(java.util.Map.Entry::getKey)
                        .orElse(null);
                }
            } catch (GitLabApiException e) {
                log.debug("Could not fetch languages for {}/{}", owner, repo);
            }

            return new RepositoryInfo(
                project.getPathWithNamespace(),
                project.getName(),
                owner,
                project.getDescription(),
                primaryLanguage,
                project.getDefaultBranch(),
                project.getVisibility() == org.gitlab4j.api.models.Visibility.PRIVATE,
                project.getWebUrl()
            );
        } catch (GitLabApiException e) {
            log.error("Failed to get repository {}/{}", owner, repo, e);
            throw new RuntimeException("Failed to get repository", e);
        }
    }

    public boolean isInitialized() {
        return initialized;
    }

    /**
     * Create a GitLab API client using an organization's encrypted token.
     * This allows per-organization authentication.
     *
     * @param encryptedToken The encrypted GitLab token from the organization
     * @param customGitLabUrl Optional custom GitLab URL (for self-hosted instances)
     * @return A GitLabApi client authenticated with the decrypted token
     */
    public GitLabApi createClientWithToken(String encryptedToken, String customGitLabUrl) {
        if (encryptedToken == null || encryptedToken.isEmpty()) {
            throw new IllegalArgumentException("Token cannot be null or empty");
        }

        String decryptedToken = encryptionService.decrypt(encryptedToken);
        String url = (customGitLabUrl != null && !customGitLabUrl.isEmpty()) ? customGitLabUrl : gitLabUrl;

        return new GitLabApi(url, decryptedToken);
    }
}
