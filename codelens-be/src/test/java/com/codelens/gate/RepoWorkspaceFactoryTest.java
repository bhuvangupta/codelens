package com.codelens.gate;

import com.codelens.gate.RepoWorkspaceFactory.Workspace;
import com.codelens.model.entity.Repository.GitProvider;
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

/** Runs real git against a local origin repository. */
class RepoWorkspaceFactoryTest {

    @TempDir
    Path tmp;

    private Path origin;
    private RepoWorkspaceFactory factory;
    private final List<String> shas = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        origin = Files.createDirectories(tmp.resolve("owner/repo.git"));
        git("init", "-q");
        // Lets a file:// origin serve a fetch by commit id, as GitHub does
        git("config", "uploadpack.allowAnySHA1InWant", "true");
        for (int i = 1; i <= 3; i++) {
            Files.writeString(origin.resolve("file" + i + ".txt"), "content " + i);
            git("add", ".");
            git("-c", "user.name=t", "-c", "user.email=t@example.com", "commit", "-q", "-m", "c" + i);
            shas.add(git("rev-parse", "HEAD").strip());
        }
        factory = new RepoWorkspaceFactory(tmp.toUri().toString(), "", 60);
    }

    @Test
    void checksOutTheRequestedCommitWithLimitedHistoryAndCleansUp() throws Exception {
        Path repoDir;
        try (Workspace workspace = factory.checkout(GitProvider.GITHUB, "owner", "repo", shas.get(2), 2)) {
            repoDir = workspace.repoDir();
            assertTrue(Files.exists(repoDir.resolve("file3.txt")));
            assertEquals(shas.get(2), gitIn(repoDir, "rev-parse", "HEAD").strip());
            assertEquals("2", gitIn(repoDir, "rev-list", "--count", "HEAD").strip());
        }
        assertFalse(Files.exists(repoDir), "workspace must be deleted on close");
    }

    @Test
    void checksOutAnOlderCommitThanTheBranchTip() throws Exception {
        try (Workspace workspace = factory.checkout(GitProvider.GITHUB, "owner", "repo", shas.get(0), 1)) {
            assertTrue(Files.exists(workspace.repoDir().resolve("file1.txt")));
            assertFalse(Files.exists(workspace.repoDir().resolve("file2.txt")));
        }
    }

    @Test
    void fetchHistoryHasTheCommitsButNoFilesOnDisk() throws Exception {
        try (Workspace workspace = factory.fetchHistory(GitProvider.GITHUB, "owner", "repo", shas.get(2), 2)) {
            assertEquals("2", gitIn(workspace.repoDir(), "rev-list", "--count", shas.get(2)).strip());
            assertFalse(Files.exists(workspace.repoDir().resolve("file3.txt")));
        }
    }

    @Test
    void rejectsValuesThatCouldBecomeGitOptions() {
        assertThrows(IllegalArgumentException.class,
            () -> factory.checkout(GitProvider.GITHUB, "owner", "repo", "--upload-pack=evil", 1));
        assertThrows(IllegalArgumentException.class,
            () -> factory.checkout(GitProvider.GITHUB, "-owner", "repo", shas.get(0), 1));
        assertThrows(IllegalArgumentException.class,
            () -> factory.checkout(GitProvider.GITHUB, "owner", "../repo", shas.get(0), 1));
    }

    @Test
    void failsLoudlyWhenTheCommitDoesNotExist() {
        assertThrows(java.io.IOException.class,
            () -> factory.checkout(GitProvider.GITHUB, "owner", "repo", "0".repeat(40), 1));
    }

    private String git(String... args) throws Exception {
        return gitIn(origin, args);
    }

    private static String gitIn(Path dir, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "-C", dir.toString()));
        command.addAll(List.of(args));
        ProcessRunner.Result result = ProcessRunner.run(command, null, Map.of(), 60);
        assertEquals(0, result.exitCode(), result.output());
        return result.output();
    }
}
