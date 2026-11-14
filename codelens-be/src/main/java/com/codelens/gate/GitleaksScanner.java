package com.codelens.gate;

import com.codelens.gate.RepoWorkspaceFactory.Workspace;
import com.codelens.git.GitProviderFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Secrets check, backed by Gitleaks.
 *
 * It scans the diff of every commit the PR adds, not just the final state, so a key
 * that was committed and then deleted in a later commit is still reported: it stays
 * in git history and must be rotated.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "codelens.security-gate.gitleaks.enabled", havingValue = "true", matchIfMissing = true)
public class GitleaksScanner implements SecurityScanner {

    private static final int MAX_ROWS = 20;
    private static final Pattern ERROR_LINE = Pattern.compile("(?m)^\\S+ (ERR|FTL) ");

    private final GitProviderFactory gitProviderFactory;
    private final RepoWorkspaceFactory workspaceFactory;
    private final ObjectMapper objectMapper;
    private final String gitleaksPath;
    private final int timeoutSeconds;

    public GitleaksScanner(
            GitProviderFactory gitProviderFactory,
            RepoWorkspaceFactory workspaceFactory,
            ObjectMapper objectMapper,
            @Value("${codelens.security-gate.gitleaks.path:gitleaks}") String gitleaksPath,
            @Value("${codelens.security-gate.timeout-seconds:300}") int timeoutSeconds) {
        this.gitProviderFactory = gitProviderFactory;
        this.workspaceFactory = workspaceFactory;
        this.objectMapper = objectMapper;
        this.gitleaksPath = gitleaksPath;
        this.timeoutSeconds = timeoutSeconds;
    }

    @Override
    public GateCheck check() {
        return GateCheck.SECRETS;
    }

    @Override
    public ScanOutcome scan(ScanContext context) throws Exception {
        List<String> commits = gitProviderFactory.getProvider(context.provider())
            .getPullRequestCommits(context.owner(), context.repo(), context.prNumber());
        if (commits.isEmpty()) {
            return ScanOutcome.pass("No commits to scan");
        }
        for (String sha : commits) {
            // They are spliced into git log options below
            if (!RepoWorkspaceFactory.isValidSha(sha)) {
                throw new IllegalStateException("Unexpected commit id in PR commit list");
            }
        }

        Path scratch = Files.createTempDirectory("codelens-gitleaks-");
        // Every PR commit is within commits.size() steps of the head, so one more level
        // of history gives each of them its parent to diff against.
        // History only, no files on disk: gitleaks honours a .gitleaksignore found in the
        // working tree even when --gitleaks-ignore-path points elsewhere.
        try (Workspace workspace = workspaceFactory.fetchHistory(
                context.provider(), context.owner(), context.repo(), context.headSha(), commits.size() + 1)) {

            // gitleaks exits 0 with an empty report when its `git log` fails, so make sure
            // every commit we are about to ask for is really there.
            List<String> verify = new ArrayList<>(List.of(
                "git", "-C", workspace.repoDir().toString(), "rev-list", "--no-walk", "--quiet"));
            verify.addAll(commits);
            ProcessRunner.Result present = ProcessRunner.run(verify, null, Map.of(), timeoutSeconds);
            if (present.exitCode() != 0) {
                throw new IOException("PR commits missing from the fetched history: " + present.output().strip());
            }

            // Config and ignore list are pinned to ours. Otherwise the PR could ship its own
            // .gitleaks.toml / .gitleaksignore / "gitleaks:allow" comment and wave itself through.
            Path config = Files.writeString(scratch.resolve("gitleaks.toml"), "[extend]\nuseDefault = true\n");
            Path emptyIgnoreDir = Files.createDirectory(scratch.resolve("ignore"));
            Path report = scratch.resolve("report.json");

            List<String> command = List.of(
                gitleaksPath, "git",
                "--no-banner", "--no-color",
                "--redact",
                "--exit-code", "0",
                "--ignore-gitleaks-allow",
                "--config", config.toString(),
                "--gitleaks-ignore-path", emptyIgnoreDir.toString(),
                "--report-format", "json",
                "--report-path", report.toString(),
                "--log-opts", "--no-walk " + String.join(" ", commits),
                workspace.repoDir().toString());

            ProcessRunner.Result result = ProcessRunner.run(command, null, Map.of(), timeoutSeconds);
            if (result.exitCode() != 0) {
                throw new IOException("gitleaks failed (exit " + result.exitCode() + "): " + result.output().strip());
            }
            // Same fail-open behaviour as above: errors are logged, not reflected in the exit code
            if (ERROR_LINE.matcher(result.output()).find()) {
                throw new IOException("gitleaks reported an error: " + result.output().strip());
            }
            if (!Files.exists(report)) {
                throw new IOException("gitleaks produced no report");
            }

            JsonNode findings = objectMapper.readTree(report.toFile());
            if (findings == null || !findings.isArray()) {
                throw new IOException("gitleaks report is not a JSON array");
            }
            if (findings.isEmpty()) {
                return ScanOutcome.pass("No secrets in " + plural(commits.size(), "commit"));
            }
            log.warn("Gitleaks found {} potential secrets in {}/{} #{}", findings.size(),
                context.owner(), context.repo(), context.prNumber());
            return ScanOutcome.fail(
                plural(findings.size(), "potential secret") + " in " + plural(commits.size(), "commit"),
                formatDetails(findings));
        } finally {
            ProcessRunner.deleteRecursively(scratch);
        }
    }

    /** Markdown for the PR comment. Only rule, location and commit: never the matched text. */
    private String formatDetails(JsonNode findings) {
        StringBuilder md = new StringBuilder();
        md.append("## :key: CodeLens security gate: possible leaked secrets\n\n");
        md.append("| Rule | File | Line | Commit |\n|---|---|---|---|\n");
        int rows = 0;
        for (JsonNode finding : findings) {
            if (rows++ == MAX_ROWS) {
                md.append("\n…and ").append(findings.size() - MAX_ROWS).append(" more.\n");
                break;
            }
            String commit = finding.path("Commit").asText("");
            md.append("| ").append(cell(finding.path("RuleID").asText("unknown")))
              .append(" | `").append(cell(finding.path("File").asText("?"))).append('`')
              .append(" | ").append(finding.path("StartLine").asInt())
              .append(" | ").append(cell(commit.length() > 8 ? commit.substring(0, 8) : commit))
              .append(" |\n");
        }
        md.append("\nIf any of these is a real credential, **revoke and rotate it now**. ")
          .append("Deleting it in a new commit is not enough: it stays in the branch history, ")
          .append("so the commit has to be rewritten as well.\n");
        return md.toString();
    }

    private static String cell(String value) {
        return GateMarkdown.cell(value);
    }

    private static String plural(int count, String noun) {
        return count + " " + noun + (count == 1 ? "" : "s");
    }
}
