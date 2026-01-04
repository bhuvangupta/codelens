package com.codelens.git.github;

import com.codelens.config.EncryptionService;
import com.codelens.core.DiffParser;
import com.codelens.git.GitProvider;
import lombok.extern.slf4j.Slf4j;
import org.kohsuke.github.*;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
public class GitHubService implements GitProvider {

    private final DiffParser diffParser;
    private final EncryptionService encryptionService;

    @Value("${GITHUB_TOKEN:}")
    private String githubToken;

    private GitHub gitHub;
    private boolean initialized = false;

    public GitHubService(DiffParser diffParser, EncryptionService encryptionService) {
        this.diffParser = diffParser;
        this.encryptionService = encryptionService;
    }

    @PostConstruct
    public void init() {
        try {
            // Check connectivity to GitHub first
            if (!checkGitHubConnectivity()) {
                log.error("Cannot connect to GitHub API. Check network, VPN, or proxy settings.");
                return;
            }

            // For now, use token-based auth or anonymous
            // Full GitHub App auth would be implemented for production
            if (githubToken != null && !githubToken.isEmpty()) {
                gitHub = new GitHubBuilder()
                    .withOAuthToken(githubToken)
                    .withConnector(new org.kohsuke.github.extras.okhttp3.OkHttpGitHubConnector(
                        new okhttp3.OkHttpClient.Builder()
                            .connectTimeout(Duration.ofSeconds(30))
                            .readTimeout(Duration.ofSeconds(60))
                            .writeTimeout(Duration.ofSeconds(30))
                            .build()
                    ))
                    .build();
                initialized = true;
                log.info("GitHub service initialized with token authentication");
            } else {
                log.warn("No GITHUB_TOKEN configured in environment or .env file, GitHub features will be limited");
            }
        } catch (IOException e) {
            log.error("Failed to initialize GitHub service", e);
        }
    }

