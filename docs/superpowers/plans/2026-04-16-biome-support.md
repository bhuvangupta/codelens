# Biome TypeScript Support Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement task-by-task.

**Goal:** Add Biome support for JS/TS files with detection via `package.json scripts.lint`, parallel execution alongside ESLint, security floor enforcement, and 10-minute subprocess timeouts across all shell-based analyzers.

**Architecture:** Mirrors the PMD/Ruff/Bandit pattern from the lint config pickup feature. New `BiomeAnalyzer` with `BiomeConfig` record + `analyzeWithConfig`. `LintConfigService` gains `detectLinters(packageJson)` that parses `scripts.lint` to decide which linters are enabled. `CombinedAnalysisService` gates ESLint vs Biome based on the detection result. Existing shell-based analyzers (ESLint/Ruff/Bandit) get 10-minute timeouts as a hardening pass.

---

## File Map

**New:**
- `codelens-be/src/main/java/com/codelens/analysis/javascript/BiomeAnalyzer.java`
- `codelens-be/src/main/resources/security-floor/biome-security-floor.json`

**Modified:**
- `codelens-be/src/main/java/com/codelens/analysis/LintConfigBundle.java` — add `biomeConfig` + `linterDetection` fields
- `codelens-be/src/main/java/com/codelens/analysis/LintConfigService.java` — add `fetchBiomeConfig` + `detectLinters`
- `codelens-be/src/main/java/com/codelens/analysis/CombinedAnalysisService.java` — inject Biome, gate ESLint/Biome by detection
- `codelens-be/src/main/java/com/codelens/analysis/javascript/EslintAnalyzer.java` — add 10-min timeout
- `codelens-be/src/main/java/com/codelens/analysis/python/RuffAnalyzer.java` — add 10-min timeout
- `codelens-be/src/main/java/com/codelens/analysis/python/BanditAnalyzer.java` — add 10-min timeout
- `codelens-be/README.md` — document Biome + detection

---

### Task 1: Biome Security Floor JSON

Create `codelens-be/src/main/resources/security-floor/biome-security-floor.json`:

```json
{
  "$schema": "https://biomejs.dev/schemas/1.9.4/schema.json",
  "linter": {
    "enabled": true,
    "rules": {
      "recommended": false,
      "security": {
        "noDangerouslySetInnerHtml": "error",
        "noDangerouslySetInnerHtmlWithChildren": "error",
        "noGlobalEval": "error"
      },
      "suspicious": {
        "noDebugger": "error",
        "noEmptyInterface": "error"
      }
    }
  }
}
```

Commit: `git commit -m "Add Biome security floor config"`

---

### Task 2: Create BiomeAnalyzer

Create `codelens-be/src/main/java/com/codelens/analysis/javascript/BiomeAnalyzer.java` mirroring `EslintAnalyzer` pattern with a 10-minute timeout. Pattern:

- `BiomeConfig` record (filename, content)
- `getConfigFileNames()` returns `["biome.json", "biome.jsonc"]`
- `analyzeWithConfig(filename, content, BiomeConfig)` — if config, two-pass (repo + security floor); else single pass with bundled floor only
- Invocation: `npx @biomejs/biome check --reporter=json --formatter-enabled=false <file>` from temp dir
- Parse JSON diagnostics array: `category`, `description`, `location.path.file`, `location.span.start` → line/column
- Map severity: `error` → HIGH, `warning` → MEDIUM, `information` → LOW
- Process timeout: `process.waitFor(10, TimeUnit.MINUTES)` + `destroyForcibly` on timeout
- Dedupe after two passes by `(file, line, ruleId)`

Key structure:

