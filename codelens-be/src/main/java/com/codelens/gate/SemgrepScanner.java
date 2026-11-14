package com.codelens.gate;

import com.codelens.gate.RepoWorkspaceFactory.Workspace;
import com.codelens.git.GitProvider.ChangedFile;
import com.codelens.git.GitProviderFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Static security analysis check, backed by Semgrep (or the CLI-compatible Opengrep).
 *
 * Scans the files the PR changes, at the PR head and at the commit the PR branched
 * from, and fails only on findings the PR introduces. A finding is "the same" when the
 * rule, the file and the matched source text are the same, so moving code up or down
 * in a file does not make an old finding new.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "codelens.security-gate.semgrep.enabled", havingValue = "true", matchIfMissing = true)
public class SemgrepScanner implements SecurityScanner {

    private static final Set<String> SOURCE_EXTENSIONS = Set.of(
        "java", "kt", "scala", "js", "jsx", "ts", "tsx", "py", "go", "rb", "php", "cs", "rs",
        "xml", "yml", "yaml", "properties", "json", "tf", "sh");
    private static final Set<String> KNOWN_SEVERITIES =
        Set.of("INFO", "WARNING", "ERROR", "LOW", "MEDIUM", "HIGH", "CRITICAL");
    private static final int MAX_TARGETS = 1000;
    private static final int MAX_ROWS = 25;

    private final GitProviderFactory gitProviderFactory;
    private final RepoWorkspaceFactory workspaceFactory;
    private final ObjectMapper objectMapper;
    private final String semgrepPath;
    private final List<String> rules;
    private final List<String> severities;
    private final int timeoutSeconds;

    public SemgrepScanner(
            GitProviderFactory gitProviderFactory,
            RepoWorkspaceFactory workspaceFactory,
            ObjectMapper objectMapper,
            @Value("${codelens.security-gate.semgrep.path:semgrep}") String semgrepPath,
            @Value("${codelens.security-gate.semgrep.rules:p/java,p/owasp-top-ten}") String rules,
            @Value("${codelens.security-gate.semgrep.severities:ERROR}") String severities,
            @Value("${codelens.security-gate.timeout-seconds:300}") int timeoutSeconds) {
        this.gitProviderFactory = gitProviderFactory;
        this.workspaceFactory = workspaceFactory;
        this.objectMapper = objectMapper;
        this.semgrepPath = semgrepPath;
        this.rules = splitList(rules);
        this.severities = splitList(severities).stream().map(String::toUpperCase).toList();
        this.timeoutSeconds = timeoutSeconds;
        // Typos here would otherwise mean "scan with nothing" or "gate on nothing"
        if (this.rules.isEmpty() || this.rules.stream().anyMatch(rule -> rule.startsWith("-"))) {
            throw new IllegalArgumentException("codelens.security-gate.semgrep.rules is empty or invalid: " + rules);
        }
        if (this.severities.isEmpty() || !KNOWN_SEVERITIES.containsAll(this.severities)) {
            throw new IllegalArgumentException(
                "codelens.security-gate.semgrep.severities must list " + KNOWN_SEVERITIES + ", got: " + severities);
        }
    }

