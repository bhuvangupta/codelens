package com.codelens.gate;

import com.codelens.gate.SecurityScanner.ScanContext;
import com.codelens.gate.SecurityScanner.ScanOutcome;
import com.codelens.git.GitProvider.ChangedFile;
import com.codelens.git.GitProviderFactory;
import com.codelens.model.entity.Repository.GitProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * End to end with the real git and the real semgrep binary against a local origin
 * repository. Opt-in, because it downloads rule packs from the Semgrep registry:
 *
 *   CODELENS_SEMGREP_IT=1 ./mvnw test -Dtest=SemgrepScannerRealToolTest
 */
@EnabledIfEnvironmentVariable(named = "CODELENS_SEMGREP_IT", matches = "1")
class SemgrepScannerRealToolTest {

    private static final String DAO = "src/main/java/Dao.java";

    @TempDir
    Path tmp;

    private Path origin;
    private com.codelens.git.GitProvider git;
    private SemgrepScanner scanner;

    @BeforeEach
    void setUp() throws Exception {
        origin = Files.createDirectories(tmp.resolve("owner/repo.git"));
        git("init", "-q");
        git("config", "uploadpack.allowAnySHA1InWant", "true");

        git = mock(com.codelens.git.GitProvider.class);
        GitProviderFactory providerFactory = mock(GitProviderFactory.class);
        when(providerFactory.getProvider(GitProvider.GITHUB)).thenReturn(git);
        when(git.getChangedFiles("owner", "repo", 7))
            .thenReturn(List.of(new ChangedFile(DAO, "modified", 1, 0, "")));
        scanner = new SemgrepScanner(providerFactory,
            new RepoWorkspaceFactory(tmp.toUri().toString(), "", 120), new ObjectMapper(),
            "semgrep", "p/java,p/owasp-top-ten", "ERROR", 280);
    }

    @Test
    void reportsOnlyTheInjectionThePrAddsDespiteSuppressionsInThePr() throws Exception {
        String base = commit(Map.of(DAO, dao(query("findOld", "users", ""))));
        String head = commit(Map.of(
            // new blank lines push the old finding down; the new one carries a nosemgrep comment
            DAO, dao("\n\n" + query("findOld", "users", "") + query("findNew", "orders", " // nosemgrep")),
            ".semgrepignore", "src/\n*.java\n"));

        ScanOutcome outcome = scan(base, head);

        assertFalse(outcome.passed(), "suppressions shipped in the PR must be ignored");
        assertEquals("1 new security finding in changed files", outcome.summary());
        assertTrue(outcome.details().contains("formatted-sql-string"), outcome.details());
        assertTrue(outcome.details().contains("`src/main/java/Dao.java:14`"), outcome.details());
        assertTrue(outcome.details().contains("1 other findings in these files were already present"),
            outcome.details());
    }

    @Test
    void harmlessChangeToAFileWithAnExistingFindingPasses() throws Exception {
        String base = commit(Map.of(DAO, dao(query("findOld", "users", ""))));
        String head = commit(Map.of(DAO, dao("  // reformatted\n\n" + query("findOld", "users", "")
            + "  public int count() { return 0; }\n")));

        ScanOutcome outcome = scan(base, head);

        assertTrue(outcome.passed(), String.valueOf(outcome.details()));
        assertEquals("No new findings in 1 changed file (1 already on base branch)", outcome.summary());
    }

    private ScanOutcome scan(String base, String head) throws Exception {
        when(git.getMergeBase("owner", "repo", base, head)).thenReturn(base);
        return scanner.scan(new ScanContext(GitProvider.GITHUB, "owner", "repo", 7, head, base));
    }

    /** A method with a classic SQL injection: request parameter concatenated into a query. */
    private static String query(String method, String table, String trailingComment) {
        return """
              public ResultSet %s(Connection c, HttpServletRequest req) throws Exception {
                String name = req.getParameter("name");
                Statement st = c.createStatement();
                return st.executeQuery("SELECT * FROM %s WHERE name = '" + name + "'");%s
              }
            """.formatted(method, table, trailingComment);
    }

    private static String dao(String body) {
        return "import java.sql.*;\nimport javax.servlet.http.HttpServletRequest;\npublic class Dao {\n" + body + "}\n";
    }

    private String commit(Map<String, String> files) throws Exception {
        for (Map.Entry<String, String> file : files.entrySet()) {
            Path path = origin.resolve(file.getKey());
            Files.createDirectories(path.getParent());
            Files.writeString(path, file.getValue());
        }
        git("add", ".");
        git("-c", "user.name=t", "-c", "user.email=t@example.com", "commit", "-q", "-m", "change");
        return git("rev-parse", "HEAD").strip();
    }

    private String git(String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "-C", origin.toString()));
        command.addAll(List.of(args));
        ProcessRunner.Result result = ProcessRunner.run(command, null, Map.of(), 60);
        assertEquals(0, result.exitCode(), result.output());
        return result.output();
    }
}
