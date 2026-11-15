package com.codelens.gate;

import com.codelens.gate.PomRepositoryGuard.Violation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PomRepositoryGuardTest {

    private static final Set<String> ALLOWED = Set.of("repo.maven.apache.org");

    @TempDir
    Path repo;

    @Test
    void pomWithoutRepositoriesIsFine() throws Exception {
        pom("pom.xml", "<project><dependencies/></project>");

        assertTrue(PomRepositoryGuard.check(repo, ALLOWED).isEmpty());
    }

    @Test
    void reportsUnlistedRepositoriesInAnyModuleProfileOrPluginSection() throws Exception {
        pom("pom.xml", """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <repositories>
                <repository><id>central</id><url>https://REPO.maven.apache.org/maven2</url></repository>
                <repository><id>a</id><url>http://127.0.0.1:8080/x</url></repository>
              </repositories>
              <profiles><profile><repositories>
                <repository><id>b</id><url>https://evil.example/m2</url></repository>
              </repositories></profile></profiles>
            </project>""");
        pom("module/pom.xml", """
            <project><pluginRepositories>
              <pluginRepository><id>c</id><url>${nexus.url}</url></pluginRepository>
            </pluginRepositories></project>""");

        List<Violation> violations = PomRepositoryGuard.check(repo, ALLOWED);

        assertEquals(List.of("${nexus.url}", "http://127.0.0.1:8080/x", "https://evil.example/m2"),
            violations.stream().map(Violation::repositoryUrl).toList());
        assertEquals("module/pom.xml", violations.get(0).pomPath());
    }

    @Test
    void ignoresPublishTargetsAndTheGitDirectory() throws Exception {
        pom("pom.xml", """
            <project><distributionManagement>
              <repository><id>releases</id><url>https://nexus.internal/releases</url></repository>
            </distributionManagement></project>""");
        pom(".git/pom.xml", "not xml at all");

        assertTrue(PomRepositoryGuard.check(repo, ALLOWED).isEmpty());
    }

    @Test
    void brokenPomIsAnError() throws Exception {
        pom("pom.xml", "<project><dependencies>");

        assertThrows(IOException.class, () -> PomRepositoryGuard.check(repo, ALLOWED));
    }

    @Test
    void doctypeAndExternalEntitiesAreRefused() throws Exception {
        pom("pom.xml", """
            <?xml version="1.0"?>
            <!DOCTYPE project [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
            <project><name>&xxe;</name></project>""");

        assertThrows(IOException.class, () -> PomRepositoryGuard.check(repo, ALLOWED));
    }

    private void pom(String path, String content) throws IOException {
        Path file = repo.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
