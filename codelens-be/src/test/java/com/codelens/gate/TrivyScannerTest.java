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
 * Uses a stand-in "trivy" shell script that returns a canned report per checked-out
 * commit: these tests cover the base-vs-head logic and how CodeLens drives the tool.
 */
class TrivyScannerTest {

    private static final String HEAD = "a".repeat(40);
    private static final String BASE_TIP = "b".repeat(40);
    private static final String MERGE_BASE = "c".repeat(40);
    private static final ScanContext CONTEXT =
        new ScanContext(GitProvider.GITHUB, "owner", "repo", 7, HEAD, BASE_TIP);

    private static final String LOG4SHELL = vuln("CVE-2021-44228", "org.apache.logging.log4j:log4j-core", "2.14.1", "2.15.0", "CRITICAL");
    private static final String OLD_JACKSON = vuln("CVE-2020-36518", "com.fasterxml.jackson.core:jackson-databind", "2.12.0", "2.12.6.1", "HIGH");

    private static final String ALLOWED = "repo.maven.apache.org, Nexus.Corp.example";

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
        when(git.getChangedFiles("owner", "repo", 7)).thenReturn(List.of(changed("backend/pom.xml")));
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
    void failsOnlyOnVulnerabilitiesThePrIntroduces() throws Exception {
        report(baseDir, OLD_JACKSON);
        report(headDir, OLD_JACKSON, LOG4SHELL);

        ScanOutcome outcome = scanner(fakeTrivy(0, "")).scan(CONTEXT);

        assertFalse(outcome.passed());
        assertEquals("1 new HIGH/CRITICAL vulnerability in dependencies", outcome.summary());
        assertTrue(outcome.details().contains(
            "| CRITICAL | org.apache.logging.log4j:log4j-core | 2.14.1 | 2.15.0 | CVE-2021-44228 | `backend/pom.xml` |"),
            outcome.details());
        assertFalse(outcome.details().contains("CVE-2020-36518"), "pre-existing issue must not be listed");
        assertTrue(outcome.details().contains("1 other vulnerabilities were already present"));
    }

    @Test
    void passesWhenEveryVulnerabilityWasAlreadyThere() throws Exception {
        report(baseDir, OLD_JACKSON);
        report(headDir, OLD_JACKSON);

        ScanOutcome outcome = scanner(fakeTrivy(0, "")).scan(CONTEXT);

        assertTrue(outcome.passed());
        assertEquals("No new vulnerable dependencies (1 already on base branch)", outcome.summary());
        assertNull(outcome.details());
    }

    @Test
    void bumpingToAnotherVersionWithTheSameAdvisoryIsNotNew() throws Exception {
        report(baseDir, OLD_JACKSON);
        report(headDir, vuln("CVE-2020-36518", "com.fasterxml.jackson.core:jackson-databind", "2.12.3", "2.12.6.1", "HIGH"));

        assertTrue(scanner(fakeTrivy(0, "")).scan(CONTEXT).passed());
    }

    @Test
    void comparesAgainstTheMergeBaseNotTheBaseBranchTip() throws Exception {
        report(baseDir);
        report(headDir);

        scanner(fakeTrivy(0, "")).scan(CONTEXT);

        verify(workspaceFactory).checkout(GitProvider.GITHUB, "owner", "repo", MERGE_BASE, 1);
        verify(workspaceFactory, never()).checkout(GitProvider.GITHUB, "owner", "repo", BASE_TIP, 1);
    }

    @Test
    void skipsCheckoutWhenNoDependencyFileChanged() throws Exception {
        when(git.getChangedFiles("owner", "repo", 7))
            .thenReturn(List.of(changed("src/main/java/App.java"), changed("docs/pom.xml.md")));

        ScanOutcome outcome = scanner(fakeTrivy(0, "")).scan(CONTEXT);

        assertTrue(outcome.passed());
        assertEquals("No dependency files changed", outcome.summary());
        verify(workspaceFactory, never()).checkout(any(), any(), any(), any(), anyInt());
    }

    @Test
    void runsOutsideTheRepoWithItsOwnIgnoreFile() throws Exception {
        report(baseDir);
        report(headDir);

        scanner(fakeTrivy(0, "")).scan(CONTEXT);

        List<String> args = Files.readAllLines(argsFile);
        assertEquals("fs", args.get(0));
        assertEquals("vuln", args.get(args.indexOf("--scanners") + 1));
        assertEquals("HIGH,CRITICAL", args.get(args.indexOf("--severity") + 1));
        assertFalse(args.contains("--offline-scan"));
        String cwd = Files.readString(cwdFile).strip();
        String ignoreFile = args.get(args.indexOf("--ignorefile") + 1);
        // A trivy.yaml or .trivyignore shipped in the PR must not be picked up.
        // (The scratch dir is already deleted here, so compare path text only.)
        for (String location : List.of(cwd, ignoreFile)) {
            assertTrue(location.contains("codelens-trivy-"), location);
            assertFalse(location.contains("/head/repo") || location.contains("/base/repo"), location);
        }
    }

    @Test
    void offlineModeIsPassedThrough() throws Exception {
        report(baseDir);
        report(headDir);

        new TrivyScanner(providerFactory, workspaceFactory, new ObjectMapper(),
            fakeTrivy(0, ""), "critical", true, ALLOWED, 30).scan(CONTEXT);

        List<String> args = Files.readAllLines(argsFile);
        assertTrue(args.contains("--offline-scan"));
        assertEquals("CRITICAL", args.get(args.indexOf("--severity") + 1));
    }

    @Test
    void toolFailureIsAnErrorNotAPass() throws Exception {
        report(headDir);
        TrivyScanner scanner = scanner(fakeTrivy(1, ""));

        assertThrows(java.io.IOException.class, () -> scanner.scan(CONTEXT));
    }

