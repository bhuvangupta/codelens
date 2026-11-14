package com.codelens.gate;

import com.codelens.gate.RepoWorkspaceFactory.Workspace;
import com.codelens.gate.SecurityScanner.ScanContext;
import com.codelens.gate.SecurityScanner.ScanOutcome;
import com.codelens.git.GitProvider.ChangedFile;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Uses a stand-in "semgrep" shell script that returns a canned report per checked-out
 * commit: these tests cover the base-vs-head logic and how CodeLens drives the tool.
 */
class SemgrepScannerTest {

    private static final String HEAD = "a".repeat(40);
    private static final String BASE_TIP = "b".repeat(40);
    private static final String MERGE_BASE = "c".repeat(40);
    private static final ScanContext CONTEXT =
        new ScanContext(GitProvider.GITHUB, "owner", "repo", 7, HEAD, BASE_TIP);
    private static final String DAO = "src/main/java/Dao.java";
    private static final String SQLI = "java.spring.security.sqli";

    @TempDir
    Path tmp;

    private com.codelens.git.GitProvider git;
    private GitProviderFactory providerFactory;
    private RepoWorkspaceFactory workspaceFactory;
    private Path headDir;
    private Path baseDir;
    private Path argsFile;
    private Path cwdFile;

    @BeforeEach
    void setUp() throws Exception {
        git = mock(com.codelens.git.GitProvider.class);
        providerFactory = mock(GitProviderFactory.class);
        when(providerFactory.getProvider(GitProvider.GITHUB)).thenReturn(git);
        when(git.getChangedFiles("owner", "repo", 7)).thenReturn(List.of(changed(DAO, "modified")));
        when(git.getMergeBase("owner", "repo", BASE_TIP, HEAD)).thenReturn(MERGE_BASE);

        headDir = Files.createDirectories(tmp.resolve("head/repo"));
        baseDir = Files.createDirectories(tmp.resolve("base/repo"));
        workspaceFactory = mock(RepoWorkspaceFactory.class);
        when(workspaceFactory.checkout(GitProvider.GITHUB, "owner", "repo", HEAD, 1))
            .thenReturn(new Workspace(headDir.getParent(), headDir));
        when(workspaceFactory.checkout(GitProvider.GITHUB, "owner", "repo", MERGE_BASE, 1))
            .thenReturn(new Workspace(baseDir.getParent(), baseDir));
        argsFile = tmp.resolve("args.txt");
        cwdFile = tmp.resolve("cwd.txt");
    }

    @Test
    void failsOnlyOnFindingsThePrIntroduces() throws Exception {
        source(baseDir, DAO, "class Dao {", "  void old() { query(\"a\" + x); }", "}");
        report(baseDir, finding(SQLI, DAO, 2));
        // The old finding moved down two lines and got re-indented; one new finding was added
        source(headDir, DAO, "class Dao {", "", "", "      void old() { query(\"a\" + x); }",
            "  void added() { query(\"b\" + y); }", "}");
        report(headDir, finding(SQLI, DAO, 4), finding(SQLI, DAO, 5));

        ScanOutcome outcome = scanner(fakeSemgrep(0)).scan(CONTEXT);

        assertFalse(outcome.passed());
        assertEquals("1 new security finding in changed files", outcome.summary());
        assertTrue(outcome.details().contains("| ERROR | sqli | `src/main/java/Dao.java:5` | SQL built by concatenation |"),
            outcome.details());
        assertFalse(outcome.details().contains("Dao.java:4"), "moved code is not a new finding");
        assertTrue(outcome.details().contains("1 other findings in these files were already present"));
    }