    /**
     * Check if GitHub API is reachable
     */
    private boolean checkGitHubConnectivity() {
        HttpURLConnection conn = null;
        try {
            URL url = new URL("https://api.github.com");
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(10000); // 10 seconds
            conn.setReadTimeout(10000);
            conn.setRequestMethod("HEAD");
            int responseCode = conn.getResponseCode();
            log.info("GitHub API connectivity check: HTTP {}", responseCode);
            return responseCode >= 200 && responseCode < 400;
        } catch (Exception e) {
            log.error("GitHub connectivity check failed: {}. " +
                "Check if you can access https://api.github.com from this machine. " +
                "Common issues: VPN, firewall, proxy configuration.", e.getMessage());
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    @Override
    public String getName() {
        return "github";
    }

    @Override
    public PullRequestInfo getPullRequest(String owner, String repo, int prNumber) {
        try {
            GHRepository repository = gitHub.getRepository(owner + "/" + repo);
            GHPullRequest pr = repository.getPullRequest(prNumber);

            return new PullRequestInfo(
                pr.getNumber(),
                pr.getTitle(),
                pr.getBody(),
                pr.getHtmlUrl().toString(),
                pr.getUser().getLogin(),
                pr.getBase().getRef(),
                pr.getHead().getRef(),
                pr.getHead().getSha(),
                pr.getBase().getSha(),
                pr.getState().name(),
                pr.getAdditions(),
                pr.getDeletions(),
                pr.getChangedFiles()
            );
        } catch (IOException e) {
            log.error("Failed to get PR {}/{} #{}", owner, repo, prNumber, e);
            throw new RuntimeException("Failed to get pull request", e);
        }
    }

    @Override
    public String getDiff(String owner, String repo, int prNumber) {
        try {
            GHRepository repository = gitHub.getRepository(owner + "/" + repo);
            GHPullRequest pr = repository.getPullRequest(prNumber);

            // Get the diff by comparing commits
            StringBuilder diff = new StringBuilder();
            for (GHPullRequestFileDetail file : pr.listFiles()) {
                diff.append("diff --git a/").append(file.getFilename())
                    .append(" b/").append(file.getFilename()).append("\n");
                if (file.getPatch() != null) {
                    diff.append(file.getPatch()).append("\n");
                }
            }
            return diff.toString();
        } catch (IOException e) {
            log.error("Failed to get diff for {}/{} #{}", owner, repo, prNumber, e);
            throw new RuntimeException("Failed to get diff", e);
        }
    }

    @Override
    public String getFileContent(String owner, String repo, String path, String commitSha) {
        try {
            GHRepository repository = gitHub.getRepository(owner + "/" + repo);
            GHContent content = repository.getFileContent(path, commitSha);
            return content.getContent();
        } catch (GHFileNotFoundException e) {
            // File not found is expected when checking for optional files (e.g., ESLint configs)
            log.debug("File not found: {}/{}/{} at {}", owner, repo, path, commitSha);
            throw new RuntimeException("File not found: " + path, e);
        } catch (IOException e) {
            log.error("Failed to get file content for {}/{}/{} at {}", owner, repo, path, commitSha, e);
            throw new RuntimeException("Failed to get file content", e);
        }
    }

    @Override
    public List<ChangedFile> getChangedFiles(String owner, String repo, int prNumber) {
        try {
            GHRepository repository = gitHub.getRepository(owner + "/" + repo);
            GHPullRequest pr = repository.getPullRequest(prNumber);

            return pr.listFiles().toList().stream()
                .map(file -> new ChangedFile(
                    file.getFilename(),
                    file.getStatus(),
                    file.getAdditions(),
                    file.getDeletions(),
                    file.getPatch()
                ))
                .collect(Collectors.toList());
        } catch (IOException e) {
            log.error("Failed to get changed files for {}/{} #{}", owner, repo, prNumber, e);
            throw new RuntimeException("Failed to get changed files", e);
        }
    }

    @Override
    public CommitInfo getCommit(String owner, String repo, String commitSha) {
        try {
            GHRepository repository = gitHub.getRepository(owner + "/" + repo);
            GHCommit commit = repository.getCommit(commitSha);
            GHCommit.ShortInfo info = commit.getCommitShortInfo();

            return new CommitInfo(
                commit.getSHA1(),
                info.getMessage(),
                info.getAuthor().getName(),
                info.getAuthor().getEmail(),
                commit.getHtmlUrl().toString(),
                commit.getLinesAdded(),
                commit.getLinesDeleted(),
                commit.getFiles().size()
            );
        } catch (IOException e) {
            log.error("Failed to get commit {}/{} {}", owner, repo, commitSha, e);
            throw new RuntimeException("Failed to get commit", e);
        }
    }

    @Override
    public String getCommitDiff(String owner, String repo, String commitSha) {
        try {
            GHRepository repository = gitHub.getRepository(owner + "/" + repo);
            GHCommit commit = repository.getCommit(commitSha);

            // Build diff from commit files
            StringBuilder diff = new StringBuilder();
            for (GHCommit.File file : commit.getFiles()) {
                diff.append("diff --git a/").append(file.getFileName())
                    .append(" b/").append(file.getFileName()).append("\n");

                if (file.getPatch() != null) {
                    // Add file headers
                    if ("added".equals(file.getStatus())) {
                        diff.append("new file mode 100644\n");
                        diff.append("--- /dev/null\n");
                        diff.append("+++ b/").append(file.getFileName()).append("\n");
                    } else if ("removed".equals(file.getStatus())) {
                        diff.append("deleted file mode 100644\n");
                        diff.append("--- a/").append(file.getFileName()).append("\n");
                        diff.append("+++ /dev/null\n");
                    } else {
                        diff.append("--- a/").append(file.getFileName()).append("\n");
                        diff.append("+++ b/").append(file.getFileName()).append("\n");
                    }
                    diff.append(file.getPatch()).append("\n");
                }
            }
            return diff.toString();
        } catch (IOException e) {
            log.error("Failed to get commit diff for {}/{} {}", owner, repo, commitSha, e);
            throw new RuntimeException("Failed to get commit diff", e);
        }
    }

    @Override
    public List<ChangedFile> getCommitChangedFiles(String owner, String repo, String commitSha) {
        try {
            GHRepository repository = gitHub.getRepository(owner + "/" + repo);
            GHCommit commit = repository.getCommit(commitSha);

            return commit.getFiles().stream()
                .map(file -> new ChangedFile(
                    file.getFileName(),
                    file.getStatus(),
                    file.getLinesAdded(),
                    file.getLinesDeleted(),
                    file.getPatch()
                ))
                .collect(Collectors.toList());
        } catch (IOException e) {
            log.error("Failed to get commit changed files for {}/{} {}", owner, repo, commitSha, e);
            throw new RuntimeException("Failed to get commit changed files", e);
        }
    }

    @Override
    public void postComment(String owner, String repo, int prNumber, String comment) {
        try {
            GHRepository repository = gitHub.getRepository(owner + "/" + repo);
            GHPullRequest pr = repository.getPullRequest(prNumber);
            pr.comment(comment);
            log.info("Posted comment on {}/{} #{}", owner, repo, prNumber);
        } catch (IOException e) {
            log.error("Failed to post comment on {}/{} #{}", owner, repo, prNumber, e);
            throw new RuntimeException("Failed to post comment", e);
        }
    }

    @Override
    public void postInlineComment(String owner, String repo, int prNumber, InlineComment comment) {
        try {
            GHRepository repository = gitHub.getRepository(owner + "/" + repo);
            GHPullRequest pr = repository.getPullRequest(prNumber);

            // Find the file's patch to validate the line is in the diff
            String filePatch = null;
            for (GHPullRequestFileDetail file : pr.listFiles()) {
                if (file.getFilename().equals(comment.filePath())) {
                    filePatch = file.getPatch();
                    break;
                }
            }

            if (filePatch == null) {
                log.warn("File {} not found in PR diff, skipping inline comment at line {}",
                    comment.filePath(), comment.line());
                return;
            }

            // Parse the patch to check if the line is in the diff
            // Build a mini diff string for parsing
            String diffString = "diff --git a/" + comment.filePath() + " b/" + comment.filePath() + "\n" + filePatch;
            List<DiffParser.FileDiff> fileDiffs = diffParser.parse(diffString);

            if (fileDiffs.isEmpty()) {
                log.warn("Could not parse diff for {}, skipping inline comment at line {}",
                    comment.filePath(), comment.line());
                return;
            }

            DiffParser.FileDiff fileDiff = fileDiffs.get(0);

            // Calculate the diff position for the line number
            int diffPosition = diffParser.getDiffPosition(fileDiff, comment.line());
            if (diffPosition == -1) {
                List<Integer> commentableLines = diffParser.getCommentableLines(fileDiff);
                log.warn("Line {} is not in the diff for {}, skipping inline comment. " +
                    "GitHub only allows comments on lines in the diff. " +
                    "Commentable lines in this file: {}",
                    comment.line(), comment.filePath(),
                    commentableLines.size() > 20 ? commentableLines.subList(0, 20) + "... (and more)" : commentableLines);
                return;
            }

            log.debug("Posting comment at line {} (diff position {}) in {}",
                comment.line(), diffPosition, comment.filePath());

            pr.createReviewComment(
                comment.body(),
                comment.commitSha(),
                comment.filePath(),
                diffPosition  // Use diff position, not line number!
            );

            log.info("Posted inline comment on {}/{} #{} at {}:{}",
                owner, repo, prNumber, comment.filePath(), comment.line());
        } catch (IOException e) {
            log.error("Failed to post inline comment on {}/{} #{}", owner, repo, prNumber, e);
            throw new RuntimeException("Failed to post inline comment", e);
        }
    }

    @Override
    public RepositoryInfo getRepository(String owner, String repo) {
        try {
            GHRepository repository = gitHub.getRepository(owner + "/" + repo);

            return new RepositoryInfo(
                repository.getFullName(),
                repository.getName(),
                repository.getOwnerName(),
                repository.getDescription(),
                repository.getLanguage(),
                repository.getDefaultBranch(),
                repository.isPrivate(),
                repository.getHtmlUrl().toString()
            );
        } catch (IOException e) {
            log.error("Failed to get repository {}/{}", owner, repo, e);
            throw new RuntimeException("Failed to get repository", e);
        }
    }

    public boolean isInitialized() {
        return initialized;
    }

    /**
     * Create a GitHub client using an organization's encrypted token.
     * This allows per-organization authentication.
     *
     * @param encryptedToken The encrypted GitHub token from the organization
     * @return A GitHub client authenticated with the decrypted token
     */
    public GitHub createClientWithToken(String encryptedToken) throws IOException {
        if (encryptedToken == null || encryptedToken.isEmpty()) {
            throw new IllegalArgumentException("Token cannot be null or empty");
        }

        String decryptedToken = encryptionService.decrypt(encryptedToken);

        return new GitHubBuilder()
            .withOAuthToken(decryptedToken)
            .withConnector(new org.kohsuke.github.extras.okhttp3.OkHttpGitHubConnector(
                new okhttp3.OkHttpClient.Builder()
                    .connectTimeout(Duration.ofSeconds(30))
                    .readTimeout(Duration.ofSeconds(60))
                    .writeTimeout(Duration.ofSeconds(30))
                    .build()
            ))
            .build();
    }
}