    @Test
    void errorLoggedByTrivyIsAnErrorEvenWithExitCodeZero() throws Exception {
        report(headDir);
        TrivyScanner scanner = scanner(fakeTrivy(0, "2026-01-01T00:00:00Z\tERROR\tDB download failed"));

        assertThrows(java.io.IOException.class, () -> scanner.scan(CONTEXT));
    }

    @Test
    void reportWithoutSchemaVersionIsAnErrorNotAPass() throws Exception {
        Files.writeString(headDir.resolve("canned.json"), "{}");
        TrivyScanner scanner = scanner(fakeTrivy(0, ""));

        assertThrows(java.io.IOException.class, () -> scanner.scan(CONTEXT));
    }

    @Test
    void workspacesAreDeletedAfterTheScan() throws Exception {
        report(baseDir);
        report(headDir);

        scanner(fakeTrivy(0, "")).scan(CONTEXT);

        assertFalse(Files.exists(headDir));
        assertFalse(Files.exists(baseDir));
    }

    @Test
    void pomWithUnlistedRepositoryIsNotHandedToTrivy() throws Exception {
        report(baseDir);
        report(headDir);
        Files.writeString(headDir.resolve("pom.xml"), """
            <project><repositories>
              <repository><id>ok</id><url>https://nexus.corp.example/maven</url></repository>
              <repository><id>evil</id><url>http://169.254.169.254/latest|x</url></repository>
            </repositories></project>""");

        ScanOutcome outcome = scanner(fakeTrivy(0, "")).scan(CONTEXT);

        assertFalse(outcome.passed());
        assertEquals("pom.xml uses a Maven repository that is not allowlisted", outcome.summary());
        assertTrue(outcome.details().contains("| `pom.xml` | http_//169.254.169.254/latest_x |"), outcome.details());
        assertFalse(outcome.details().contains("nexus.corp.example"), "allowlisted host must not be reported");
        assertFalse(Files.exists(argsFile), "trivy must not be started");
    }

    @Test
    void unparseablePomIsAnErrorNotAPass() throws Exception {
        report(headDir);
        Files.writeString(headDir.resolve("pom.xml"), "<project><dependencies><dependency>");
        TrivyScanner scanner = scanner(fakeTrivy(0, ""));

        // Real trivy skips a broken pom.xml and reports nothing
        assertThrows(java.io.IOException.class, () -> scanner.scan(CONTEXT));
    }

    @Test
    void offlineScanSkipsTheRepositoryCheck() throws Exception {
        report(baseDir);
        report(headDir);
        Files.writeString(headDir.resolve("pom.xml"),
            "<project><repositories><repository><url>http://10.0.0.1/</url></repository></repositories></project>");

        ScanOutcome outcome = new TrivyScanner(providerFactory, workspaceFactory, new ObjectMapper(),
            fakeTrivy(0, ""), "HIGH,CRITICAL", true, ALLOWED, 30).scan(CONTEXT);

        assertTrue(outcome.passed());
    }

    @Test
    void unknownSeverityIsRejectedAtStartup() {
        assertThrows(IllegalArgumentException.class, () -> new TrivyScanner(
            providerFactory, workspaceFactory, new ObjectMapper(), "trivy", "HIHG", false, ALLOWED, 30));
    }

    @Test
    void recognisesDependencyFilesByName() {
        assertTrue(TrivyScanner.isManifest("pom.xml"));
        assertTrue(TrivyScanner.isManifest("services/api/pom.xml"));
        assertTrue(TrivyScanner.isManifest("web/package-lock.json"));
        assertFalse(TrivyScanner.isManifest("src/pom.xml.bak"));
        assertFalse(TrivyScanner.isManifest("build.gradle"));
    }

    private TrivyScanner scanner(String trivyPath) {
        return new TrivyScanner(providerFactory, workspaceFactory, new ObjectMapper(),
            trivyPath, "HIGH,CRITICAL", false, ALLOWED, 30);
    }

    private static ChangedFile changed(String path) {
        return new ChangedFile(path, "modified", 1, 0, "");
    }

    private static String vuln(String id, String pkg, String installed, String fixed, String severity) {
        return """
            {"VulnerabilityID":"%s","PkgName":"%s","InstalledVersion":"%s","FixedVersion":"%s","Severity":"%s"}"""
            .formatted(id, pkg, installed, fixed, severity);
    }

    /** The report the stand-in trivy returns when asked to scan {@code repoDir}. */
    private static void report(Path repoDir, String... vulnerabilities) throws Exception {
        Files.writeString(repoDir.resolve("canned.json"), """
            {"SchemaVersion":2,"Results":[{"Target":"backend/pom.xml","Type":"pom","Vulnerabilities":[%s]}]}"""
            .formatted(String.join(",", vulnerabilities)));
    }

    /** Records args and working directory, copies <scanned dir>/canned.json to --output, exits. */
    private String fakeTrivy(int exitCode, String logLine) throws Exception {
        Path script = tmp.resolve("fake-trivy.sh");
        Files.writeString(script, """
            #!/bin/sh
            printf '%%s\\n' "$@" > '%s'
            pwd > '%s'
            printf '%%s\\n' '%s'
            out=""
            for last; do :; done
            while [ $# -gt 0 ]; do
              if [ "$1" = "--output" ]; then out="$2"; fi
              shift
            done
            if [ -f "$last/canned.json" ]; then cp "$last/canned.json" "$out"; fi
            exit %d
            """.formatted(argsFile, cwdFile, logLine, exitCode));
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
        return script.toString();
    }
}
