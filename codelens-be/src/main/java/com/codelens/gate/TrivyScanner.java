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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Dependency vulnerability check, backed by Trivy.
 *
 * Scans the dependency manifests (pom.xml and lockfiles) at the PR head and at the
 * commit the PR branched from, and fails only on vulnerabilities the PR introduces.
 * Vulnerabilities that were already there do not block unrelated work.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "codelens.security-gate.trivy.enabled", havingValue = "true", matchIfMissing = true)
public class TrivyScanner implements SecurityScanner {

    /** Files Trivy reads dependencies from. build.gradle is not one: Trivy needs gradle.lockfile. */
    private static final Set<String> MANIFESTS = Set.of(
        "pom.xml", "gradle.lockfile",
        "package-lock.json", "yarn.lock", "pnpm-lock.yaml",
        "requirements.txt", "Pipfile.lock", "poetry.lock", "uv.lock",
        "go.mod", "Cargo.lock");

    private static final Set<String> KNOWN_SEVERITIES = Set.of("LOW", "MEDIUM", "HIGH", "CRITICAL");
    private static final Pattern ERROR_LINE = Pattern.compile("(?m)^\\S+\\s+(ERROR|FATAL)\\s");
    private static final int MAX_ROWS = 25;

    private final GitProviderFactory gitProviderFactory;
    private final RepoWorkspaceFactory workspaceFactory;
    private final ObjectMapper objectMapper;
    private final String trivyPath;
    private final String severities;
    private final boolean offlineScan;
    private final Set<String> allowedRepositoryHosts;
    private final int timeoutSeconds;

    public TrivyScanner(
            GitProviderFactory gitProviderFactory,
            RepoWorkspaceFactory workspaceFactory,
            ObjectMapper objectMapper,
            @Value("${codelens.security-gate.trivy.path:trivy}") String trivyPath,
            @Value("${codelens.security-gate.trivy.severities:HIGH,CRITICAL}") String severities,
            @Value("${codelens.security-gate.trivy.offline-scan:false}") boolean offlineScan,
            @Value("${codelens.security-gate.trivy.allowed-repository-hosts:repo.maven.apache.org,repo1.maven.org}")
            String allowedRepositoryHosts,
            @Value("${codelens.security-gate.timeout-seconds:300}") int timeoutSeconds) {
        this.gitProviderFactory = gitProviderFactory;
        this.workspaceFactory = workspaceFactory;
        this.objectMapper = objectMapper;
        this.trivyPath = trivyPath;
        this.severities = normalizeSeverities(severities);
        this.offlineScan = offlineScan;
        this.allowedRepositoryHosts = Arrays.stream(allowedRepositoryHosts.split(","))
            .map(host -> host.trim().toLowerCase()).filter(host -> !host.isEmpty())
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
        this.timeoutSeconds = timeoutSeconds;
    }

    private static String normalizeSeverities(String raw) {
        List<String> parsed = Arrays.stream(raw.split(","))
            .map(s -> s.trim().toUpperCase()).filter(s -> !s.isEmpty()).toList();
        if (parsed.isEmpty() || !KNOWN_SEVERITIES.containsAll(parsed)) {
            // A typo here would otherwise mean "gate on nothing"
            throw new IllegalArgumentException(
                "codelens.security-gate.trivy.severities must list LOW/MEDIUM/HIGH/CRITICAL, got: " + raw);
        }
        return String.join(",", parsed);
    }

    @Override
    public GateCheck check() {
        return GateCheck.DEPS;
    }

