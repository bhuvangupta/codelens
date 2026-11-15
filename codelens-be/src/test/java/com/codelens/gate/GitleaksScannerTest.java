package com.codelens.gate;

import com.codelens.gate.RepoWorkspaceFactory.Workspace;
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
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Uses a stand-in "gitleaks" shell script: these tests cover how CodeLens drives the
 * tool and reads its report, not Gitleaks' own detection.
 */
class GitleaksScannerTest {

    // Real commits in the stand-in workspace: the scanner checks they exist before scanning
    private String SHA1;
    private String SHA2;
    private String HEAD;
    private ScanContext CONTEXT;

    @TempDir
    Path tmp;

    private com.codelens.git.GitProvider git;
    private GitProviderFactory providerFactory;
    private RepoWorkspaceFactory workspaceFactory;
    private Path workspaceRoot;
    private Path argsFile;

    @BeforeEach
    void setUp() throws Exception {
        git = mock(com.codelens.git.GitProvider.class);
        providerFactory = mock(GitProviderFactory.class);
        when(providerFactory.getProvider(GitProvider.GITHUB)).thenReturn(git);

        workspaceRoot = Files.createDirectory(tmp.resolve("ws"));
        Path repoDir = Files.createDirectory(workspaceRoot.resolve("repo"));
        SHA1 = commit(repoDir, "one");
        SHA2 = commit(repoDir, "two");
        HEAD = SHA2;
        CONTEXT = new ScanContext(GitProvider.GITHUB, "owner", "repo", 7, HEAD, "c".repeat(40));
        when(git.getPullRequestCommits("owner", "repo", 7)).thenReturn(List.of(SHA1, SHA2));
        workspaceFactory = mock(RepoWorkspaceFactory.class);
        when(workspaceFactory.fetchHistory(any(), any(), any(), any(), anyInt()))
            .thenReturn(new Workspace(workspaceRoot, repoDir));
        argsFile = tmp.resolve("args.txt");
    }

    @Test
    void reportsFindingsWithoutSecretValues() throws Exception {
        String report = """
            [{"RuleID":"aws-access-token","File":"src/main/resources/application.yaml","StartLine":12,
              "Commit":"%s","Secret":"REDACTED","Match":"REDACTED","Description":"AWS"},
             {"RuleID":"generic-api-key","File":"evil|`name`.java","StartLine":3,"Commit":"%s",
              "Secret":"REDACTED","Match":"REDACTED"}]""".formatted(SHA1, SHA2);

        ScanOutcome outcome = scanner(fakeGitleaks(report, 0)).scan(CONTEXT);

        assertFalse(outcome.passed());
        assertEquals("2 potential secrets in 2 commits", outcome.summary());
        assertTrue(outcome.details().contains("aws-access-token"));
        assertTrue(outcome.details().contains("`src/main/resources/application.yaml` | 12 | " + SHA1.substring(0, 8)));
        assertTrue(outcome.details().contains("`evil__name_.java`"), "file names must not break the table");
        assertTrue(outcome.details().contains("rotate"));
    }

    @Test
    void passesWhenReportIsEmpty() throws Exception {
        ScanOutcome outcome = scanner(fakeGitleaks("[]", 0)).scan(CONTEXT);

        assertTrue(outcome.passed());
        assertEquals("No secrets in 2 commits", outcome.summary());
        assertNull(outcome.details());
    }

    @Test
    void scansExactlyThePrCommitsWithPinnedConfigAndRedaction() throws Exception {
        scanner(fakeGitleaks("[]", 0)).scan(CONTEXT);

        // Head plus enough history for every PR commit to have its parent
        verify(workspaceFactory).fetchHistory(GitProvider.GITHUB, "owner", "repo", HEAD, 3);

        List<String> args = Files.readAllLines(argsFile);
        assertEquals("git", args.get(0));
        assertTrue(args.contains("--redact"));
        assertTrue(args.contains("--ignore-gitleaks-allow"));
        assertEquals("--no-walk " + SHA1 + " " + SHA2, args.get(args.indexOf("--log-opts") + 1));
        assertEquals("0", args.get(args.indexOf("--exit-code") + 1));
        // Neither the config nor the ignore list may come from the PR's own tree
        assertFalse(args.get(args.indexOf("--config") + 1).startsWith(workspaceRoot.toString()));
        assertFalse(args.get(args.indexOf("--gitleaks-ignore-path") + 1).startsWith(workspaceRoot.toString()));
    }