```java
package com.codelens.analysis.javascript;

import com.codelens.analysis.AnalysisIssue;
import com.codelens.analysis.StaticAnalyzer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Component
public class BiomeAnalyzer implements StaticAnalyzer {

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("js", "jsx", "ts", "tsx", "mjs", "cjs");
    private static final List<String> BIOME_CONFIG_FILES = List.of("biome.json", "biome.jsonc");
    private static final String SECURITY_FLOOR_RESOURCE = "security-floor/biome-security-floor.json";
    private static final long PROCESS_TIMEOUT_MINUTES = 10;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public record BiomeConfig(String configFilename, String configContent) {}

    public static List<String> getConfigFileNames() { return BIOME_CONFIG_FILES; }

    @Override public String getName() { return "biome"; }
    @Override public Set<String> getSupportedExtensions() { return SUPPORTED_EXTENSIONS; }

    @Override
    public List<AnalysisIssue> analyze(String filename, String content) {
        return analyzeWithConfig(filename, content, null);
    }

    public List<AnalysisIssue> analyzeWithConfig(String filename, String content, BiomeConfig config) {
        List<AnalysisIssue> issues = new ArrayList<>();
        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("biome-session-");
            String extension = getExtension(filename);
            Path sourceFile = tempDir.resolve("source." + extension);
            Files.writeString(sourceFile, content);

            if (config != null && config.configContent() != null && !config.configContent().isBlank()) {
                // Pass 1: project config
                try {
                    Path configPath = tempDir.resolve(config.configFilename());
                    Files.writeString(configPath, config.configContent());
                    issues.addAll(runBiome(sourceFile, filename, tempDir));
                    log.info("Biome: Using project config from {}", config.configFilename());
                } catch (Exception e) {
                    log.warn("Biome project config {} failed, falling back to default: {}",
                        config.configFilename(), e.getMessage());
                }
                // Pass 2: security floor — always runs
                try {
                    Path floorDir = Files.createTempDirectory("biome-floor-");
                    Path floorConfig = floorDir.resolve("biome.json");
                    Files.writeString(floorConfig, loadSecurityFloor());
                    Path floorSource = floorDir.resolve("source." + extension);
                    Files.writeString(floorSource, content);
                    try {
                        issues.addAll(runBiome(floorSource, filename, floorDir));
                    } finally {
                        deleteRecursively(floorDir);
                    }
                } catch (Exception e) {
                    log.warn("Biome security floor failed: {}", e.getMessage());
                }
                issues = deduplicateIssues(issues);
            } else {
                // No project config — use security floor only (as default)
                String floor = loadSecurityFloor();
                if (floor != null) {
                    Path configPath = tempDir.resolve("biome.json");
                    Files.writeString(configPath, floor);
                }
                try {
                    issues.addAll(runBiome(sourceFile, filename, tempDir));
                    log.debug("Biome: Using bundled CodeLens default");
                } catch (Exception e) {
                    log.warn("Biome default analysis failed: {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("Biome analysis failed for {}: {}", filename, e.getMessage());
        } finally {
            if (tempDir != null) {
                deleteRecursively(tempDir);
            }
        }
        return issues;
    }

    @Override
    public List<AnalysisIssue> analyzeFile(String filePath) {
        return runBiomeFromPath(filePath, filePath);
    }

    private List<AnalysisIssue> runBiomeFromPath(String actualPath, String reportedPath) {
        return runBiome(Path.of(actualPath), reportedPath, null);
    }

    private List<AnalysisIssue> runBiome(Path sourceFile, String reportedPath, Path workingDirectory) {
        List<AnalysisIssue> issues = new ArrayList<>();
        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(
                "npx", "@biomejs/biome", "check",
                "--reporter=json",
                "--formatter-enabled=false",
                sourceFile.toString()
            );
            pb.redirectErrorStream(true);
            if (workingDirectory != null) {
                pb.directory(workingDirectory.toFile());
            }
            process = pb.start();
            String output;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                output = reader.lines().collect(Collectors.joining("\n"));
            }
            boolean finished = process.waitFor(PROCESS_TIMEOUT_MINUTES, TimeUnit.MINUTES);
            if (!finished) {
                log.warn("Biome timed out after {} minutes for {}", PROCESS_TIMEOUT_MINUTES, reportedPath);
                process.destroyForcibly();
                return issues;
            }
            // Parse JSON diagnostics
            JsonNode root = objectMapper.readTree(output);
            JsonNode diagnostics = root.path("diagnostics");
            if (diagnostics.isArray()) {
                for (JsonNode diag : diagnostics) {
                    issues.add(parseDiagnostic(diag, reportedPath));
                }
            }
        } catch (IOException e) {
            log.warn("Biome I/O error: {}", e.getMessage());
        } catch (InterruptedException e) {
            log.warn("Biome execution interrupted: {}", e.getMessage());
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("Biome output parse error: {}", e.getMessage());
        } finally {
            if (process != null) {
                process.destroyForcibly();
            }
        }
        return issues;
    }

    private AnalysisIssue parseDiagnostic(JsonNode diag, String filePath) {
        String category = diag.path("category").asText("unknown");
        String severity = diag.path("severity").asText("warning");
        String message = diag.path("description").asText("");

        int line = 1, column = 1;
        JsonNode locationSpan = diag.path("location").path("span");
        if (locationSpan.isArray() && locationSpan.size() > 0) {
            // Biome gives byte offsets; default to line 1 when we can't map — the source
            // resolver in the LLM review prompt will surface the message with the file path.
            line = diag.path("location").path("sourceCode").path("line").asInt(1);
        } else if (locationSpan.has("start")) {
            line = locationSpan.path("start").asInt(1);
        }

        return AnalysisIssue.builder()
            .analyzer(getName())
            .ruleId(category)
            .severity(mapSeverity(severity, category))
            .category(categorizeRule(category))
            .message(message)
            .filePath(filePath)
            .line(line)
            .column(column)
            .build();
    }

    private AnalysisIssue.Severity mapSeverity(String severity, String category) {
        if (category.startsWith("lint/security/")) return AnalysisIssue.Severity.HIGH;
        return switch (severity.toLowerCase()) {
            case "error" -> AnalysisIssue.Severity.HIGH;
            case "warning" -> AnalysisIssue.Severity.MEDIUM;
            default -> AnalysisIssue.Severity.LOW;
        };
    }

    private String categorizeRule(String category) {
        if (category.startsWith("lint/security/")) return "Security";
        if (category.startsWith("lint/suspicious/")) return "Bug";
        if (category.startsWith("lint/correctness/")) return "Correctness";
        if (category.startsWith("lint/performance/")) return "Performance";
        if (category.startsWith("lint/complexity/")) return "Complexity";
        if (category.startsWith("lint/style/")) return "Style";
        return "Code Quality";
    }

    private String loadSecurityFloor() {
        try (InputStream is = new ClassPathResource(SECURITY_FLOOR_RESOURCE).getInputStream()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Failed to load Biome security floor: {}", e.getMessage());
            return null;
        }
    }

    private List<AnalysisIssue> deduplicateIssues(List<AnalysisIssue> issues) {
        Set<String> seen = new HashSet<>();
        List<AnalysisIssue> deduped = new ArrayList<>();
        for (AnalysisIssue issue : issues) {
            String key = issue.filePath() + ":" + issue.line() + ":" + issue.ruleId();
            if (seen.add(key)) deduped.add(issue);
        }
        return deduped;
    }

    private void deleteRecursively(Path dir) {
        try {
            Files.walk(dir)
                .sorted((a, b) -> b.compareTo(a))
                .forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                });
        } catch (IOException e) {
            log.debug("Failed to clean up Biome temp dir: {}", dir);
        }
    }

    private String getExtension(String filename) {
        int lastDot = filename.lastIndexOf('.');
        return lastDot > 0 ? filename.substring(lastDot + 1) : "ts";
    }

    @Override
    public boolean isAvailable() {
        try {
            ProcessBuilder pb = new ProcessBuilder("npx", "@biomejs/biome", "--version");
            Process process = pb.start();
            return process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
```

