package com.codelens.git.bitbucket;

import com.codelens.config.EncryptionService;
import com.codelens.core.DiffParser;
import com.codelens.git.GitProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class BitbucketService implements GitProvider {

    private final DiffParser diffParser;
    private final EncryptionService encryptionService;
    private final ObjectMapper objectMapper;

    @Value("${codelens.git.bitbucket.url:https://api.bitbucket.org/2.0}")
    private String baseUrl;

    @Value("${codelens.git.bitbucket.token:}")
    private String token;

    private RestClient restClient;
    private boolean initialized = false;

    public BitbucketService(DiffParser diffParser, EncryptionService encryptionService, ObjectMapper objectMapper) {
        this.diffParser = diffParser;
        this.encryptionService = encryptionService;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        try {
            if (token != null && !token.isEmpty()) {
                restClient = createRestClient(token);
                initialized = true;
                log.info("Bitbucket service initialized for {}", baseUrl);
            } else {
                restClient = RestClient.builder().baseUrl(baseUrl).build();
                log.warn("No Bitbucket token configured, Bitbucket integration disabled");
            }
        } catch (Exception e) {
            log.error("Failed to initialize Bitbucket service", e);
        }
    }

    private RestClient createRestClient(String bearerToken) {
        return RestClient.builder()
            .baseUrl(baseUrl)
            .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
            .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
            .build();
    }

    @Override
    public String getName() {
        return "bitbucket";
    }

    @Override
    public PullRequestInfo getPullRequest(String owner, String repo, int prNumber) {
        try {
            String json = restClient.get()
                .uri("/repositories/{workspace}/{repo}/pullrequests/{id}", owner, repo, prNumber)
                .retrieve()
                .body(String.class);

            JsonNode pr = objectMapper.readTree(json);

            return new PullRequestInfo(
                pr.get("id").asInt(),
                pr.get("title").asText(),
                pr.has("description") ? pr.get("description").asText("") : "",
                pr.get("links").get("html").get("href").asText(),
                pr.get("author").get("display_name").asText(),
                pr.get("destination").get("branch").get("name").asText(),
                pr.get("source").get("branch").get("name").asText(),
                pr.get("source").get("commit").get("hash").asText(),
                pr.get("destination").get("commit").get("hash").asText(),
                pr.get("state").asText(),
                0, // Bitbucket doesn't provide stats in PR response
                0,
                0
            );
        } catch (Exception e) {
            log.error("Failed to get PR {}/{} #{}", owner, repo, prNumber, e);
            throw new RuntimeException("Failed to get pull request", e);
        }
    }

    @Override
    public String getDiff(String owner, String repo, int prNumber) {
        try {
            return restClient.get()
                .uri("/repositories/{workspace}/{repo}/pullrequests/{id}/diff", owner, repo, prNumber)
                .accept(MediaType.TEXT_PLAIN)
                .retrieve()
                .body(String.class);
        } catch (Exception e) {
            log.error("Failed to get diff for {}/{} #{}", owner, repo, prNumber, e);
            throw new RuntimeException("Failed to get diff", e);
        }
    }

    @Override
    public String getFileContent(String owner, String repo, String path, String commitSha) {
        try {
            return restClient.get()
                .uri("/repositories/{workspace}/{repo}/src/{commit}/{path}", owner, repo, commitSha, path)
                .retrieve()
                .body(String.class);
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("404")) {
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
            List<ChangedFile> files = new ArrayList<>();
            String json = restClient.get()
                .uri("/repositories/{workspace}/{repo}/pullrequests/{id}/diffstat", owner, repo, prNumber)
                .retrieve()
                .body(String.class);

            JsonNode root = objectMapper.readTree(json);
            JsonNode values = root.get("values");

            if (values != null && values.isArray()) {
                for (JsonNode entry : values) {
                    String status = entry.get("status").asText();
                    String filename = "removed".equals(status)
                        ? entry.get("old").get("path").asText()
                        : entry.get("new").get("path").asText();
                    int additions = entry.has("lines_added") ? entry.get("lines_added").asInt() : 0;
                    int deletions = entry.has("lines_removed") ? entry.get("lines_removed").asInt() : 0;

                    files.add(new ChangedFile(
                        filename,
                        mapDiffStatus(status),
                        additions,
                        deletions,
                        null
                    ));
                }
            }

            // Handle pagination
            while (root.has("next") && !root.get("next").isNull()) {
                String nextUrl = root.get("next").asText();
                json = restClient.get()
                    .uri(nextUrl)
                    .retrieve()
                    .body(String.class);
                root = objectMapper.readTree(json);
                values = root.get("values");
                if (values != null && values.isArray()) {
                    for (JsonNode entry : values) {
                        String status = entry.get("status").asText();
                        String filename = "removed".equals(status)
                            ? entry.get("old").get("path").asText()
                            : entry.get("new").get("path").asText();
                        int additions = entry.has("lines_added") ? entry.get("lines_added").asInt() : 0;
                        int deletions = entry.has("lines_removed") ? entry.get("lines_removed").asInt() : 0;

                        files.add(new ChangedFile(
                            filename,
                            mapDiffStatus(status),
                            additions,
                            deletions,
                            null
                        ));
                    }
                }
            }

            return files;
        } catch (Exception e) {
            log.error("Failed to get changed files for {}/{} #{}", owner, repo, prNumber, e);
            throw new RuntimeException("Failed to get changed files", e);
        }
    }

    @Override
    public CommitInfo getCommit(String owner, String repo, String commitSha) {
        try {
            String json = restClient.get()
                .uri("/repositories/{workspace}/{repo}/commit/{sha}", owner, repo, commitSha)
                .retrieve()
                .body(String.class);

            JsonNode commit = objectMapper.readTree(json);

            return new CommitInfo(
                commit.get("hash").asText(),
                commit.get("message").asText(),
                commit.get("author").has("user")
                    ? commit.get("author").get("user").get("display_name").asText()
                    : commit.get("author").get("raw").asText(),
                commit.get("author").get("raw").asText(),
                commit.get("links").get("html").get("href").asText(),
                0, // Bitbucket doesn't provide stats in commit response
                0,
                0
            );
        } catch (Exception e) {
            log.error("Failed to get commit {}/{} {}", owner, repo, commitSha, e);
            throw new RuntimeException("Failed to get commit", e);
        }
    }

    @Override
    public String getCommitDiff(String owner, String repo, String commitSha) {
        try {
            return restClient.get()
                .uri("/repositories/{workspace}/{repo}/diff/{sha}", owner, repo, commitSha)
                .accept(MediaType.TEXT_PLAIN)
                .retrieve()
                .body(String.class);
        } catch (Exception e) {
            log.error("Failed to get commit diff for {}/{} {}", owner, repo, commitSha, e);
            throw new RuntimeException("Failed to get commit diff", e);
        }
    }

    @Override
    public List<ChangedFile> getCommitChangedFiles(String owner, String repo, String commitSha) {
        try {
            String diff = getCommitDiff(owner, repo, commitSha);
            List<DiffParser.FileDiff> fileDiffs = diffParser.parse(diff);

            return fileDiffs.stream()
                .map(fd -> new ChangedFile(
                    fd.getPath(),
                    fd.oldPath() == null ? "added" : (fd.newPath() == null ? "deleted" : "modified"),
                    0,
                    0,
                    null
                ))
                .toList();
        } catch (Exception e) {
            log.error("Failed to get commit changed files for {}/{} {}", owner, repo, commitSha, e);
            throw new RuntimeException("Failed to get commit changed files", e);
        }
    }

    @Override
    public void postComment(String owner, String repo, int prNumber, String comment) {
        try {
            Map<String, Object> body = Map.of("content", Map.of("raw", comment));

            restClient.post()
                .uri("/repositories/{workspace}/{repo}/pullrequests/{id}/comments", owner, repo, prNumber)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toBodilessEntity();

            log.info("Posted comment on {}/{} #{}", owner, repo, prNumber);
        } catch (Exception e) {
            log.error("Failed to post comment on {}/{} #{}", owner, repo, prNumber, e);
            throw new RuntimeException("Failed to post comment", e);
        }
    }

    @Override
    public void postInlineComment(String owner, String repo, int prNumber, InlineComment comment) {
        try {
            // Validate line is in the diff
            String diff = getDiff(owner, repo, prNumber);
            String fileDiffString = extractFileDiff(diff, comment.filePath());

            if (fileDiffString == null) {
                log.warn("File {} not found in PR diff, skipping inline comment at line {}",
                    comment.filePath(), comment.line());
                return;
            }

            String wrappedDiff = "diff --git a/" + comment.filePath() + " b/" + comment.filePath() + "\n" + fileDiffString;
            List<DiffParser.FileDiff> fileDiffs = diffParser.parse(wrappedDiff);

            if (fileDiffs.isEmpty()) {
                log.warn("Could not parse diff for {}, skipping inline comment at line {}",
                    comment.filePath(), comment.line());
                return;
            }

            if (!diffParser.isLineInDiff(fileDiffs.get(0), comment.line())) {
                log.warn("Line {} is not in the diff for {}, skipping inline comment",
                    comment.line(), comment.filePath());
                return;
            }

            Map<String, Object> body = Map.of(
                "content", Map.of("raw", comment.body()),
                "inline", Map.of(
                    "path", comment.filePath(),
                    "to", comment.line()
                )
            );

            restClient.post()
                .uri("/repositories/{workspace}/{repo}/pullrequests/{id}/comments", owner, repo, prNumber)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toBodilessEntity();

            log.info("Posted inline comment on {}/{} #{} at {}:{}",
                owner, repo, prNumber, comment.filePath(), comment.line());
        } catch (Exception e) {
            log.error("Failed to post inline comment on {}/{} #{}", owner, repo, prNumber, e);
            throw new RuntimeException("Failed to post inline comment", e);
        }
    }

    @Override
    public RepositoryInfo getRepository(String owner, String repo) {
        try {
            String json = restClient.get()
                .uri("/repositories/{workspace}/{repo}", owner, repo)
                .retrieve()
                .body(String.class);

            JsonNode repoNode = objectMapper.readTree(json);

            return new RepositoryInfo(
                repoNode.get("full_name").asText(),
                repoNode.get("name").asText(),
                owner,
                repoNode.has("description") ? repoNode.get("description").asText("") : "",
                repoNode.has("language") ? repoNode.get("language").asText("") : "",
                repoNode.has("mainbranch") && repoNode.get("mainbranch").has("name")
                    ? repoNode.get("mainbranch").get("name").asText() : "main",
                repoNode.has("is_private") && repoNode.get("is_private").asBoolean(),
                repoNode.get("links").get("html").get("href").asText()
            );
        } catch (Exception e) {
            log.error("Failed to get repository {}/{}", owner, repo, e);
            throw new RuntimeException("Failed to get repository", e);
        }
    }

    public boolean isInitialized() {
        return initialized;
    }

    /**
     * Create a RestClient using an organization's encrypted token.
     */
    public RestClient createClientWithToken(String encryptedToken) {
        if (encryptedToken == null || encryptedToken.isEmpty()) {
            throw new IllegalArgumentException("Token cannot be null or empty");
        }
        String decryptedToken = encryptionService.decrypt(encryptedToken);
        return createRestClient(decryptedToken);
    }

    private String mapDiffStatus(String bitbucketStatus) {
        return switch (bitbucketStatus) {
            case "added" -> "added";
            case "removed" -> "deleted";
            case "modified" -> "modified";
            case "renamed" -> "renamed";
            default -> "modified";
        };
    }

    /**
     * Extract the diff section for a specific file from a full diff string.
     */
    private String extractFileDiff(String fullDiff, String filePath) {
        String[] lines = fullDiff.split("\n");
        StringBuilder fileDiff = new StringBuilder();
        boolean inFile = false;

        for (String line : lines) {
            if (line.startsWith("diff --git")) {
                if (inFile) break; // Done with our file
                if (line.contains("b/" + filePath)) {
                    inFile = true;
                }
                continue;
            }
            if (inFile) {
                fileDiff.append(line).append("\n");
            }
        }

        return fileDiff.isEmpty() ? null : fileDiff.toString();
    }
}