    @Override
    public ScanOutcome scan(ScanContext context) throws Exception {
        com.codelens.git.GitProvider git = gitProviderFactory.getProvider(context.provider());

        List<String> changedManifests = git.getChangedFiles(context.owner(), context.repo(), context.prNumber())
            .stream().map(ChangedFile::filename).filter(TrivyScanner::isManifest).toList();
        if (changedManifests.isEmpty()) {
            return ScanOutcome.pass("No dependency files changed");
        }

        String mergeBase = git.getMergeBase(context.owner(), context.repo(), context.baseSha(), context.headSha());

        Map<String, Vulnerability> head;
        Map<String, Vulnerability> base;
        try {
            head = scanCommit(context, context.headSha());
            base = scanCommit(context, mergeBase);
        } catch (UntrustedRepositoryException e) {
            return ScanOutcome.fail("pom.xml uses a Maven repository that is not allowlisted",
                formatRepositoryDetails(e.violations));
        }

        List<Vulnerability> introduced = head.entrySet().stream()
            .filter(entry -> !base.containsKey(entry.getKey()))
            .map(Map.Entry::getValue)
            .sorted(Comparator.comparing((Vulnerability v) -> severityRank(v.severity()))
                .thenComparing(Vulnerability::pkg).thenComparing(Vulnerability::id))
            .toList();

        int existing = head.size() - introduced.size();
        if (introduced.isEmpty()) {
            return ScanOutcome.pass("No new vulnerable dependencies"
                + (existing > 0 ? " (" + existing + " already on base branch)" : ""));
        }
        log.warn("Trivy found {} new vulnerabilities in {}/{} #{}", introduced.size(),
            context.owner(), context.repo(), context.prNumber());
        return ScanOutcome.fail(
            introduced.size() + " new " + severities.replace(",", "/")
                + (introduced.size() == 1 ? " vulnerability" : " vulnerabilities") + " in dependencies",
            formatDetails(introduced, existing));
    }

    static boolean isManifest(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        return MANIFESTS.contains(name);
    }

    /**
     * @return vulnerabilities at {@code sha}, keyed by package + advisory id. File and
     *         version are left out of the key on purpose: moving a pom.xml, or bumping a
     *         package to another version with the same advisory, does not introduce it.
     */
    private Map<String, Vulnerability> scanCommit(ScanContext context, String sha) throws Exception {
        Path scratch = Files.createTempDirectory("codelens-trivy-");
        try (Workspace workspace = workspaceFactory.checkout(
                context.provider(), context.owner(), context.repo(), sha, 1)) {

            if (!offlineScan) {
                // Trivy would fetch from any repository URL the PR's pom.xml declares
                List<PomRepositoryGuard.Violation> violations =
                    PomRepositoryGuard.check(workspace.repoDir(), allowedRepositoryHosts);
                if (!violations.isEmpty()) {
                    throw new UntrustedRepositoryException(violations);
                }
            }

            Path report = scratch.resolve("report.json");
            // Trivy picks up trivy.yaml and .trivyignore from its working directory. It runs in
            // the scratch dir with an explicit empty ignore file, so the PR cannot supply either.
            Path emptyIgnore = Files.writeString(scratch.resolve("trivyignore"), "");

            List<String> command = new ArrayList<>(List.of(
                trivyPath, "fs",
                "--scanners", "vuln",
                "--severity", severities,
                "--format", "json",
                "--output", report.toString(),
                "--exit-code", "0",
                "--ignorefile", emptyIgnore.toString(),
                "--config", "",            // empty = do not load any trivy.yaml
                "--skip-version-check",
                "--disable-telemetry",
                "--skip-java-db-update",   // only needed to identify JAR files, not manifests
                "--no-progress"));
            if (offlineScan) {
                command.add("--offline-scan");
            }
            command.add(workspace.repoDir().toString());

            ProcessRunner.Result result = ProcessRunner.run(command, scratch, Map.of(), timeoutSeconds);
            if (result.exitCode() != 0) {
                throw new IOException("trivy failed (exit " + result.exitCode() + "): " + result.output().strip());
            }
            if (ERROR_LINE.matcher(result.output()).find()) {
                throw new IOException("trivy reported an error: " + result.output().strip());
            }
            if (!Files.exists(report)) {
                throw new IOException("trivy produced no report");
            }
            JsonNode root = objectMapper.readTree(report.toFile());
            if (root == null || !root.isObject() || !root.has("SchemaVersion")) {
                throw new IOException("trivy report has an unexpected shape");
            }

            Map<String, Vulnerability> found = new LinkedHashMap<>();
            for (JsonNode target : root.path("Results")) {
                String file = target.path("Target").asText("");
                for (JsonNode v : target.path("Vulnerabilities")) {
                    Vulnerability vulnerability = new Vulnerability(
                        v.path("VulnerabilityID").asText(""),
                        v.path("PkgName").asText(""),
                        v.path("InstalledVersion").asText(""),
                        v.path("FixedVersion").asText(""),
                        v.path("Severity").asText("UNKNOWN"),
                        file);
                    found.putIfAbsent(vulnerability.pkg() + "|" + vulnerability.id(), vulnerability);
                }
            }
            return found;
        } finally {
            ProcessRunner.deleteRecursively(scratch);
        }
    }