Verify: `./mvnw compile -pl . -q`. Commit: `git commit -m "Add BiomeAnalyzer with security floor and timeout"`

---

### Task 3: Update LintConfigBundle

Add `biomeConfig` and `linterDetection` fields:

```java
package com.codelens.analysis;

import com.codelens.analysis.java.CheckstyleAnalyzer.CheckstyleConfig;
import com.codelens.analysis.java.PmdAnalyzer.PmdConfig;
import com.codelens.analysis.javascript.BiomeAnalyzer.BiomeConfig;
import com.codelens.analysis.javascript.EslintAnalyzer.EslintConfig;
import com.codelens.analysis.python.BanditAnalyzer.BanditConfig;
import com.codelens.analysis.python.RuffAnalyzer.RuffConfig;

public record LintConfigBundle(
    EslintConfig eslintConfig,
    BiomeConfig biomeConfig,
    PmdConfig pmdConfig,
    CheckstyleConfig checkstyleConfig,
    RuffConfig ruffConfig,
    BanditConfig banditConfig,
    LinterDetection linterDetection
) {
    public static LintConfigBundle empty() {
        return new LintConfigBundle(null, null, null, null, null, null, LinterDetection.defaultFallback());
    }

    /**
     * Which JS/TS linters are enabled based on scripts.lint detection.
     * When fromScriptsLint is true, the booleans are authoritative.
     * When false, callers should fall back to config file presence.
     */
    public record LinterDetection(boolean eslintEnabled, boolean biomeEnabled, boolean fromScriptsLint) {
        public static LinterDetection defaultFallback() {
            return new LinterDetection(true, false, false);
        }
    }
}
```