    @Test
    void passesWhenEveryFindingWasAlreadyThere() throws Exception {
        source(baseDir, DAO, "query(\"a\" + x);");
        report(baseDir, finding(SQLI, DAO, 1));
        source(headDir, DAO, "// comment", "query(\"a\" + x);");
        report(headDir, finding(SQLI, DAO, 2));

        ScanOutcome outcome = scanner(fakeSemgrep(0)).scan(CONTEXT);

        assertTrue(outcome.passed());
        assertEquals("No new findings in 1 changed file (1 already on base branch)", outcome.summary());
        assertNull(outcome.details());
    }

    @Test
    void secondCopyOfAnExistingFindingIsNew() throws Exception {
        source(baseDir, DAO, "query(\"a\" + x);");
        report(baseDir, finding(SQLI, DAO, 1));
        source(headDir, DAO, "query(\"a\" + x);", "query(\"a\" + x);");
        report(headDir, finding(SQLI, DAO, 1), finding(SQLI, DAO, 2));

        ScanOutcome outcome = scanner(fakeSemgrep(0)).scan(CONTEXT);

        assertFalse(outcome.passed());
        assertEquals("1 new security finding in changed files", outcome.summary());
    }

    @Test
    void newFileIsScannedOnlyAtHead() throws Exception {
        source(headDir, DAO, "query(\"a\" + x);");
        report(headDir, finding(SQLI, DAO, 1));
        // baseDir has no Dao.java: nothing to scan there, so semgrep is not run for it

        ScanOutcome outcome = scanner(fakeSemgrep(0)).scan(CONTEXT);

        assertFalse(outcome.passed());
    }

    @Test
    void skipsCheckoutWhenNoSourceFileChanged() throws Exception {
        when(git.getChangedFiles("owner", "repo", 7)).thenReturn(List.of(
            changed("docs/guide.md", "modified"), changed("logo.png", "added"), changed("Old.java", "removed")));

        ScanOutcome outcome = scanner(fakeSemgrep(0)).scan(CONTEXT);

        assertTrue(outcome.passed());
        assertEquals("No source files changed", outcome.summary());
        verify(workspaceFactory, never()).checkout(any(), any(), any(), any(), anyInt());
    }

    @Test
    void drivesSemgrepSoThePrCannotSuppressFindings() throws Exception {
        source(headDir, DAO, "x");
        source(headDir, ".semgrepignore", "src/");
        source(headDir, "src/.semgrepignore", "*.java");
        report(headDir);

        scanner(fakeSemgrep(0)).scan(CONTEXT);

        List<String> args = Files.readAllLines(argsFile);
        assertEquals("scan", args.get(0));
        assertTrue(args.contains("--disable-nosem"));
        assertTrue(args.contains("--metrics=off"));
        assertEquals("p/java", args.get(args.indexOf("--config") + 1));
        assertEquals("ERROR", args.get(args.indexOf("--severity") + 1));
        String target = args.get(args.size() - 1);
        assertTrue(target.startsWith("/") && target.endsWith("/head/repo/" + DAO), target);
        assertFalse(Files.readString(cwdFile).contains("/head/repo"), "must not run inside the checkout");
        assertTrue(Files.readString(tmp.resolve("ignore-files-seen.txt")).isBlank(),
            ".semgrepignore files must be gone before semgrep starts");
    }

    @Test
    void pathsThatEscapeTheCheckoutAreNotScanned() throws Exception {
        when(git.getChangedFiles("owner", "repo", 7)).thenReturn(List.of(
            changed("../../outside.java", "modified"), changed("--config=evil.yml", "modified")));
        Files.writeString(tmp.resolve("outside.java"), "x");

        ScanOutcome outcome = scanner(fakeSemgrep(0)).scan(CONTEXT);

        assertTrue(outcome.passed());
        assertFalse(Files.exists(argsFile), "nothing valid to scan, so semgrep must not be started");
    }

    @Test
    void toolFailureIsAnErrorNotAPass() throws Exception {
        source(headDir, DAO, "x");
        report(headDir);
        SemgrepScanner scanner = scanner(fakeSemgrep(2));

        assertThrows(java.io.IOException.class, () -> scanner.scan(CONTEXT));
    }