    private static List<String> splitList(String raw) {
        return Arrays.stream(raw.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    @Override
    public GateCheck check() {
        return GateCheck.SAST;
    }

    @Override
    public ScanOutcome scan(ScanContext context) throws Exception {
        com.codelens.git.GitProvider git = gitProviderFactory.getProvider(context.provider());

        List<String> changed = git.getChangedFiles(context.owner(), context.repo(), context.prNumber())
            .stream()
            .filter(file -> !"removed".equals(file.status()))
            .map(ChangedFile::filename)
            .filter(SemgrepScanner::isSource)
            .toList();
        if (changed.isEmpty()) {
            return ScanOutcome.pass("No source files changed");
        }
        if (changed.size() > MAX_TARGETS) {
            throw new IOException("PR changes " + changed.size() + " source files, more than the "
                + MAX_TARGETS + " the SAST check scans");
        }

        String mergeBase = git.getMergeBase(context.owner(), context.repo(), context.baseSha(), context.headSha());

        List<Finding> head = scanCommit(context, context.headSha(), changed);
        List<Finding> base = scanCommit(context, mergeBase, changed);

        // Count how often each identical finding existed before; anything beyond that is new
        Map<String, Integer> before = new HashMap<>();
        base.forEach(finding -> before.merge(finding.identity(), 1, Integer::sum));
        List<Finding> introduced = new ArrayList<>();
        for (Finding finding : head) {
            if (before.merge(finding.identity(), -1, Integer::sum) < 0) {
                introduced.add(finding);
            }
        }
        introduced.sort(Comparator.comparing(Finding::path).thenComparingInt(Finding::line));

        int existing = head.size() - introduced.size();
        if (introduced.isEmpty()) {
            return ScanOutcome.pass("No new findings in " + GateMarkdown.plural(changed.size(), "changed file")
                + (existing > 0 ? " (" + existing + " already on base branch)" : ""));
        }
        log.warn("Semgrep found {} new findings in {}/{} #{}", introduced.size(),
            context.owner(), context.repo(), context.prNumber());
        return ScanOutcome.fail(
            GateMarkdown.plural(introduced.size(), "new security finding") + " in changed files",
            formatDetails(introduced, existing));
    }

    static boolean isSource(String path) {
        int dot = path.lastIndexOf('.');
        return dot > path.lastIndexOf('/') && SOURCE_EXTENSIONS.contains(path.substring(dot + 1).toLowerCase());
    }

    private List<Finding> scanCommit(ScanContext context, String sha, List<String> changedPaths) throws Exception {
        Path scratch = Files.createTempDirectory("codelens-semgrep-");
        try (Workspace workspace = workspaceFactory.checkout(
                context.provider(), context.owner(), context.repo(), sha, 1)) {

            Path repoDir = workspace.repoDir().toRealPath();
            removeIgnoreFiles(repoDir);

            // Absolute paths: a file name from the PR can then never be read as an option
            List<String> targets = new ArrayList<>();
            for (String changedPath : changedPaths) {
                Path target = repoDir.resolve(changedPath).normalize();
                if (target.startsWith(repoDir) && Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                    targets.add(target.toString());
                }
            }
            if (targets.isEmpty()) {
                return List.of(); // e.g. every changed file is new, so none exist at the merge base
            }

            Path report = scratch.resolve("report.json");
            List<String> command = new ArrayList<>(List.of(semgrepPath, "scan"));
            for (String rule : rules) {
                command.addAll(List.of("--config", rule));
            }
            for (String severity : severities) {
                command.addAll(List.of("--severity", severity));
            }
            command.addAll(List.of(
                "--json", "--output", report.toString(),
                "--metrics=off",
                "--disable-version-check",
                "--disable-nosem",        // "// nosemgrep" comments in the PR do not count
                "--no-git-ignore",
                "--quiet"));
            command.addAll(targets);

            // Runs in the scratch dir so nothing in the repo is picked up as tool configuration
            ProcessRunner.Result result = ProcessRunner.run(command, scratch, Map.of(), timeoutSeconds);
            if (result.exitCode() != 0) {
                throw new IOException("semgrep failed (exit " + result.exitCode() + "): " + result.output().strip());
            }
            if (!Files.exists(report)) {
                throw new IOException("semgrep produced no report");
            }
            JsonNode root = objectMapper.readTree(report.toFile());
            if (root == null || !root.path("results").isArray()) {
                throw new IOException("semgrep report has an unexpected shape");
            }
            for (JsonNode error : root.path("errors")) {
                // "warn" covers things like one file that could not be fully parsed
                if ("error".equalsIgnoreCase(error.path("level").asText())) {
                    throw new IOException("semgrep reported an error: " + error.path("message").asText(error.toString()));
                }
            }

            List<Finding> findings = new ArrayList<>();
            Map<Path, List<String>> sourceCache = new HashMap<>();
            for (JsonNode node : root.path("results")) {
                Path file = Path.of(node.path("path").asText("")).normalize();
                if (!file.isAbsolute()) {
                    file = scratch.resolve(file).normalize();
                }
                if (!file.startsWith(repoDir)) {
                    throw new IOException("semgrep reported a finding outside the checkout");
                }
                int startLine = node.path("start").path("line").asInt();
                int endLine = Math.max(startLine, node.path("end").path("line").asInt(startLine));
                findings.add(new Finding(
                    node.path("check_id").asText("unknown"),
                    repoDir.relativize(file).toString(),
                    startLine,
                    node.path("extra").path("severity").asText(""),
                    node.path("extra").path("message").asText(""),
                    matchedText(sourceCache, file, startLine, endLine)));
            }
            return findings;
        } finally {
            ProcessRunner.deleteRecursively(scratch);
        }
    }

    /** A .semgrepignore shipped in the PR must not be able to hide files from the scan. */
    private static void removeIgnoreFiles(Path repoDir) throws IOException {
        List<Path> ignoreFiles;
        try (Stream<Path> files = Files.walk(repoDir)) {
            ignoreFiles = files
                .filter(path -> path.getFileName() != null && path.getFileName().toString().equals(".semgrepignore"))
                .toList();
        }
        for (Path ignoreFile : ignoreFiles) {
            Files.deleteIfExists(ignoreFile);
        }
    }

    /** The matched lines with whitespace removed: stable when code is moved or re-indented. */
    private static String matchedText(Map<Path, List<String>> cache, Path file, int startLine, int endLine) {
        List<String> lines = cache.computeIfAbsent(file, path -> {
            try {
                return Files.readAllLines(path, StandardCharsets.UTF_8);
            } catch (IOException | java.io.UncheckedIOException e) {
                return List.of(); // not UTF-8: fall back to rule + file only
            }
        });
        StringBuilder text = new StringBuilder();
        for (int line = Math.max(1, startLine); line <= Math.min(endLine, lines.size()); line++) {
            text.append(lines.get(line - 1).replaceAll("\\s+", ""));
        }
        return text.toString();
    }

    private String formatDetails(List<Finding> introduced, int existing) {
        StringBuilder md = new StringBuilder();
        md.append("## :mag: CodeLens security gate: new static analysis findings\n\n");
        md.append("| Severity | Rule | Location | What |\n|---|---|---|---|\n");
        int rows = 0;
        for (Finding finding : introduced) {
            if (rows++ == MAX_ROWS) {
                md.append("\n…and ").append(introduced.size() - MAX_ROWS).append(" more.\n");
                break;
            }
            String rule = finding.rule().substring(finding.rule().lastIndexOf('.') + 1);
            md.append("| ").append(GateMarkdown.cell(finding.severity()))
              .append(" | ").append(GateMarkdown.cell(rule))
              .append(" | `").append(GateMarkdown.cell(finding.path())).append(':').append(finding.line()).append('`')
              .append(" | ").append(GateMarkdown.cell(finding.message()))
              .append(" |\n");
        }
        if (existing > 0) {
            md.append("\n(").append(existing)
              .append(" other findings in these files were already present before this PR and are not counted.)\n");
        }
        return md.toString();
    }

    record Finding(String rule, String path, int line, String severity, String message, String matchedText) {
        String identity() {
            return rule + "\n" + path + "\n" + matchedText;
        }
    }
}