Commit: `git commit -m "Add biomeConfig and linterDetection to LintConfigBundle"`

---

### Task 4: Update LintConfigService

Add `fetchBiomeConfig` (mirrors existing fetch methods) and `detectLinters` (parses `package.json scripts.lint`). Update `fetchAll` to include both.

```java
// New field
private final com.fasterxml.jackson.databind.ObjectMapper objectMapper =
    new com.fasterxml.jackson.databind.ObjectMapper();

// Update fetchAll signature
public LintConfigBundle fetchAll(GitProvider gitProvider, String owner, String repo, String commitSha) {
    return new LintConfigBundle(
        fetchEslintConfig(gitProvider, owner, repo, commitSha),
        fetchBiomeConfig(gitProvider, owner, repo, commitSha),
        fetchPmdConfig(gitProvider, owner, repo, commitSha),
        fetchCheckstyleConfig(gitProvider, owner, repo, commitSha),
        fetchRuffConfig(gitProvider, owner, repo, commitSha),
        fetchBanditConfig(gitProvider, owner, repo, commitSha),
        detectLinters(gitProvider, owner, repo, commitSha)
    );
}

// Add new private methods
private BiomeConfig fetchBiomeConfig(GitProvider gitProvider, String owner, String repo, String commitSha) {
    for (String configFilename : BiomeAnalyzer.getConfigFileNames()) {
        try {
            String content = gitProvider.getFileContent(owner, repo, configFilename, commitSha);
            if (content != null && !content.isBlank()) {
                log.debug("Found Biome config: {}", configFilename);
                return new BiomeConfig(configFilename, content);
            }
        } catch (Exception e) {
            log.trace("Biome config not found: {}", configFilename);
        }
    }
    return null;
}

/**
 * Parse package.json scripts.lint to determine which JS/TS linter(s) the repo actually uses.
 * Returns detection with fromScriptsLint=true only when a recognizable linter name is found.
 */
private LintConfigBundle.LinterDetection detectLinters(GitProvider gitProvider, String owner, String repo, String commitSha) {
    try {
        String packageJson = gitProvider.getFileContent(owner, repo, "package.json", commitSha);
        if (packageJson != null && !packageJson.isBlank()) {
            com.fasterxml.jackson.databind.JsonNode pkg = objectMapper.readTree(packageJson);
            String lintScript = pkg.path("scripts").path("lint").asText("").toLowerCase();
            if (!lintScript.isEmpty()) {
                boolean hasBiome = lintScript.contains("biome");
                boolean hasEslint = lintScript.contains("eslint");
                if (hasBiome || hasEslint) {
                    log.info("Detected linters from package.json scripts.lint: eslint={}, biome={}", hasEslint, hasBiome);
                    return new LintConfigBundle.LinterDetection(hasEslint, hasBiome, true);
                }
            }
        }
    } catch (Exception e) {
        log.trace("Could not detect linters from package.json: {}", e.getMessage());
    }
    return LintConfigBundle.LinterDetection.defaultFallback();
}
```

Add imports:
```java
import com.codelens.analysis.javascript.BiomeAnalyzer;
import com.codelens.analysis.javascript.BiomeAnalyzer.BiomeConfig;
```

Verify: `./mvnw compile -pl . -q`. Commit: `git commit -m "Add Biome config fetch and linter detection"`

---

### Task 5: Wire BiomeAnalyzer through CombinedAnalysisService

Inject `BiomeAnalyzer`, import `BiomeConfig`, and update both parallel and sequential JS/TS branches to gate by `linterDetection`.

Changes:

1. **Add field and import:**
```java
import com.codelens.analysis.javascript.BiomeAnalyzer;
import com.codelens.analysis.javascript.BiomeAnalyzer.BiomeConfig;

private final BiomeAnalyzer biomeAnalyzer;
```

2. **Add to constructor params and body** (insert after `eslintAnalyzer`):
```java
BiomeAnalyzer biomeAnalyzer,
...
this.biomeAnalyzer = biomeAnalyzer;
```