    @Test
    void errorInReportIsAnErrorNotAPass() throws Exception {
        source(headDir, DAO, "x");
        Files.writeString(headDir.resolve("canned.json"),
            "{\"results\":[],\"errors\":[{\"level\":\"error\",\"message\":\"rule download failed\"}]}");
        SemgrepScanner scanner = scanner(fakeSemgrep(0));

        assertThrows(java.io.IOException.class, () -> scanner.scan(CONTEXT));
    }

    @Test
    void warningInReportDoesNotFailTheScan() throws Exception {
        source(headDir, DAO, "x");
        Files.writeString(headDir.resolve("canned.json"),
            "{\"results\":[],\"errors\":[{\"level\":\"warn\",\"message\":\"partial parse\"}]}");

        assertTrue(scanner(fakeSemgrep(0)).scan(CONTEXT).passed());
    }

    @Test
    void reportWithoutResultsIsAnErrorNotAPass() throws Exception {
        source(headDir, DAO, "x");
        Files.writeString(headDir.resolve("canned.json"), "{}");
        SemgrepScanner scanner = scanner(fakeSemgrep(0));

        assertThrows(java.io.IOException.class, () -> scanner.scan(CONTEXT));
    }

    @Test
    void badConfigurationIsRejectedAtStartup() {
        assertThrows(IllegalArgumentException.class, () -> new SemgrepScanner(
            providerFactory, workspaceFactory, new ObjectMapper(), "semgrep", " , ", "ERROR", 30));
        assertThrows(IllegalArgumentException.class, () -> new SemgrepScanner(
            providerFactory, workspaceFactory, new ObjectMapper(), "semgrep", "p/java", "EROR", 30));
    }

    private SemgrepScanner scanner(String semgrepPath) {
        return new SemgrepScanner(providerFactory, workspaceFactory, new ObjectMapper(),
            semgrepPath, "p/java, p/owasp-top-ten", "error", 30);
    }

    private static ChangedFile changed(String path, String status) {
        return new ChangedFile(path, status, 1, 0, "");
    }

    private static void source(Path repoDir, String path, String... lines) throws Exception {
        Path file = repoDir.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, String.join("\n", lines) + "\n");
    }

    /** @REPO@ is replaced by the stand-in with the real checkout path, as semgrep reports absolute paths. */
    private static String finding(String rule, String path, int line) {
        return """
            {"check_id":"%s","path":"@REPO@/%s","start":{"line":%d,"col":1},"end":{"line":%d,"col":9},
             "extra":{"severity":"ERROR","message":"SQL built by concatenation","lines":"requires login"}}"""
            .formatted(rule, path, line, line);
    }

    private static void report(Path repoDir, String... findings) throws Exception {
        Files.writeString(repoDir.resolve("canned.json"),
            "{\"results\":[%s],\"errors\":[]}".formatted(String.join(",", findings)));
    }

    /**
     * Records args, working directory and any .semgrepignore still present, then writes the
     * canned.json of the checkout that contains the last target to --output.
     */
    private String fakeSemgrep(int exitCode) throws Exception {
        Path script = tmp.resolve("fake-semgrep.sh");
        Files.writeString(script, """
            #!/bin/sh
            printf '%%s\\n' "$@" > '%s'
            pwd > '%s'
            out=""
            for last; do :; done
            while [ $# -gt 0 ]; do
              if [ "$1" = "--output" ]; then out="$2"; fi
              shift
            done
            d=$(dirname "$last")
            while [ ! -f "$d/canned.json" ] && [ "$d" != "/" ]; do d=$(dirname "$d"); done
            find "$d" -name .semgrepignore > '%s'
            sed "s|@REPO@|$d|g" "$d/canned.json" > "$out"
            exit %d
            """.formatted(argsFile, cwdFile, tmp.resolve("ignore-files-seen.txt"), exitCode));
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
        return script.toString();
    }
}