    private String formatDetails(List<Vulnerability> introduced, int existing) {
        StringBuilder md = new StringBuilder();
        md.append("## :package: CodeLens security gate: new vulnerable dependencies\n\n");
        md.append("This PR introduces dependencies with known vulnerabilities.\n\n");
        md.append("| Severity | Package | Installed | Fixed in | Advisory | File |\n|---|---|---|---|---|---|\n");
        int rows = 0;
        for (Vulnerability v : introduced) {
            if (rows++ == MAX_ROWS) {
                md.append("\n…and ").append(introduced.size() - MAX_ROWS).append(" more.\n");
                break;
            }
            md.append("| ").append(GateMarkdown.cell(v.severity()))
              .append(" | ").append(GateMarkdown.cell(v.pkg()))
              .append(" | ").append(GateMarkdown.cell(v.installed()))
              .append(" | ").append(v.fixed().isBlank() ? "no fix yet" : GateMarkdown.cell(v.fixed()))
              .append(" | ").append(GateMarkdown.cell(v.id()))
              .append(" | `").append(GateMarkdown.cell(v.file())).append("` |\n");
        }
        md.append("\nUpgrade to a fixed version where one exists.");
        if (existing > 0) {
            md.append(" (").append(existing)
              .append(" other vulnerabilities were already present before this PR and are not counted.)");
        }
        md.append('\n');
        return md.toString();
    }

    private String formatRepositoryDetails(List<PomRepositoryGuard.Violation> violations) {
        StringBuilder md = new StringBuilder();
        md.append("## :package: CodeLens security gate: dependency scan not run\n\n");
        md.append("The dependency scan downloads from the Maven repositories a `pom.xml` declares. ")
          .append("These are not on the CodeLens allowlist, so the scan was not run:\n\n");
        md.append("| File | Repository |\n|---|---|\n");
        violations.stream().limit(MAX_ROWS).forEach(v -> md
            .append("| `").append(GateMarkdown.cell(v.pomPath())).append("` | ")
            .append(GateMarkdown.cell(v.repositoryUrl().replaceFirst("^(https?)://", "$1_//"))).append(" |\n"));
        md.append("\nIf a repository is trusted, a CodeLens administrator can add its host to ")
          .append("`codelens.security-gate.trivy.allowed-repository-hosts`.\n");
        return md.toString();
    }

    private static class UntrustedRepositoryException extends Exception {
        private final List<PomRepositoryGuard.Violation> violations;

        UntrustedRepositoryException(List<PomRepositoryGuard.Violation> violations) {
            super("pom.xml declares repositories that are not allowlisted");
            this.violations = violations;
        }
    }

    private static int severityRank(String severity) {
        return switch (severity) {
            case "CRITICAL" -> 0;
            case "HIGH" -> 1;
            case "MEDIUM" -> 2;
            case "LOW" -> 3;
            default -> 4;
        };
    }

    record Vulnerability(String id, String pkg, String installed, String fixed, String severity, String file) {}
}