3. **Update `analyzeInParallelWithBundle` JS/TS branch** (replace existing ESLint block):
```java
if (languageDetector.isJavaScriptFamily(filename)) {
    LintConfigBundle.LinterDetection detection = bundle.linterDetection();
    boolean runEslint;
    boolean runBiome;
    if (detection.fromScriptsLint()) {
        runEslint = detection.eslintEnabled();
        runBiome = detection.biomeEnabled();
    } else {
        // Fallback: ESLint always on (current behavior), Biome on only if biome.json present
        runEslint = true;
        runBiome = bundle.biomeConfig() != null;
    }
    if (runEslint && eslintAnalyzer.isAvailable()) {
        EslintConfig eslintConfig = bundle.eslintConfig();
        futures.add(CompletableFuture.supplyAsync(() -> {
            if (eslintConfig != null) {
                return eslintAnalyzer.analyzeWithConfig(
                    filename, content, eslintConfig.configFilename(), eslintConfig.configContent());
            } else {
                return eslintAnalyzer.analyze(filename, content);
            }
        }, executor));
    }
    if (runBiome && biomeAnalyzer.isAvailable()) {
        BiomeConfig biomeConfig = bundle.biomeConfig();
        futures.add(CompletableFuture.supplyAsync(
            () -> biomeAnalyzer.analyzeWithConfig(filename, content, biomeConfig), executor
        ));
    }
}
```

4. **Update `analyzeSequentiallyWithBundle` JS/TS branch** with the same gating logic (sync version).

5. **Add Biome to `getAvailableAnalyzers`:**
```java
analyzers.add(new AnalyzerInfo("biome", "Biome", "JavaScript/TypeScript", biomeAnalyzer.isAvailable()));
```

Verify: `./mvnw compile -pl . -q`. Commit: `git commit -m "Wire BiomeAnalyzer through CombinedAnalysisService"`

---

### Task 6: Add 10-min Timeout to EslintAnalyzer

In `EslintAnalyzer.runEslint` method, add timeout to `process.waitFor`:

Find:
```java
process.waitFor();
```

Replace with:
```java
boolean finished = process.waitFor(10, java.util.concurrent.TimeUnit.MINUTES);
if (!finished) {
    log.warn("ESLint timed out after 10 minutes for {}", reportedPath);
    process.destroyForcibly();
    return issues;
}
```

Verify: `./mvnw compile -pl . -q`. Commit: `git commit -m "Add 10-min timeout to ESLint invocation"`

---

### Task 7: Add 10-min Timeouts to Ruff and Bandit

Same pattern in both `RuffAnalyzer.runRuff` and `BanditAnalyzer.runBandit`:

Find:
```java
process.waitFor();
```

Replace with (Ruff):
```java
boolean finished = process.waitFor(10, java.util.concurrent.TimeUnit.MINUTES);
if (!finished) {
    log.warn("Ruff timed out after 10 minutes for {}", reportedPath);
    process.destroyForcibly();
    return issues;
}
```

And for Bandit (same, swap tool name).

Verify: `./mvnw compile -pl . -q`. Commit: `git commit -m "Add 10-min timeout to Ruff and Bandit"`

---

### Task 8: Update README

Add Biome section to `codelens-be/README.md` under the existing "Lint Configuration Pickup" section:

```markdown
### Biome (JavaScript/TypeScript)

**Config files searched:**
- `biome.json`, `biome.jsonc`

**Security floor (always enforced):**
- `lint/security/noDangerouslySetInnerHtml`
- `lint/security/noDangerouslySetInnerHtmlWithChildren`
- `lint/security/noGlobalEval`
- `lint/suspicious/noDebugger`
- `lint/suspicious/noEmptyInterface`

### Linter Detection (ESLint vs Biome)

For JS/TS files, CodeLens parses `package.json` → `scripts.lint` to decide which linter(s) to run:

- Contains `biome` → Biome enabled
- Contains `eslint` → ESLint enabled
- Contains both → both run in parallel (findings from both are surfaced)
- Opaque or missing `scripts.lint` → ESLint always enabled (default); Biome enabled only if `biome.json` is present

### Timeouts

All shell-based analyzers (ESLint, Biome, Ruff, Bandit) have a 10-minute hard timeout per file. If the subprocess doesn't finish within that window, it's killed and the file is analyzed by the remaining analyzers only.
```

Commit: `git commit -m "Document Biome support and linter detection"`

---

## Self-Review

- ✅ All 6 spec items (Biome analyzer, security floor, detection, bundle/service plumbing, timeouts, docs) have tasks
- ✅ `analyze()` delegates to `analyzeWithConfig(null)` for backward compat (same pattern as other analyzers)
- ✅ ESLint path still falls through to current behavior when detection is opaque
- ✅ No placeholders
