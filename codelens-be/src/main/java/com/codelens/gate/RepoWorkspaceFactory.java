package com.codelens.gate;

import com.codelens.model.entity.Repository.GitProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Checks out a PR commit into a throwaway directory so directory-based scanners
 * (Gitleaks, Trivy, Semgrep) can run on it.
 *
 * The checked-out code is untrusted. Nothing in it is ever executed: no build, no
 * hooks, no submodules, no LFS downloads.
 */
@Slf4j
@Component
public class RepoWorkspaceFactory {

    private static final Pattern SHA = Pattern.compile("[0-9a-fA-F]{7,64}");
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9._-]*");
    private static final String TOKEN_ENV = "CODELENS_GIT_TOKEN";

    private final String cloneBaseUrl;
    private final String githubToken;
    private final int timeoutSeconds;

    public RepoWorkspaceFactory(
            @Value("${codelens.security-gate.clone-base-url:https://github.com}") String cloneBaseUrl,
            @Value("${GITHUB_TOKEN:}") String githubToken,
            @Value("${codelens.security-gate.timeout-seconds:300}") int timeoutSeconds) {
        this.cloneBaseUrl = cloneBaseUrl.replaceAll("/+$", "");
        this.githubToken = githubToken;
        this.timeoutSeconds = timeoutSeconds;
    }

    public static boolean isValidSha(String sha) {
        return sha != null && SHA.matcher(sha).matches();
    }

    /**
     * Fetch {@code sha} with {@code depth} commits of history and check it out.
     * The caller must close the returned workspace.
     */
    public Workspace checkout(GitProvider provider, String owner, String repo, String sha, int depth)
            throws IOException, InterruptedException {
        return prepare(provider, owner, repo, sha, depth, true);
    }

    /**
     * Fetch {@code sha} with {@code depth} commits of history but leave the working tree
     * empty. For scanners that read git history only: with no files on disk, nothing the
     * PR ships (tool config, ignore lists) can influence the scanner.
     */
    public Workspace fetchHistory(GitProvider provider, String owner, String repo, String sha, int depth)
            throws IOException, InterruptedException {
        return prepare(provider, owner, repo, sha, depth, false);
    }

    private Workspace prepare(GitProvider provider, String owner, String repo, String sha, int depth,
                              boolean checkoutFiles) throws IOException, InterruptedException {
        if (provider != GitProvider.GITHUB) {
            throw new UnsupportedOperationException("Workspace checkout is only implemented for GitHub");
        }
        // These values come from webhooks and end up in a git command line
        if (!NAME.matcher(owner).matches() || !NAME.matcher(repo).matches() || !isValidSha(sha)) {
            throw new IllegalArgumentException("Refusing to check out unexpected owner/repo/sha");
        }
        if (depth < 1) {
            throw new IllegalArgumentException("depth must be >= 1");
        }

        Path root = Files.createTempDirectory("codelens-ws-");
        try {
            Path repoDir = Files.createDirectory(root.resolve("repo"));
            Map<String, String> env = baseEnv();
            String url = cloneBaseUrl + "/" + owner + "/" + repo + ".git";

            if (githubToken != null && !githubToken.isBlank() && url.startsWith("https://")) {
                // The token is handed to git through an askpass helper reading an env var,
                // so it is never in argv (visible in `ps`) and never written to .git/config.
                Path askpass = root.resolve("askpass.sh");
                Files.writeString(askpass, "#!/bin/sh\nprintf '%s\\n' \"$" + TOKEN_ENV + "\"\n");
                Files.setPosixFilePermissions(askpass, PosixFilePermissions.fromString("rwx------"));
                env.put("GIT_ASKPASS", askpass.toString());
                env.put(TOKEN_ENV, githubToken);
                url = url.replaceFirst("^https://", "https://x-access-token@");
            }

            git(repoDir, env, "init", "-q");
            git(repoDir, env, "remote", "add", "origin", url);
            git(repoDir, env, "fetch", "-q", "--no-tags", "--depth=" + depth, "origin", sha);
            if (checkoutFiles) {
                git(repoDir, env, "-c", "core.hooksPath=/dev/null", "-c", "core.symlinks=false",
                    "checkout", "-q", "--detach", sha);
            }

            log.info("{} {}/{}@{} (depth {})", checkoutFiles ? "Checked out" : "Fetched", owner, repo, sha, depth);
            return new Workspace(root, repoDir);
        } catch (IOException | InterruptedException | RuntimeException e) {
            ProcessRunner.deleteRecursively(root);
            throw e;
        }
    }

    private Map<String, String> baseEnv() {
        Map<String, String> env = new HashMap<>();
        env.put("GIT_TERMINAL_PROMPT", "0");
        env.put("GIT_CONFIG_NOSYSTEM", "1");
        env.put("GIT_CONFIG_GLOBAL", "/dev/null");
        env.put("GIT_LFS_SKIP_SMUDGE", "1");
        return env;
    }

    private void git(Path repoDir, Map<String, String> env, String... args)
            throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("git", "-C", repoDir.toString()));
        command.addAll(List.of(args));
        ProcessRunner.Result result = ProcessRunner.run(command, null, env, timeoutSeconds);
        if (result.exitCode() != 0) {
            // args never contain the token; git output may echo the URL, which has no secret in it
            throw new IOException("git " + args[0] + " failed (exit " + result.exitCode() + "): "
                + result.output().strip());
        }
    }

    /** A checked-out commit. Deleted from disk on close. */
    public static class Workspace implements AutoCloseable {
        private final Path root;
        private final Path repoDir;

        public Workspace(Path root, Path repoDir) {
            this.root = root;
            this.repoDir = repoDir;
        }

        /** The git working tree. */
        public Path repoDir() {
            return repoDir;
        }

        @Override
        public void close() {
            ProcessRunner.deleteRecursively(root);
        }
    }
}