    @Test
    void toolFailureIsAnErrorNotAPass() throws Exception {
        GitleaksScanner scanner = scanner(fakeGitleaks("[]", 1));

        assertThrows(java.io.IOException.class, () -> scanner.scan(CONTEXT));
    }

    @Test
    void missingBinaryIsAnErrorNotAPass() {
        GitleaksScanner scanner = scanner(tmp.resolve("no-such-gitleaks").toString());

        assertThrows(java.io.IOException.class, () -> scanner.scan(CONTEXT));
    }

    @Test
    void errorLoggedByGitleaksIsAnErrorEvenWithExitCodeZero() throws Exception {
        // Real gitleaks does this when its `git log` fails: logs ERR, writes [], exits 0
        GitleaksScanner scanner = scanner(fakeGitleaks("[]", 0, "2:24AM ERR [git] fatal: bad object"));

        assertThrows(java.io.IOException.class, () -> scanner.scan(CONTEXT));
    }

    @Test
    void commitMissingFromFetchedHistoryIsAnErrorNotAPass() throws Exception {
        when(git.getPullRequestCommits("owner", "repo", 7)).thenReturn(List.of(SHA1, "d".repeat(40)));
        GitleaksScanner scanner = scanner(fakeGitleaks("[]", 0));

        assertThrows(java.io.IOException.class, () -> scanner.scan(CONTEXT));
        assertFalse(Files.exists(argsFile), "gitleaks must not even be started");
    }

    @Test
    void garbageReportIsAnErrorNotAPass() throws Exception {
        GitleaksScanner scanner = scanner(fakeGitleaks("{\"oops\":true}", 0));

        assertThrows(java.io.IOException.class, () -> scanner.scan(CONTEXT));
    }

    @Test
    void workspaceIsDeletedAfterTheScan() throws Exception {
        scanner(fakeGitleaks("[]", 0)).scan(CONTEXT);

        assertFalse(Files.exists(workspaceRoot));
    }

    @Test
    void refusesCommitIdsThatCouldBecomeGitLogOptions() throws Exception {
        when(git.getPullRequestCommits("owner", "repo", 7)).thenReturn(List.of(SHA1, "--all"));
        GitleaksScanner scanner = scanner(fakeGitleaks("[]", 0));

        assertThrows(IllegalStateException.class, () -> scanner.scan(CONTEXT));
        verify(workspaceFactory, never()).fetchHistory(any(), any(), any(), any(), anyInt());
    }

    @Test
    void emptyPullRequestPassesWithoutCheckout() throws Exception {
        when(git.getPullRequestCommits("owner", "repo", 7)).thenReturn(List.of());

        assertTrue(scanner(fakeGitleaks("[]", 0)).scan(CONTEXT).passed());
        verify(workspaceFactory, never()).fetchHistory(any(), any(), any(), eq(HEAD), anyInt());
    }

    private GitleaksScanner scanner(String gitleaksPath) {
        return new GitleaksScanner(providerFactory, workspaceFactory, new ObjectMapper(), gitleaksPath, 30);
    }

    /** A script that records its arguments, writes {@code report} to --report-path and exits. */
    private String fakeGitleaks(String report, int exitCode) throws Exception {
        return fakeGitleaks(report, exitCode, "");
    }

    private String fakeGitleaks(String report, int exitCode, String logLine) throws Exception {
        Path canned = Files.writeString(tmp.resolve("canned.json"), report);
        Path script = tmp.resolve("fake-gitleaks.sh");
        Files.writeString(script, """
            #!/bin/sh
            printf '%%s\\n' "$@" > '%s'
            echo '%s'
            while [ $# -gt 0 ]; do
              if [ "$1" = "--report-path" ]; then cp '%s' "$2"; fi
              shift
            done
            exit %d
            """.formatted(argsFile, logLine, canned, exitCode));
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
        return script.toString();
    }

    private static String commit(Path repoDir, String message) throws Exception {
        if (!Files.exists(repoDir.resolve(".git"))) {
            run(repoDir, "init", "-q");
        }
        run(repoDir, "-c", "user.name=t", "-c", "user.email=t@example.com",
            "commit", "-q", "--allow-empty", "-m", message);
        return run(repoDir, "rev-parse", "HEAD").strip();
    }

    private static String run(Path repoDir, String... args) throws Exception {
        List<String> command = new java.util.ArrayList<>(List.of("git", "-C", repoDir.toString()));
        command.addAll(List.of(args));
        ProcessRunner.Result result = ProcessRunner.run(command, null, java.util.Map.of(), 60);
        assertEquals(0, result.exitCode(), result.output());
        return result.output();
    }
}
