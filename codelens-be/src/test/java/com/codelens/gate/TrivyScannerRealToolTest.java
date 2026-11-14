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
 * End to end with the real git and the real trivy binary against a local origin
 * repository. Opt-in, because it needs network access (Trivy's vulnerability database,
 * about 1.6 GB on disk, and Maven Central):
 *
 *   CODELENS_TRIVY_IT=1 ./mvnw test -Dtest=TrivyScannerRealToolTest
 */
@EnabledIfEnvironmentVariable(named = "CODELENS_TRIVY_IT", matches = "1")
class TrivyScannerRealToolTest {

    private static final String LOG4J_VULNERABLE = dependency("org.apache.logging.log4j", "log4j-core", "2.14.1");
    private static final String COMMONS_TEXT_VULNERABLE = dependency("org.apache.commons", "commons-text", "1.9");

    @TempDir
    Path tmp;

    private Path origin;
    private com.codelens.git.GitProvider git;
    private TrivyScanner scanner;

    @BeforeEach
    void setUp() throws Exception {
        origin = Files.createDirectories(tmp.resolve("owner/repo.git"));
        git("init", "-q");
        git("config", "uploadpack.allowAnySHA1InWant", "true");

        git = mock(com.codelens.git.GitProvider.class);
        GitProviderFactory providerFactory = mock(GitProviderFactory.class);
        when(providerFactory.getProvider(GitProvider.GITHUB)).thenReturn(git);
        when(git.getChangedFiles("owner", "repo", 7))
            .thenReturn(List.of(new ChangedFile("pom.xml", "modified", 1, 0, "")));
        scanner = new TrivyScanner(providerFactory,
            new RepoWorkspaceFactory(tmp.toUri().toString(), "", 120), new ObjectMapper(),
            "trivy", "HIGH,CRITICAL", false, "repo.maven.apache.org", 280);
    }

    @Test
    void reportsOnlyTheVulnerableDependencyThePrAddsDespiteSuppressionFilesInThePr() throws Exception {
        String base = commit(Map.of("pom.xml", pom(COMMONS_TEXT_VULNERABLE)));
        String head = commit(Map.of(
            "pom.xml", pom(COMMONS_TEXT_VULNERABLE + LOG4J_VULNERABLE),
            ".trivyignore", "CVE-2021-44228\nCVE-2021-45046\nCVE-2021-45105\nCVE-2021-44832\n",
            "trivy.yaml", "severity:\n  - LOW\n"));

        ScanOutcome outcome = scan(base, head);

        assertFalse(outcome.passed(), "suppression files shipped in the PR must be ignored");
        assertTrue(outcome.details().contains("CVE-2021-44228"), outcome.details());
        assertTrue(outcome.details().contains("org.apache.logging.log4j:log4j-core"), outcome.details());
        assertFalse(outcome.details().contains("commons-text |"), "already on the base branch: " + outcome.details());
        assertTrue(outcome.summary().contains("new HIGH/CRITICAL"), outcome.summary());
    }

    @Test
    void harmlessPomChangePassesEvenWithExistingVulnerabilities() throws Exception {
        String base = commit(Map.of("pom.xml", pom(COMMONS_TEXT_VULNERABLE)));
        String head = commit(Map.of("pom.xml", pom(COMMONS_TEXT_VULNERABLE + dependency("org.slf4j", "slf4j-api", "2.0.13"))));

        ScanOutcome outcome = scan(base, head);

        assertTrue(outcome.passed(), String.valueOf(outcome.details()));
        assertTrue(outcome.summary().startsWith("No new vulnerable dependencies ("), outcome.summary());
    }

    private ScanOutcome scan(String base, String head) throws Exception {
        when(git.getMergeBase("owner", "repo", base, head)).thenReturn(base);
        return scanner.scan(new ScanContext(GitProvider.GITHUB, "owner", "repo", 7, head, base));
    }

    private static String dependency(String group, String artifact, String version) {
        return "<dependency><groupId>%s</groupId><artifactId>%s</artifactId><version>%s</version></dependency>"
            .formatted(group, artifact, version);
    }

    private static String pom(String dependencies) {
        return """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>com.example</groupId><artifactId>demo</artifactId><version>1.0</version>
              <dependencies>%s</dependencies>
            </project>
            """.formatted(dependencies);
    }

    private String commit(Map<String, String> files) throws Exception {
        for (Map.Entry<String, String> file : files.entrySet()) {
            Files.writeString(origin.resolve(file.getKey()), file.getValue());
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
