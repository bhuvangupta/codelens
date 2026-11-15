package com.codelens.gate;

import com.codelens.gate.SecurityScanner.ScanContext;
import com.codelens.gate.SecurityScanner.ScanOutcome;
import com.codelens.git.GitProviderFactory;
import com.codelens.model.entity.Repository.GitProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * End to end with the real git and the real gitleaks binary against a local origin
 * repository. Skipped when gitleaks is not installed.
 */
class GitleaksScannerRealToolTest {

    // Assembled at runtime so this file does not itself look like a leaked token
    private static final String OLD_SECRET = "gh" + "p_" + "x7Kq2mVb9Zr4Tn8Lw3Yc6Jh1Fd5Gs0Pa2Ue4";
    private static final String PR_SECRET = "gh" + "p_" + "Q3wE7rT1yU9iO5pA2sD8fG4hJ6kL0zX3cV7b";

    @TempDir
    Path tmp;

    private Path origin;
    private com.codelens.git.GitProvider git;
    private GitleaksScanner scanner;

    @BeforeEach
    void setUp() throws Exception {
        assumeTrue(gitleaksInstalled(), "gitleaks not installed");

        origin = Files.createDirectories(tmp.resolve("owner/repo.git"));
        git("init", "-q");
        git("config", "uploadpack.allowAnySHA1InWant", "true");

        git = mock(com.codelens.git.GitProvider.class);
        GitProviderFactory providerFactory = mock(GitProviderFactory.class);
        when(providerFactory.getProvider(GitProvider.GITHUB)).thenReturn(git);
        scanner = new GitleaksScanner(providerFactory,
            new RepoWorkspaceFactory(tmp.toUri().toString(), "", 60), new ObjectMapper(), "gitleaks", 60);
    }

    @Test
    void findsSecretThatWasAddedThenDeletedButNotOneAlreadyOnTheBaseBranch() throws Exception {
        commit("old.properties", "token=" + OLD_SECRET + "\n");           // base branch history
        String added = commit("app.properties", "github.token=" + PR_SECRET + "\n");
        String removed = commit("app.properties", "github.token=${GITHUB_TOKEN}\n");

        ScanOutcome outcome = scan(List.of(added, removed), removed);

        assertFalse(outcome.passed());
        assertEquals("1 potential secret in 2 commits", outcome.summary());
        assertTrue(outcome.details().contains("`app.properties`"), outcome.details());
        assertTrue(outcome.details().contains(added.substring(0, 8)), outcome.details());
        assertFalse(outcome.details().contains("old.properties"), outcome.details());
        assertFalse(outcome.details().contains(PR_SECRET), "the secret itself must never be reported");
    }

    @Test
    void pullRequestCannotSuppressItsOwnFindings() throws Exception {
        commit("README.md", "hello\n");
        List<String> pr = new ArrayList<>();
        pr.add(commit(".gitleaks.toml", "title = \"allow all\"\n[allowlist]\npaths = ['''.*''']\n"));
        pr.add(commit("Keys.java", "String t = \"" + PR_SECRET + "\"; // gitleaks:allow\n"));
        // .gitleaksignore entries are <commit>:<file>:<rule>:<line>
        pr.add(commit(".gitleaksignore", pr.get(1) + ":Keys.java:github-pat:1\n"));

        ScanOutcome outcome = scan(pr, pr.get(2));

        assertFalse(outcome.passed(), "suppressions shipped in the PR must be ignored");
        assertTrue(outcome.details().contains("`Keys.java`"), outcome.details());
    }

    @Test
    void commitThatIsNotInTheRepositoryIsAnErrorNotAPass() throws Exception {
        String head = commit("README.md", "hello\n");

        // gitleaks alone would log an error, write an empty report and exit 0 here
        assertThrows(java.io.IOException.class, () -> scan(List.of(head, "d".repeat(40)), head));
    }

    @Test
    void cleanPullRequestPasses() throws Exception {
        commit("README.md", "hello\n");
        String change = commit("Service.java", "class Service { String url = \"https://example.com\"; }\n");

        ScanOutcome outcome = scan(List.of(change), change);

        assertTrue(outcome.passed(), String.valueOf(outcome.details()));
        assertEquals("No secrets in 1 commit", outcome.summary());
    }

    private ScanOutcome scan(List<String> prCommits, String head) throws Exception {
        when(git.getPullRequestCommits("owner", "repo", 7)).thenReturn(prCommits);
        return scanner.scan(new ScanContext(GitProvider.GITHUB, "owner", "repo", 7, head, null));
    }

    private String commit(String file, String content) throws Exception {
        Files.writeString(origin.resolve(file), content);
        git("add", ".");
        git("-c", "user.name=t", "-c", "user.email=t@example.com", "commit", "-q", "-m", "edit " + file);
        return git("rev-parse", "HEAD").strip();
    }

    private String git(String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "-C", origin.toString()));
        command.addAll(List.of(args));
        ProcessRunner.Result result = ProcessRunner.run(command, null, Map.of(), 60);
        assertEquals(0, result.exitCode(), result.output());
        return result.output();
    }

    private static boolean gitleaksInstalled() {
        try {
            return ProcessRunner.run(List.of("gitleaks", "version"), null, Map.of(), 20).exitCode() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
