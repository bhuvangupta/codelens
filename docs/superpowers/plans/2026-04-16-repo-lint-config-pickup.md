# Repo-Level Lint Config Pickup with Security Floor — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Allow repos to override CodeLens's bundled defaults for PMD, Checkstyle, Ruff, and Bandit, while always enforcing a non-negotiable security floor.

**Architecture:** Mirrors the existing `fetchEslintConfig()` flow at `ReviewEngine.java:1530`. A new `LintConfigService` fetches per-analyzer configs from the PR head SHA. Each analyzer gets a new `analyzeWithConfig()` method that uses repo config but layers a security floor on top. Configs are bundled in `LintConfigBundle` and passed through `CombinedAnalysisService`.

**Tech Stack:** Java 17, Spring Boot 3.2, PMD, Checkstyle, Ruff (subprocess), Bandit (subprocess)

**Spec:** [docs/superpowers/specs/2026-04-16-repo-lint-config-pickup-design.md](../specs/2026-04-16-repo-lint-config-pickup-design.md)

---

## File Map

### New Files (4)
- `codelens-be/src/main/resources/security-floor/pmd-security-floor.xml` — PMD security ruleset (always loaded)
- `codelens-be/src/main/resources/security-floor/checkstyle-security-floor.xml` — Checkstyle security checks (always run)
- `codelens-be/src/main/java/com/codelens/analysis/LintConfigBundle.java` — record wrapping all 5 configs
- `codelens-be/src/main/java/com/codelens/analysis/LintConfigService.java` — orchestrator that fetches all 5 from the git provider

### Modified Files (6)
- `codelens-be/src/main/java/com/codelens/analysis/java/PmdAnalyzer.java` — add `PmdConfig` record + `analyzeWithConfig()`
- `codelens-be/src/main/java/com/codelens/analysis/java/CheckstyleAnalyzer.java` — add `CheckstyleConfig` record + `analyzeWithConfig()` (two-pass)
- `codelens-be/src/main/java/com/codelens/analysis/python/RuffAnalyzer.java` — add `RuffConfig` record + `analyzeWithConfig()` (write config to temp dir)
- `codelens-be/src/main/java/com/codelens/analysis/python/BanditAnalyzer.java` — add `BanditConfig` record + `analyzeWithConfig()` (override severity)
- `codelens-be/src/main/java/com/codelens/analysis/CombinedAnalysisService.java` — accept `LintConfigBundle`, route to each analyzer
- `codelens-be/src/main/java/com/codelens/core/ReviewEngine.java` — call `LintConfigService.fetchAll()`, pass bundle through

### Modified Files (1)
- `codelens-be/README.md` — document security floor for each analyzer

---

### Task 1: Bundle PMD Security Floor Ruleset

**Files:**
- Create: `codelens-be/src/main/resources/security-floor/pmd-security-floor.xml`

PMD's built-in `category/java/security.xml` ruleset is the security floor. Wrap it in a CodeLens ruleset.

- [ ] **Step 1: Create the directory and file**

```bash
mkdir -p /Users/bhuvang/ofb/codelens/codelens-be/src/main/resources/security-floor
```

- [ ] **Step 2: Write the security floor ruleset**

Create `codelens-be/src/main/resources/security-floor/pmd-security-floor.xml`:

```xml
<?xml version="1.0"?>
<ruleset name="CodeLens PMD Security Floor"
         xmlns="http://pmd.sourceforge.net/ruleset/2.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://pmd.sourceforge.net/ruleset/2.0.0 http://pmd.sourceforge.net/ruleset_2_0_0.xsd">

    <description>
        Non-negotiable PMD security rules enforced by CodeLens.
        These rules always run regardless of project configuration.
    </description>

    <!-- Full PMD security category - cannot be disabled -->
    <rule ref="category/java/security.xml"/>

    <!-- Critical errorprone rules with security implications -->
    <rule ref="category/java/errorprone.xml/AvoidCatchingThrowable"/>
    <rule ref="category/java/errorprone.xml/EmptyCatchBlock"/>
    <rule ref="category/java/errorprone.xml/AvoidLiteralsInIfCondition"/>

</ruleset>
```

- [ ] **Step 3: Commit**

```bash
cd /Users/bhuvang/ofb/codelens
git add codelens-be/src/main/resources/security-floor/pmd-security-floor.xml
git commit -m "Add PMD security floor ruleset"
```

---

### Task 2: Bundle Checkstyle Security Floor Config

**Files:**
- Create: `codelens-be/src/main/resources/security-floor/checkstyle-security-floor.xml`

- [ ] **Step 1: Write the security floor config**

Create `codelens-be/src/main/resources/security-floor/checkstyle-security-floor.xml`:

```xml
<?xml version="1.0"?>
<!DOCTYPE module PUBLIC
    "-//Checkstyle//DTD Checkstyle Configuration 1.3//EN"
    "https://checkstyle.org/dtds/configuration_1_3.dtd">
<module name="Checker">
    <module name="TreeWalker">
        <!-- Security-critical checks - always enforced regardless of project config -->
        <module name="EmptyCatchBlock"/>
        <module name="IllegalCatch"/>
        <module name="IllegalThrows"/>
        <module name="NoClone"/>
        <module name="NoFinalizer"/>
    </module>
</module>
```

- [ ] **Step 2: Commit**

```bash
cd /Users/bhuvang/ofb/codelens
git add codelens-be/src/main/resources/security-floor/checkstyle-security-floor.xml
git commit -m "Add Checkstyle security floor config"
```

---

### Task 3: Update PmdAnalyzer to Accept Repo Config

**Files:**
- Modify: `codelens-be/src/main/java/com/codelens/analysis/java/PmdAnalyzer.java`

Add `PmdConfig` record, a `getConfigFileNames()` static getter, and an `analyzeWithConfig()` method that loads BOTH the repo's ruleset (written to a temp file) AND the security floor (classpath resource).

- [ ] **Step 1: Add imports and replace the file body**

Replace the entire contents of `PmdAnalyzer.java` with:

```java
package com.codelens.analysis.java;

import com.codelens.analysis.AnalysisIssue;
import com.codelens.analysis.StaticAnalyzer;
import lombok.extern.slf4j.Slf4j;
import net.sourceforge.pmd.*;
import net.sourceforge.pmd.lang.LanguageRegistry;
import net.sourceforge.pmd.lang.document.FileId;
import net.sourceforge.pmd.lang.document.TextFile;
import net.sourceforge.pmd.lang.rule.RulePriority;
import net.sourceforge.pmd.reporting.Report;
import net.sourceforge.pmd.reporting.RuleViolation;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Slf4j
@Component
public class PmdAnalyzer implements StaticAnalyzer {

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("java");

    private static final List<String> PMD_CONFIG_FILES = List.of(
        "pmd-ruleset.xml",
        ".pmd-ruleset.xml",
        "config/pmd/ruleset.xml"
    );

    private static final String SECURITY_FLOOR_RULESET = "security-floor/pmd-security-floor.xml";
    private static final String DEFAULT_RULESET = "pmd/codelens-ruleset.xml";

    /**
     * Holds PMD configuration fetched from the repository.
     */
    public record PmdConfig(String configFilename, String configContent) {}

    /**
     * Get list of PMD config file names to search for in a repo.
     */
    public static List<String> getConfigFileNames() {
        return PMD_CONFIG_FILES;
    }

    @Override
    public String getName() {
        return "pmd";
    }

    @Override
    public Set<String> getSupportedExtensions() {
        return SUPPORTED_EXTENSIONS;
    }

    @Override
    public List<AnalysisIssue> analyze(String filename, String content) {
        return analyzeWithConfig(filename, content, null);
    }

    /**
     * Analyze with optional project ruleset. If config is non-null, loads BOTH
     * the project's ruleset and the security floor. If null, loads only the
     * bundled CodeLens default ruleset.
     */
    public List<AnalysisIssue> analyzeWithConfig(String filename, String content, PmdConfig config) {
        List<AnalysisIssue> issues = new ArrayList<>();
        Path tempRulesetFile = null;

        try {
            PMDConfiguration pmdConfig = new PMDConfiguration();
            pmdConfig.setDefaultLanguageVersion(
                LanguageRegistry.PMD.getLanguageById("java").getDefaultVersion()
            );

            if (config != null && config.configContent() != null && !config.configContent().isBlank()) {
                tempRulesetFile = Files.createTempFile("pmd-ruleset-", ".xml");
                Files.writeString(tempRulesetFile, config.configContent());
                pmdConfig.addRuleSet(tempRulesetFile.toString());
                pmdConfig.addRuleSet(SECURITY_FLOOR_RULESET);
                log.info("PMD: Using project ruleset from {} + security floor", config.configFilename());
            } else {
                pmdConfig.addRuleSet(DEFAULT_RULESET);
                log.debug("PMD: Using bundled CodeLens default ruleset");
            }

            try (PmdAnalysis pmd = PmdAnalysis.create(pmdConfig)) {
                TextFile textFile = TextFile.forCharSeq(
                    content,
                    FileId.fromPathLikeString(filename),
                    LanguageRegistry.PMD.getLanguageById("java").getDefaultVersion()
                );
                pmd.files().addFile(textFile);

                Report report = pmd.performAnalysisAndCollectReport();

                for (RuleViolation violation : report.getViolations()) {
                    issues.add(AnalysisIssue.builder()
                        .analyzer(getName())
                        .ruleId(violation.getRule().getName())
                        .severity(mapPriority(violation.getRule().getPriority()))
                        .category(violation.getRule().getRuleSetName())
                        .message(violation.getDescription())
                        .filePath(filename)
                        .line(violation.getBeginLine())
                        .column(violation.getBeginColumn())
                        .endLine(violation.getEndLine())
                        .endColumn(violation.getEndColumn())
                        .build());
                }
            }
        } catch (Exception e) {
            log.warn("PMD analysis failed for {}: {}", filename, e.getMessage());
        } finally {
            if (tempRulesetFile != null) {
                try {
                    Files.deleteIfExists(tempRulesetFile);
                } catch (IOException e) {
                    log.debug("Failed to delete temp PMD ruleset: {}", tempRulesetFile);
                }
            }
        }

        return issues;
    }

    @Override
    public List<AnalysisIssue> analyzeFile(String filePath) {
        try {
            String content = Files.readString(Path.of(filePath));
            return analyze(filePath, content);
        } catch (Exception e) {
            log.error("Failed to analyze file: {}", filePath, e);
            return List.of();
        }
    }

    @Override
    public boolean isAvailable() {
        try {
            Class.forName("net.sourceforge.pmd.PMDConfiguration");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private AnalysisIssue.Severity mapPriority(RulePriority priority) {
        return switch (priority) {
            case HIGH -> AnalysisIssue.Severity.HIGH;
            case MEDIUM_HIGH -> AnalysisIssue.Severity.MEDIUM;
            case MEDIUM -> AnalysisIssue.Severity.MEDIUM;
            case MEDIUM_LOW -> AnalysisIssue.Severity.LOW;
            case LOW -> AnalysisIssue.Severity.LOW;
        };
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `cd /Users/bhuvang/ofb/codelens/codelens-be && ./mvnw compile -pl . -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
cd /Users/bhuvang/ofb/codelens
git add codelens-be/src/main/java/com/codelens/analysis/java/PmdAnalyzer.java
git commit -m "Add PMD analyzeWithConfig and security floor"
```

---

### Task 4: Update CheckstyleAnalyzer for Two-Pass Merge

**Files:**
- Modify: `codelens-be/src/main/java/com/codelens/analysis/java/CheckstyleAnalyzer.java`

Add `CheckstyleConfig` record, `getConfigFileNames()`, and `analyzeWithConfig()` that runs Checkstyle twice — once with repo's config, once with the security floor — then dedupes by `(filename, line, ruleId)`.

- [ ] **Step 1: Replace the file body**

Replace the entire contents of `CheckstyleAnalyzer.java` with:

```java
package com.codelens.analysis.java;

import com.codelens.analysis.AnalysisIssue;
import com.codelens.analysis.StaticAnalyzer;
import com.puppycrawl.tools.checkstyle.Checker;
import com.puppycrawl.tools.checkstyle.ConfigurationLoader;
import com.puppycrawl.tools.checkstyle.PropertiesExpander;
import com.puppycrawl.tools.checkstyle.api.AuditEvent;
import com.puppycrawl.tools.checkstyle.api.AuditListener;
import com.puppycrawl.tools.checkstyle.api.Configuration;
import com.puppycrawl.tools.checkstyle.api.SeverityLevel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.xml.sax.InputSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

@Slf4j
@Component
public class CheckstyleAnalyzer implements StaticAnalyzer {

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("java");

    private static final List<String> CHECKSTYLE_CONFIG_FILES = List.of(
        "checkstyle.xml",
        "config/checkstyle/checkstyle.xml",
        ".checkstyle.xml"
    );

    private static final String SECURITY_FLOOR_RESOURCE = "security-floor/checkstyle-security-floor.xml";

    private static final String DEFAULT_CONFIG = """
        <?xml version="1.0"?>
        <!DOCTYPE module PUBLIC
            "-//Checkstyle//DTD Checkstyle Configuration 1.3//EN"
            "https://checkstyle.org/dtds/configuration_1_3.dtd">
        <module name="Checker">
            <module name="TreeWalker">
                <module name="AvoidStarImport"/>
                <module name="EmptyBlock"/>
                <module name="EmptyCatchBlock"/>
                <module name="EqualsHashCode"/>
                <module name="FallThrough"/>
                <module name="HiddenField">
                    <property name="ignoreConstructorParameter" value="true"/>
                    <property name="ignoreSetter" value="true"/>
                    <property name="setterCanReturnItsClass" value="true"/>
                </module>
                <module name="SuppressionXpathSingleFilter">
                    <property name="checks" value="HiddenField"/>
                    <property name="query" value="//METHOD_DEF[./IDENT[starts-with(@text, 'with')]]/PARAMETERS/PARAMETER_DEF"/>
                </module>
                <module name="IllegalCatch"/>
                <module name="IllegalThrows"/>
                <module name="MissingSwitchDefault"/>
                <module name="ModifiedControlVariable"/>
                <module name="MultipleVariableDeclarations"/>
                <module name="NestedForDepth">
                    <property name="max" value="3"/>
                </module>
                <module name="NestedIfDepth">
                    <property name="max" value="4"/>
                </module>
                <module name="NestedTryDepth">
                    <property name="max" value="2"/>
                </module>
                <module name="NoClone"/>
                <module name="NoFinalizer"/>
                <module name="SimplifyBooleanExpression"/>
                <module name="SimplifyBooleanReturn"/>
                <module name="StringLiteralEquality"/>
            </module>
        </module>
        """;

    /**
     * Holds Checkstyle configuration fetched from the repository.
     */
    public record CheckstyleConfig(String configFilename, String configContent) {}

    /**
     * Get list of Checkstyle config file names to search for in a repo.
     */
    public static List<String> getConfigFileNames() {
        return CHECKSTYLE_CONFIG_FILES;
    }

    @Override
    public String getName() {
        return "checkstyle";
    }

    @Override
    public Set<String> getSupportedExtensions() {
        return SUPPORTED_EXTENSIONS;
    }

    @Override
    public List<AnalysisIssue> analyze(String filename, String content) {
        return analyzeWithConfig(filename, content, null);
    }

    /**
     * Analyze with optional project config. If config is non-null, runs TWO passes:
     *   1. With the project's config
     *   2. With the security floor
     * Then merges results and deduplicates by (filename, line, ruleId).
     * If config is null, uses only the bundled DEFAULT_CONFIG.
     */
    public List<AnalysisIssue> analyzeWithConfig(String filename, String content, CheckstyleConfig config) {
        List<AnalysisIssue> issues = new ArrayList<>();

        try {
            Path tempFile = Files.createTempFile("checkstyle", ".java");
            Files.writeString(tempFile, content);

            try {
                if (config != null && config.configContent() != null && !config.configContent().isBlank()) {
                    // Run with project config + security floor (two passes)
                    issues.addAll(runCheckstyle(tempFile, filename, config.configContent()));
                    String securityFloorXml = loadSecurityFloor();
                    if (securityFloorXml != null) {
                        issues.addAll(runCheckstyle(tempFile, filename, securityFloorXml));
                    }
                    log.info("Checkstyle: Using project config from {} + security floor",
                        config.configFilename());
                    issues = deduplicateIssues(issues);
                } else {
                    // Use bundled default
                    issues.addAll(runCheckstyle(tempFile, filename, DEFAULT_CONFIG));
                    log.debug("Checkstyle: Using bundled CodeLens default config");
                }
            } finally {
                Files.deleteIfExists(tempFile);
            }
        } catch (Exception e) {
            log.warn("Checkstyle analysis failed for {}: {}", filename, e.getMessage());
        }

        return issues;
    }

    private List<AnalysisIssue> runCheckstyle(Path tempFile, String originalFilename, String configXml)
            throws Exception {
        Configuration config = ConfigurationLoader.loadConfiguration(
            new InputSource(new StringReader(configXml)),
            new PropertiesExpander(new Properties()),
            ConfigurationLoader.IgnoredModulesOptions.OMIT
        );

        Checker checker = new Checker();
        checker.setModuleClassLoader(Checker.class.getClassLoader());
        checker.configure(config);

        IssueCollector collector = new IssueCollector(originalFilename);
        checker.addListener(collector);

        try {
            checker.process(List.of(tempFile.toFile()));
            return collector.getIssues();
        } finally {
            checker.destroy();
        }
    }

    private String loadSecurityFloor() {
        try (InputStream is = new ClassPathResource(SECURITY_FLOOR_RESOURCE).getInputStream()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Failed to load Checkstyle security floor: {}", e.getMessage());
            return null;
        }
    }

    private List<AnalysisIssue> deduplicateIssues(List<AnalysisIssue> issues) {
        Set<String> seen = new HashSet<>();
        List<AnalysisIssue> deduped = new ArrayList<>();
        for (AnalysisIssue issue : issues) {
            String key = issue.filePath() + ":" + issue.line() + ":" + issue.ruleId();
            if (seen.add(key)) {
                deduped.add(issue);
            }
        }
        return deduped;
    }

    @Override
    public List<AnalysisIssue> analyzeFile(String filePath) {
        try {
            String content = Files.readString(Path.of(filePath));
            return analyze(filePath, content);
        } catch (Exception e) {
            log.error("Failed to analyze file: {}", filePath, e);
            return List.of();
        }
    }

    @Override
    public boolean isAvailable() {
        try {
            Class.forName("com.puppycrawl.tools.checkstyle.Checker");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private class IssueCollector implements AuditListener {
        private final String originalFilename;
        private final List<AnalysisIssue> issues = new ArrayList<>();

        public IssueCollector(String originalFilename) {
            this.originalFilename = originalFilename;
        }

        @Override
        public void auditStarted(AuditEvent event) {}

        @Override
        public void auditFinished(AuditEvent event) {}

        @Override
        public void fileStarted(AuditEvent event) {}

        @Override
        public void fileFinished(AuditEvent event) {}

        @Override
        public void addError(AuditEvent event) {
            issues.add(AnalysisIssue.builder()
                .analyzer(getName())
                .ruleId(extractRuleName(event.getSourceName()))
                .severity(mapSeverity(event.getSeverityLevel()))
                .category("Code Style")
                .message(event.getMessage())
                .filePath(originalFilename)
                .line(event.getLine())
                .column(event.getColumn())
                .build());
        }

        @Override
        public void addException(AuditEvent event, Throwable throwable) {
            log.warn("Checkstyle exception: {}", throwable.getMessage());
        }

        public List<AnalysisIssue> getIssues() {
            return issues;
        }

        private String extractRuleName(String sourceName) {
            if (sourceName == null) return "Unknown";
            int lastDot = sourceName.lastIndexOf('.');
            return lastDot > 0 ? sourceName.substring(lastDot + 1) : sourceName;
        }

        private AnalysisIssue.Severity mapSeverity(SeverityLevel level) {
            return switch (level) {
                case ERROR -> AnalysisIssue.Severity.HIGH;
                case WARNING -> AnalysisIssue.Severity.MEDIUM;
                case INFO -> AnalysisIssue.Severity.LOW;
                case IGNORE -> AnalysisIssue.Severity.INFO;
            };
        }
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `cd /Users/bhuvang/ofb/codelens/codelens-be && ./mvnw compile -pl . -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
cd /Users/bhuvang/ofb/codelens
git add codelens-be/src/main/java/com/codelens/analysis/java/CheckstyleAnalyzer.java
git commit -m "Add Checkstyle two-pass with security floor"
```

---

### Task 5: Update RuffAnalyzer for Repo Config

**Files:**
- Modify: `codelens-be/src/main/java/com/codelens/analysis/python/RuffAnalyzer.java`

Add `RuffConfig` record + `analyzeWithConfig()`. When repo config exists, write it to a temp dir alongside the source file, drop the hardcoded `--select`, and add `--extend-select S,B`.

- [ ] **Step 1: Replace the file body**

Replace the entire contents of `RuffAnalyzer.java` with:

```java
package com.codelens.analysis.python;

import com.codelens.analysis.AnalysisIssue;
import com.codelens.analysis.StaticAnalyzer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Component
public class RuffAnalyzer implements StaticAnalyzer {

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("py", "pyw", "pyi");

    private static final List<String> RUFF_CONFIG_FILES = List.of(
        "ruff.toml",
        ".ruff.toml",
        "pyproject.toml"
    );

    private static final String DEFAULT_SELECT =
        "E,F,W,C,B,S,N,UP,ANN,ASYNC,A,COM,DTZ,DJ,EXE,FA,ISC,ICN,G,INP,PIE,T20,PYI,PT,Q,RSE,RET,SLF,SLOT,SIM,TID,TCH,INT,ARG,PTH,TD,FIX,ERA,PD,PGH,PL,TRY,FLY,NPY,AIR,PERF,FURB,LOG,RUF";

    private static final String SECURITY_FLOOR_SELECT = "S,B";

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Holds Ruff configuration fetched from the repository.
     */
    public record RuffConfig(String configFilename, String configContent) {}

    /**
     * Get list of Ruff config file names to search for in a repo.
     * Note: pyproject.toml only counts if it contains a [tool.ruff] section
     * (caller should verify before constructing RuffConfig).
     */
    public static List<String> getConfigFileNames() {
        return RUFF_CONFIG_FILES;
    }

    @Override
    public String getName() {
        return "ruff";
    }

    @Override
    public Set<String> getSupportedExtensions() {
        return SUPPORTED_EXTENSIONS;
    }

    @Override
    public List<AnalysisIssue> analyze(String filename, String content) {
        return analyzeWithConfig(filename, content, null);
    }

    /**
     * Analyze with optional project config. If config is non-null, writes the config
     * to a temp dir alongside the source file, drops the default --select (lets project
     * config decide rule selection), and adds --extend-select S,B (security floor).
     * If config is null, uses the hardcoded default --select.
     */
    public List<AnalysisIssue> analyzeWithConfig(String filename, String content, RuffConfig config) {
        List<AnalysisIssue> issues = new ArrayList<>();
        Path tempDir = null;

        try {
            if (config != null && config.configContent() != null && !config.configContent().isBlank()) {
                tempDir = Files.createTempDirectory("ruff-session-");
                Path configPath = tempDir.resolve(config.configFilename());
                Files.writeString(configPath, config.configContent());
                Path sourceFile = tempDir.resolve("source.py");
                Files.writeString(sourceFile, content);
                issues = runRuff(sourceFile.toString(), filename, true, tempDir);
                log.info("Ruff: Using project config from {} + security floor (--extend-select {})",
                    config.configFilename(), SECURITY_FLOOR_SELECT);
            } else {
                Path tempFile = Files.createTempFile("ruff", ".py");
                Files.writeString(tempFile, content);
                try {
                    issues = runRuff(tempFile.toString(), filename, false, null);
                    log.debug("Ruff: Using bundled CodeLens default --select");
                } finally {
                    Files.deleteIfExists(tempFile);
                }
            }
        } catch (Exception e) {
            log.warn("Ruff analysis failed for {}: {}", filename, e.getMessage());
        } finally {
            if (tempDir != null) {
                try {
                    Files.walk(tempDir)
                        .sorted((a, b) -> b.compareTo(a))
                        .forEach(p -> {
                            try {
                                Files.deleteIfExists(p);
                            } catch (IOException e) {
                                log.debug("Failed to delete temp file: {}", p);
                            }
                        });
                } catch (IOException e) {
                    log.debug("Failed to clean up Ruff temp dir: {}", tempDir);
                }
            }
        }

        return issues;
    }

    @Override
    public List<AnalysisIssue> analyzeFile(String filePath) {
        return runRuff(filePath, filePath, false, null);
    }

    private List<AnalysisIssue> runRuff(String actualPath, String reportedPath,
                                        boolean useProjectConfig, Path workingDirectory) {
        List<AnalysisIssue> issues = new ArrayList<>();
        Process process = null;

        try {
            List<String> command = new ArrayList<>();
            command.add("ruff");
            command.add("check");
            command.add("--output-format");
            command.add("json");
            if (useProjectConfig) {
                // Let project config decide --select; always extend with security floor
                command.add("--extend-select");
                command.add(SECURITY_FLOOR_SELECT);
            } else {
                command.add("--select");
                command.add(DEFAULT_SELECT);
            }
            command.add(actualPath);

            ProcessBuilder pb = new ProcessBuilder(command);
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

            process.waitFor();

            if (!output.isEmpty() && output.startsWith("[")) {
                JsonNode results = objectMapper.readTree(output);
                for (JsonNode result : results) {
                    issues.add(parseResult(result, reportedPath));
                }
            }
        } catch (IOException e) {
            log.warn("Ruff I/O error: {}", e.getMessage());
        } catch (InterruptedException e) {
            log.warn("Ruff execution interrupted: {}", e.getMessage());
            Thread.currentThread().interrupt();
        } finally {
            if (process != null) {
                process.destroyForcibly();
            }
        }

        return issues;
    }

    private AnalysisIssue parseResult(JsonNode result, String filePath) {
        String code = result.has("code") ? result.get("code").asText() : "unknown";
        String message = result.has("message") ? result.get("message").asText() : "";

        JsonNode location = result.get("location");
        int line = location != null && location.has("row") ? location.get("row").asInt() : 0;
        int column = location != null && location.has("column") ? location.get("column").asInt() : 0;

        JsonNode endLocation = result.get("end_location");
        int endLine = endLocation != null && endLocation.has("row") ? endLocation.get("row").asInt() : line;
        int endColumn = endLocation != null && endLocation.has("column") ? endLocation.get("column").asInt() : column;

        String suggestion = null;
        if (result.has("fix") && !result.get("fix").isNull()) {
            JsonNode fix = result.get("fix");
            if (fix.has("message")) {
                suggestion = fix.get("message").asText();
            }
        }

        return AnalysisIssue.builder()
            .analyzer(getName())
            .ruleId(code)
            .severity(mapSeverity(code))
            .category(categorizeRule(code))
            .message(message)
            .filePath(filePath)
            .line(line)
            .column(column)
            .endLine(endLine)
            .endColumn(endColumn)
            .suggestion(suggestion)
            .build();
    }

    private AnalysisIssue.Severity mapSeverity(String code) {
        if (code.startsWith("S")) {
            return AnalysisIssue.Severity.HIGH;
        }
        if (code.startsWith("E") || code.startsWith("F")) {
            return AnalysisIssue.Severity.MEDIUM;
        }
        if (code.startsWith("B")) {
            return AnalysisIssue.Severity.MEDIUM;
        }
        if (code.startsWith("W")) {
            return AnalysisIssue.Severity.LOW;
        }
        return AnalysisIssue.Severity.LOW;
    }

    private String categorizeRule(String code) {
        if (code.startsWith("S")) return "Security";
        if (code.startsWith("E")) return "Error";
        if (code.startsWith("F")) return "PyFlakes";
        if (code.startsWith("W")) return "Warning";
        if (code.startsWith("C")) return "Complexity";
        if (code.startsWith("B")) return "Bugbear";
        if (code.startsWith("N")) return "Naming";
        if (code.startsWith("UP")) return "Upgrade";
        if (code.startsWith("ANN")) return "Annotations";
        if (code.startsWith("ASYNC")) return "Async";
        if (code.startsWith("PL")) return "Pylint";
        if (code.startsWith("TRY")) return "Exception";
        if (code.startsWith("PERF")) return "Performance";
        if (code.startsWith("RUF")) return "Ruff";
        return "Code Quality";
    }

    @Override
    public boolean isAvailable() {
        try {
            ProcessBuilder pb = new ProcessBuilder("ruff", "--version");
            Process process = pb.start();
            return process.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `cd /Users/bhuvang/ofb/codelens/codelens-be && ./mvnw compile -pl . -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
cd /Users/bhuvang/ofb/codelens
git add codelens-be/src/main/java/com/codelens/analysis/python/RuffAnalyzer.java
git commit -m "Add Ruff analyzeWithConfig and security floor"
```

---

### Task 6: Update BanditAnalyzer for Repo Config

**Files:**
- Modify: `codelens-be/src/main/java/com/codelens/analysis/python/BanditAnalyzer.java`

Add `BanditConfig` record + `analyzeWithConfig()`. When repo config exists, write it to a temp dir, run with `-c <config>` AND override severity to `--severity-level low --confidence-level medium` (security floor — repo can pick which tests, but cannot raise the severity floor).

- [ ] **Step 1: Replace the file body**

Replace the entire contents of `BanditAnalyzer.java` with:

```java
package com.codelens.analysis.python;

import com.codelens.analysis.AnalysisIssue;
import com.codelens.analysis.StaticAnalyzer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Component
public class BanditAnalyzer implements StaticAnalyzer {

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("py", "pyw", "pyi");

    private static final List<String> BANDIT_CONFIG_FILES = List.of(
        ".bandit",
        "bandit.yaml",
        "pyproject.toml"
    );

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Holds Bandit configuration fetched from the repository.
     */
    public record BanditConfig(String configFilename, String configContent) {}

    /**
     * Get list of Bandit config file names to search for in a repo.
     * Note: pyproject.toml only counts if it contains a [tool.bandit] section
     * (caller should verify before constructing BanditConfig).
     */
    public static List<String> getConfigFileNames() {
        return BANDIT_CONFIG_FILES;
    }

    @Override
    public String getName() {
        return "bandit";
    }

    @Override
    public Set<String> getSupportedExtensions() {
        return SUPPORTED_EXTENSIONS;
    }

    @Override
    public List<AnalysisIssue> analyze(String filename, String content) {
        return analyzeWithConfig(filename, content, null);
    }

    /**
     * Analyze with optional project config. If config is non-null, writes the config
     * to a temp dir, runs bandit with -c pointing at it, AND overrides severity to
     * --severity-level low --confidence-level medium (security floor — repo can
     * customize which tests run but cannot raise the severity threshold).
     * If config is null, runs with -ll (medium+ severity, default behavior).
     */
    public List<AnalysisIssue> analyzeWithConfig(String filename, String content, BanditConfig config) {
        List<AnalysisIssue> issues = new ArrayList<>();
        Path tempDir = null;

        try {
            if (config != null && config.configContent() != null && !config.configContent().isBlank()) {
                tempDir = Files.createTempDirectory("bandit-session-");
                Path configPath = tempDir.resolve(config.configFilename());
                Files.writeString(configPath, config.configContent());
                Path sourceFile = tempDir.resolve("source.py");
                Files.writeString(sourceFile, content);
                issues = runBandit(sourceFile.toString(), filename, configPath.toString());
                log.info("Bandit: Using project config from {} + severity floor (--severity-level low)",
                    config.configFilename());
            } else {
                Path tempFile = Files.createTempFile("bandit", ".py");
                Files.writeString(tempFile, content);
                try {
                    issues = runBandit(tempFile.toString(), filename, null);
                    log.debug("Bandit: Using bundled CodeLens default (-ll)");
                } finally {
                    Files.deleteIfExists(tempFile);
                }
            }
        } catch (Exception e) {
            log.warn("Bandit analysis failed for {}: {}", filename, e.getMessage());
        } finally {
            if (tempDir != null) {
                try {
                    Files.walk(tempDir)
                        .sorted((a, b) -> b.compareTo(a))
                        .forEach(p -> {
                            try {
                                Files.deleteIfExists(p);
                            } catch (IOException e) {
                                log.debug("Failed to delete temp file: {}", p);
                            }
                        });
                } catch (IOException e) {
                    log.debug("Failed to clean up Bandit temp dir: {}", tempDir);
                }
            }
        }

        return issues;
    }

    @Override
    public List<AnalysisIssue> analyzeFile(String filePath) {
        return runBandit(filePath, filePath, null);
    }

    private List<AnalysisIssue> runBandit(String actualPath, String reportedPath, String configPath) {
        List<AnalysisIssue> issues = new ArrayList<>();
        Process process = null;

        try {
            List<String> command = new ArrayList<>();
            command.add("bandit");
            command.add("-f");
            command.add("json");
            if (configPath != null) {
                command.add("-c");
                command.add(configPath);
                // Override severity floor: ensure medium+ confidence, low+ severity surface
                command.add("--severity-level");
                command.add("low");
                command.add("--confidence-level");
                command.add("medium");
            } else {
                command.add("-ll");
            }
            command.add(actualPath);

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);

            process = pb.start();
            String output;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                output = reader.lines().collect(Collectors.joining("\n"));
            }

            process.waitFor();

            if (!output.isEmpty() && output.contains("\"results\"")) {
                JsonNode root = objectMapper.readTree(output);
                JsonNode results = root.get("results");
                if (results != null && results.isArray()) {
                    for (JsonNode result : results) {
                        issues.add(parseResult(result, reportedPath));
                    }
                }
            }
        } catch (IOException e) {
            log.warn("Bandit I/O error: {}", e.getMessage());
        } catch (InterruptedException e) {
            log.warn("Bandit execution interrupted: {}", e.getMessage());
            Thread.currentThread().interrupt();
        } finally {
            if (process != null) {
                process.destroyForcibly();
            }
        }

        return issues;
    }

    private AnalysisIssue parseResult(JsonNode result, String filePath) {
        String testId = result.has("test_id") ? result.get("test_id").asText() : "unknown";
        String testName = result.has("test_name") ? result.get("test_name").asText() : "";
        String message = result.has("issue_text") ? result.get("issue_text").asText() : "";
        String severity = result.has("issue_severity") ? result.get("issue_severity").asText() : "MEDIUM";
        String confidence = result.has("issue_confidence") ? result.get("issue_confidence").asText() : "MEDIUM";
        int line = result.has("line_number") ? result.get("line_number").asInt() : 0;

        String codeSnippet = null;
        if (result.has("code") && !result.get("code").isNull()) {
            codeSnippet = result.get("code").asText();
        }

        String suggestion = null;
        if (result.has("more_info") && !result.get("more_info").isNull()) {
            suggestion = "See: " + result.get("more_info").asText();
        }

        return AnalysisIssue.builder()
            .analyzer(getName())
            .ruleId(testId)
            .severity(mapSeverity(severity, confidence))
            .category("Security")
            .message(testName + ": " + message)
            .filePath(filePath)
            .line(line)
            .column(0)
            .endLine(line)
            .endColumn(0)
            .codeSnippet(codeSnippet)
            .suggestion(suggestion)
            .build();
    }

    private AnalysisIssue.Severity mapSeverity(String severity, String confidence) {
        if ("HIGH".equalsIgnoreCase(severity) && "HIGH".equalsIgnoreCase(confidence)) {
            return AnalysisIssue.Severity.CRITICAL;
        }
        if ("HIGH".equalsIgnoreCase(severity)) {
            return AnalysisIssue.Severity.HIGH;
        }
        if ("MEDIUM".equalsIgnoreCase(severity)) {
            return AnalysisIssue.Severity.MEDIUM;
        }
        return AnalysisIssue.Severity.LOW;
    }

    @Override
    public boolean isAvailable() {
        try {
            ProcessBuilder pb = new ProcessBuilder("bandit", "--version");
            Process process = pb.start();
            return process.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `cd /Users/bhuvang/ofb/codelens/codelens-be && ./mvnw compile -pl . -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
cd /Users/bhuvang/ofb/codelens
git add codelens-be/src/main/java/com/codelens/analysis/python/BanditAnalyzer.java
git commit -m "Add Bandit analyzeWithConfig and severity floor"
```

---

### Task 7: Create LintConfigBundle and LintConfigService

**Files:**
- Create: `codelens-be/src/main/java/com/codelens/analysis/LintConfigBundle.java`
- Create: `codelens-be/src/main/java/com/codelens/analysis/LintConfigService.java`

The `LintConfigBundle` is a record holding all 5 configs (ESLint + the 4 new analyzers). `LintConfigService` has one public method, `fetchAll(...)`, that consolidates fetching for all 5. The existing `fetchEslintConfig()` in `ReviewEngine` will be removed in Task 9 once the bundle replaces it.

- [ ] **Step 1: Create `LintConfigBundle.java`**

Create `codelens-be/src/main/java/com/codelens/analysis/LintConfigBundle.java`:

```java
package com.codelens.analysis;

import com.codelens.analysis.java.CheckstyleAnalyzer.CheckstyleConfig;
import com.codelens.analysis.java.PmdAnalyzer.PmdConfig;
import com.codelens.analysis.javascript.EslintAnalyzer.EslintConfig;
import com.codelens.analysis.python.BanditAnalyzer.BanditConfig;
import com.codelens.analysis.python.RuffAnalyzer.RuffConfig;

/**
 * Bundle of all per-analyzer configurations fetched from the repo being reviewed.
 * Any field may be null, indicating no project config was found for that analyzer
 * (in which case the analyzer uses its bundled CodeLens default).
 */
public record LintConfigBundle(
    EslintConfig eslintConfig,
    PmdConfig pmdConfig,
    CheckstyleConfig checkstyleConfig,
    RuffConfig ruffConfig,
    BanditConfig banditConfig
) {
    /**
     * Convenience: a bundle with no project configs (everything uses CodeLens defaults).
     */
    public static LintConfigBundle empty() {
        return new LintConfigBundle(null, null, null, null, null);
    }
}
```

- [ ] **Step 2: Create `LintConfigService.java`**

Create `codelens-be/src/main/java/com/codelens/analysis/LintConfigService.java`:

```java
package com.codelens.analysis;

import com.codelens.analysis.java.CheckstyleAnalyzer;
import com.codelens.analysis.java.CheckstyleAnalyzer.CheckstyleConfig;
import com.codelens.analysis.java.PmdAnalyzer;
import com.codelens.analysis.java.PmdAnalyzer.PmdConfig;
import com.codelens.analysis.javascript.EslintAnalyzer;
import com.codelens.analysis.javascript.EslintAnalyzer.EslintConfig;
import com.codelens.analysis.python.BanditAnalyzer;
import com.codelens.analysis.python.BanditAnalyzer.BanditConfig;
import com.codelens.analysis.python.RuffAnalyzer;
import com.codelens.analysis.python.RuffAnalyzer.RuffConfig;
import com.codelens.git.GitProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Fetches lint configurations from the repository being reviewed.
 * For each analyzer, walks the candidate file list and returns the first
 * non-blank file content found, or null if none found.
 */
@Slf4j
@Service
public class LintConfigService {

    /**
     * Fetch all per-analyzer configs from the repo at the given commit SHA.
     * Returns a bundle where each field is either the config found, or null.
     */
    public LintConfigBundle fetchAll(GitProvider gitProvider, String owner, String repo, String commitSha) {
        return new LintConfigBundle(
            fetchEslintConfig(gitProvider, owner, repo, commitSha),
            fetchPmdConfig(gitProvider, owner, repo, commitSha),
            fetchCheckstyleConfig(gitProvider, owner, repo, commitSha),
            fetchRuffConfig(gitProvider, owner, repo, commitSha),
            fetchBanditConfig(gitProvider, owner, repo, commitSha)
        );
    }

    private EslintConfig fetchEslintConfig(GitProvider gitProvider, String owner, String repo, String commitSha) {
        for (String configFilename : EslintAnalyzer.getConfigFileNames()) {
            try {
                String content = gitProvider.getFileContent(owner, repo, configFilename, commitSha);
                if (content != null && !content.isBlank()) {
                    log.debug("Found ESLint config: {}", configFilename);
                    return new EslintConfig(configFilename, content);
                }
            } catch (Exception e) {
                log.trace("ESLint config not found: {}", configFilename);
            }
        }
        // Also check package.json for eslintConfig
        try {
            String packageJson = gitProvider.getFileContent(owner, repo, "package.json", commitSha);
            if (packageJson != null && packageJson.contains("\"eslintConfig\"")) {
                log.debug("Found eslintConfig in package.json");
                return new EslintConfig("package.json", packageJson);
            }
        } catch (Exception e) {
            log.trace("No package.json found");
        }
        return null;
    }

    private PmdConfig fetchPmdConfig(GitProvider gitProvider, String owner, String repo, String commitSha) {
        for (String configFilename : PmdAnalyzer.getConfigFileNames()) {
            try {
                String content = gitProvider.getFileContent(owner, repo, configFilename, commitSha);
                if (content != null && !content.isBlank()) {
                    log.debug("Found PMD config: {}", configFilename);
                    return new PmdConfig(configFilename, content);
                }
            } catch (Exception e) {
                log.trace("PMD config not found: {}", configFilename);
            }
        }
        return null;
    }

    private CheckstyleConfig fetchCheckstyleConfig(GitProvider gitProvider, String owner, String repo, String commitSha) {
        for (String configFilename : CheckstyleAnalyzer.getConfigFileNames()) {
            try {
                String content = gitProvider.getFileContent(owner, repo, configFilename, commitSha);
                if (content != null && !content.isBlank()) {
                    log.debug("Found Checkstyle config: {}", configFilename);
                    return new CheckstyleConfig(configFilename, content);
                }
            } catch (Exception e) {
                log.trace("Checkstyle config not found: {}", configFilename);
            }
        }
        return null;
    }

    private RuffConfig fetchRuffConfig(GitProvider gitProvider, String owner, String repo, String commitSha) {
        for (String configFilename : RuffAnalyzer.getConfigFileNames()) {
            try {
                String content = gitProvider.getFileContent(owner, repo, configFilename, commitSha);
                if (content != null && !content.isBlank()) {
                    // pyproject.toml only counts if it has [tool.ruff] section
                    if ("pyproject.toml".equals(configFilename) && !content.contains("[tool.ruff")) {
                        continue;
                    }
                    log.debug("Found Ruff config: {}", configFilename);
                    return new RuffConfig(configFilename, content);
                }
            } catch (Exception e) {
                log.trace("Ruff config not found: {}", configFilename);
            }
        }
        return null;
    }

    private BanditConfig fetchBanditConfig(GitProvider gitProvider, String owner, String repo, String commitSha) {
        for (String configFilename : BanditAnalyzer.getConfigFileNames()) {
            try {
                String content = gitProvider.getFileContent(owner, repo, configFilename, commitSha);
                if (content != null && !content.isBlank()) {
                    // pyproject.toml only counts if it has [tool.bandit] section
                    if ("pyproject.toml".equals(configFilename) && !content.contains("[tool.bandit")) {
                        continue;
                    }
                    log.debug("Found Bandit config: {}", configFilename);
                    return new BanditConfig(configFilename, content);
                }
            } catch (Exception e) {
                log.trace("Bandit config not found: {}", configFilename);
            }
        }
        return null;
    }
}
```

- [ ] **Step 3: Verify it compiles**

Run: `cd /Users/bhuvang/ofb/codelens/codelens-be && ./mvnw compile -pl . -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
cd /Users/bhuvang/ofb/codelens
git add codelens-be/src/main/java/com/codelens/analysis/LintConfigBundle.java codelens-be/src/main/java/com/codelens/analysis/LintConfigService.java
git commit -m "Add LintConfigBundle and LintConfigService"
```

---

### Task 8: Update CombinedAnalysisService to Route Bundle

**Files:**
- Modify: `codelens-be/src/main/java/com/codelens/analysis/CombinedAnalysisService.java`

Add a new `analyzeFile` overload that accepts `LintConfigBundle`. Route each per-language analyzer to its `analyzeWithConfig()` method using the appropriate field from the bundle. Keep the existing `EslintConfig`-based overload for backward compatibility.

- [ ] **Step 1: Add the bundle-based overload and routing**

Open `codelens-be/src/main/java/com/codelens/analysis/CombinedAnalysisService.java`. Make these changes:

**Add an import** at the top (after the existing analyzer imports):
```java
import com.codelens.analysis.java.CheckstyleAnalyzer.CheckstyleConfig;
import com.codelens.analysis.java.PmdAnalyzer.PmdConfig;
import com.codelens.analysis.python.BanditAnalyzer.BanditConfig;
import com.codelens.analysis.python.RuffAnalyzer.RuffConfig;
```

**Add a new public overload** after the existing `analyzeFile(filename, content, organizationId, eslintConfig)` method (around line 148):

```java
/**
 * Analyze a single file with custom rules and a full lint config bundle.
 * The bundle may contain configs for ESLint, PMD, Checkstyle, Ruff, Bandit.
 * Each analyzer uses its config if non-null, or its bundled CodeLens default.
 */
public List<AnalysisIssue> analyzeFile(String filename, String content, UUID organizationId,
                                        LintConfigBundle configBundle) {
    List<AnalysisIssue> allIssues = new ArrayList<>();

    if (!languageDetector.shouldAnalyze(filename)) {
        log.debug("Skipping analysis for unsupported file: {}", filename);
        return allIssues;
    }

    AnalysisLanguageDetector.Language language = languageDetector.detect(filename);
    log.debug("Analyzing {} as {}", filename, language);

    LintConfigBundle bundle = configBundle != null ? configBundle : LintConfigBundle.empty();

    if (runInParallel) {
        allIssues = analyzeInParallelWithBundle(filename, content, language, bundle);
    } else {
        allIssues = analyzeSequentiallyWithBundle(filename, content, language, bundle);
    }

    if (customRuleAnalyzer != null && customRuleAnalyzer.isAvailable()) {
        List<AnalysisIssue> customIssues = customRuleAnalyzer.analyze(filename, content, organizationId);
        allIssues.addAll(customIssues);
    }

    log.info("Found {} issues in {}", allIssues.size(), filename);
    return allIssues;
}
```

**Add the bundle-based parallel routing** (after the existing `analyzeInParallel` method):

```java
private List<AnalysisIssue> analyzeInParallelWithBundle(String filename, String content,
        AnalysisLanguageDetector.Language language, LintConfigBundle bundle) {
    List<CompletableFuture<List<AnalysisIssue>>> futures = new ArrayList<>();

    if (language == AnalysisLanguageDetector.Language.JAVA || language == AnalysisLanguageDetector.Language.KOTLIN) {
        if (pmdAnalyzer.isAvailable()) {
            PmdConfig pmdConfig = bundle.pmdConfig();
            futures.add(CompletableFuture.supplyAsync(
                () -> pmdAnalyzer.analyzeWithConfig(filename, content, pmdConfig), executor
            ));
        }
        if (spotBugsAnalyzer.isAvailable()) {
            futures.add(CompletableFuture.supplyAsync(
                () -> spotBugsAnalyzer.analyze(filename, content), executor
            ));
        }
        if (checkstyleAnalyzer.isAvailable()) {
            CheckstyleConfig csConfig = bundle.checkstyleConfig();
            futures.add(CompletableFuture.supplyAsync(
                () -> checkstyleAnalyzer.analyzeWithConfig(filename, content, csConfig), executor
            ));
        }
    }

    if (languageDetector.isJavaScriptFamily(filename)) {
        if (eslintAnalyzer.isAvailable()) {
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
    }

    if (language == AnalysisLanguageDetector.Language.PYTHON) {
        if (ruffAnalyzer.isAvailable()) {
            RuffConfig ruffConfig = bundle.ruffConfig();
            futures.add(CompletableFuture.supplyAsync(
                () -> ruffAnalyzer.analyzeWithConfig(filename, content, ruffConfig), executor
            ));
        }
        if (banditAnalyzer.isAvailable()) {
            BanditConfig banditConfig = bundle.banditConfig();
            futures.add(CompletableFuture.supplyAsync(
                () -> banditAnalyzer.analyzeWithConfig(filename, content, banditConfig), executor
            ));
        }
    }

    if (language == AnalysisLanguageDetector.Language.GO) {
        if (staticcheckAnalyzer.isAvailable()) {
            futures.add(CompletableFuture.supplyAsync(
                () -> staticcheckAnalyzer.analyze(filename, content), executor
            ));
        }
        if (gosecAnalyzer.isAvailable()) {
            futures.add(CompletableFuture.supplyAsync(
                () -> gosecAnalyzer.analyze(filename, content), executor
            ));
        }
    }

    if (language == AnalysisLanguageDetector.Language.RUST) {
        if (clippyAnalyzer.isAvailable()) {
            futures.add(CompletableFuture.supplyAsync(
                () -> clippyAnalyzer.analyze(filename, content), executor
            ));
        }
    }

    return futures.stream()
        .map(CompletableFuture::join)
        .flatMap(List::stream)
        .collect(Collectors.toList());
}

private List<AnalysisIssue> analyzeSequentiallyWithBundle(String filename, String content,
        AnalysisLanguageDetector.Language language, LintConfigBundle bundle) {
    List<AnalysisIssue> allIssues = new ArrayList<>();

    if (language == AnalysisLanguageDetector.Language.JAVA || language == AnalysisLanguageDetector.Language.KOTLIN) {
        if (pmdAnalyzer.isAvailable()) {
            allIssues.addAll(pmdAnalyzer.analyzeWithConfig(filename, content, bundle.pmdConfig()));
        }
        if (spotBugsAnalyzer.isAvailable()) {
            allIssues.addAll(spotBugsAnalyzer.analyze(filename, content));
        }
        if (checkstyleAnalyzer.isAvailable()) {
            allIssues.addAll(checkstyleAnalyzer.analyzeWithConfig(filename, content, bundle.checkstyleConfig()));
        }
    }

    if (languageDetector.isJavaScriptFamily(filename)) {
        if (eslintAnalyzer.isAvailable()) {
            EslintConfig eslintConfig = bundle.eslintConfig();
            if (eslintConfig != null) {
                allIssues.addAll(eslintAnalyzer.analyzeWithConfig(
                    filename, content, eslintConfig.configFilename(), eslintConfig.configContent()));
            } else {
                allIssues.addAll(eslintAnalyzer.analyze(filename, content));
            }
        }
    }

    if (language == AnalysisLanguageDetector.Language.PYTHON) {
        if (ruffAnalyzer.isAvailable()) {
            allIssues.addAll(ruffAnalyzer.analyzeWithConfig(filename, content, bundle.ruffConfig()));
        }
        if (banditAnalyzer.isAvailable()) {
            allIssues.addAll(banditAnalyzer.analyzeWithConfig(filename, content, bundle.banditConfig()));
        }
    }

    if (language == AnalysisLanguageDetector.Language.GO) {
        if (staticcheckAnalyzer.isAvailable()) {
            allIssues.addAll(staticcheckAnalyzer.analyze(filename, content));
        }
        if (gosecAnalyzer.isAvailable()) {
            allIssues.addAll(gosecAnalyzer.analyze(filename, content));
        }
    }

    if (language == AnalysisLanguageDetector.Language.RUST) {
        if (clippyAnalyzer.isAvailable()) {
            allIssues.addAll(clippyAnalyzer.analyze(filename, content));
        }
    }

    return allIssues;
}
```

Note: Keep the existing `analyzeFile(..., EslintConfig)` overload and the existing `analyzeInParallel` / `analyzeSequentially` methods unchanged for backward compatibility.

- [ ] **Step 2: Verify it compiles**

Run: `cd /Users/bhuvang/ofb/codelens/codelens-be && ./mvnw compile -pl . -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
cd /Users/bhuvang/ofb/codelens
git add codelens-be/src/main/java/com/codelens/analysis/CombinedAnalysisService.java
git commit -m "Route lint config bundle through analysis service"
```

---

### Task 9: Wire ReviewEngine to Use LintConfigService

**Files:**
- Modify: `codelens-be/src/main/java/com/codelens/core/ReviewEngine.java`

Inject `LintConfigService`. Replace the existing `fetchEslintConfig()` calls (lines 202 and 1724) with a single `lintConfigService.fetchAll(...)` call. Pass the `LintConfigBundle` through `reviewFile()` to `staticAnalysisService.analyzeFile(..., bundle)`. Update both PR and commit review code paths.

- [ ] **Step 1: Add imports**

Open `codelens-be/src/main/java/com/codelens/core/ReviewEngine.java`. Find the imports section near the top. Add:

```java
import com.codelens.analysis.LintConfigBundle;
import com.codelens.analysis.LintConfigService;
```

The existing `EslintConfig` import can stay — it's still used by some signatures.

- [ ] **Step 2: Inject LintConfigService**

Find the field declarations in `ReviewEngine` (the `private final` fields near the top of the class). Add:
```java
private final LintConfigService lintConfigService;
```

Add `LintConfigService lintConfigService` to the constructor parameter list AND assign it: `this.lintConfigService = lintConfigService;`. The other constructor parameters and assignments remain unchanged.

- [ ] **Step 3: Replace fetchEslintConfig call in PR review path (line 202)**

Find this code block (around line 200-205):
```java
final EslintConfig eslintConfig = fetchEslintConfig(gitProvider, request.owner(), request.repo(), prInfo.headCommitSha());
if (eslintConfig != null) {
    log.info("Using project ESLint config: {}", eslintConfig.configFilename());
}
```

Replace with:
```java
final LintConfigBundle lintConfigBundle = lintConfigService.fetchAll(
    gitProvider, request.owner(), request.repo(), prInfo.headCommitSha());
if (lintConfigBundle.eslintConfig() != null) {
    log.info("Using project ESLint config: {}", lintConfigBundle.eslintConfig().configFilename());
}
final EslintConfig eslintConfig = lintConfigBundle.eslintConfig();
```

- [ ] **Step 4: Pass bundle to reviewFile in PR path (line 313-317)**

Find this code block (around line 313-317):
```java
FileReviewResult fileResult = reviewFile(
    request, gitProvider, prInfo, file, parsedDiffs,
    request.organizationId(), eslintConfig, repoRules,
    learningContext
);
```

Replace with:
```java
FileReviewResult fileResult = reviewFile(
    request, gitProvider, prInfo, file, parsedDiffs,
    request.organizationId(), lintConfigBundle, repoRules,
    learningContext
);
```

- [ ] **Step 5: Update reviewFile signature (line 517-526)**

Find the `reviewFile` method declaration (around line 517-526):
```java
private FileReviewResult reviewFile(
        ReviewRequest request,
        GitProvider gitProvider,
        PullRequestInfo prInfo,
        ChangedFile file,
        List<DiffParser.FileDiff> parsedDiffs,
        java.util.UUID organizationId,
        EslintConfig eslintConfig,
        String customRepoRules,
        com.codelens.service.LearningService.RepoLearningContext learningContext) {
```

Replace `EslintConfig eslintConfig,` with `LintConfigBundle lintConfigBundle,`. The new signature:
```java
private FileReviewResult reviewFile(
        ReviewRequest request,
        GitProvider gitProvider,
        PullRequestInfo prInfo,
        ChangedFile file,
        List<DiffParser.FileDiff> parsedDiffs,
        java.util.UUID organizationId,
        LintConfigBundle lintConfigBundle,
        String customRepoRules,
        com.codelens.service.LearningService.RepoLearningContext learningContext) {
```

- [ ] **Step 6: Update local variable and analyzeFile call inside reviewFile (lines 572-584)**

Find this code block inside `reviewFile` (around line 572-584):
```java
final java.util.UUID finalOrgId = organizationId;
final EslintConfig finalEslintConfig = eslintConfig;
```

Replace with:
```java
final java.util.UUID finalOrgId = organizationId;
final LintConfigBundle finalLintConfigBundle = lintConfigBundle;
```

Then find the `staticAnalysisService.analyzeFile` call (around line 583-584):
```java
List<AnalysisIssue> analysisIssues = staticAnalysisService.analyzeFile(
    file.filename(), finalFileContent, finalOrgId, finalEslintConfig);
```

Replace with:
```java
List<AnalysisIssue> analysisIssues = staticAnalysisService.analyzeFile(
    file.filename(), finalFileContent, finalOrgId, finalLintConfigBundle);
```

- [ ] **Step 7: Apply identical changes to the commit review path**

Find the equivalent code in the commit review path (around line 1724):
```java
final EslintConfig eslintConfig = fetchEslintConfig(gitProvider, request.owner(), request.repo(), request.commitSha());
if (eslintConfig != null) {
    log.info("Using project ESLint config: {}", eslintConfig.configFilename());
}
```

Replace with:
```java
final LintConfigBundle lintConfigBundle = lintConfigService.fetchAll(
    gitProvider, request.owner(), request.repo(), request.commitSha());
if (lintConfigBundle.eslintConfig() != null) {
    log.info("Using project ESLint config: {}", lintConfigBundle.eslintConfig().configFilename());
}
final EslintConfig eslintConfig = lintConfigBundle.eslintConfig();
```

Find the `reviewCommitFile` call (around line 1819):
```java
allIssues.addAll(reviewCommitFile(
    request, gitProvider, file, parsedDiffs,
    request.organizationId(), eslintConfig, repoRules,
    learningContext
).issues());
```

Replace with:
```java
allIssues.addAll(reviewCommitFile(
    request, gitProvider, file, parsedDiffs,
    request.organizationId(), lintConfigBundle, repoRules,
    learningContext
).issues());
```

Find the `reviewCommitFile` method declaration (around line 1925):
```java
private FileReviewResult reviewCommitFile(
        ...
        EslintConfig eslintConfig,
        ...
```

Replace `EslintConfig eslintConfig,` with `LintConfigBundle lintConfigBundle,`.

Find the local variable and analyzeFile call inside `reviewCommitFile` (around line 1970):
```java
final EslintConfig finalEslintConfig = eslintConfig;
```
Replace with:
```java
final LintConfigBundle finalLintConfigBundle = lintConfigBundle;
```

And the corresponding `staticAnalysisService.analyzeFile(...)` call inside this method — replace `finalEslintConfig` with `finalLintConfigBundle` (the call signature change is the same as Step 6).

- [ ] **Step 8: Remove the now-unused `fetchEslintConfig` method**

Find the `fetchEslintConfig` method (lines 1530-1556) and DELETE it entirely. The logic now lives in `LintConfigService.fetchEslintConfig` (private). The `EslintConfig` import remains because it's still used as a local variable type.

- [ ] **Step 9: Verify it compiles**

Run: `cd /Users/bhuvang/ofb/codelens/codelens-be && ./mvnw compile -pl . -q`
Expected: BUILD SUCCESS

- [ ] **Step 10: Commit**

```bash
cd /Users/bhuvang/ofb/codelens
git add codelens-be/src/main/java/com/codelens/core/ReviewEngine.java
git commit -m "Wire lint config bundle through ReviewEngine"
```

---

### Task 10: Document Security Floor in README

**Files:**
- Modify: `codelens-be/README.md`

Add a section documenting which lint configs are picked up from the repo and what the security floor is for each analyzer.

- [ ] **Step 1: Add the section**

Open `codelens-be/README.md`. Find a good location near the existing static analysis documentation (look for sections on PMD, Checkstyle, ESLint, Ruff, or Bandit). Add this new section:

```markdown
## Lint Configuration Pickup

CodeLens automatically picks up lint configurations from the repo being reviewed.
For each analyzer below, the listed config files are searched (in order) at the
PR head commit. The first match is used in place of the bundled CodeLens default.

A **security floor** is always enforced on top of the repo's config — these rules
cannot be disabled by the project.

### ESLint (JavaScript/TypeScript)

**Config files searched:**
- `.eslintrc`, `.eslintrc.js`, `.eslintrc.cjs`, `.eslintrc.json`, `.eslintrc.yaml`, `.eslintrc.yml`
- `eslint.config.js`, `eslint.config.mjs`, `eslint.config.cjs` (flat config)
- `package.json` (the `eslintConfig` field)

**Security floor:** None enforced separately — ESLint rules from the project config
run as-is. (CodeLens still applies severity mapping for security-related rule names.)

### PMD (Java)

**Config files searched:**
- `pmd-ruleset.xml`, `.pmd-ruleset.xml`, `config/pmd/ruleset.xml`

**Security floor (always enforced):**
- All rules from PMD's `category/java/security.xml`
- `errorprone/AvoidCatchingThrowable`, `errorprone/EmptyCatchBlock`, `errorprone/AvoidLiteralsInIfCondition`

### Checkstyle (Java)

**Config files searched:**
- `checkstyle.xml`, `config/checkstyle/checkstyle.xml`, `.checkstyle.xml`

**Security floor (always enforced):**
- `EmptyCatchBlock`
- `IllegalCatch`
- `IllegalThrows`
- `NoClone`
- `NoFinalizer`

### Ruff (Python)

**Config files searched:**
- `ruff.toml`, `.ruff.toml`
- `pyproject.toml` (only when it contains a `[tool.ruff]` section)

**Security floor (always enforced):**
- `--extend-select S` (flake8-bandit security rules)
- `--extend-select B` (flake8-bugbear bug-prone patterns)

### Bandit (Python)

**Config files searched:**
- `.bandit`, `bandit.yaml`
- `pyproject.toml` (only when it contains a `[tool.bandit]` section)

**Security floor (always enforced):**
- `--severity-level low` (cannot raise the severity threshold)
- `--confidence-level medium` (medium and high confidence findings always surface)

### Behavior Notes

- If no project config is found, the bundled CodeLens defaults are used (current behavior, unchanged).
- If a project config is malformed or fails to load, a WARN log is emitted and the analyzer falls back to defaults.
- Configs are fetched from the **PR's head branch**, not main. New rules added in a PR apply to that PR's review.
- Security floor rules cannot be disabled by repo config — they are layered on top.
```

- [ ] **Step 2: Commit**

```bash
cd /Users/bhuvang/ofb/codelens
git add codelens-be/README.md
git commit -m "Document lint config pickup and security floor"
```

---

## Self-Review Notes

Spec coverage check:
- ✅ PMD config pickup + security floor → Tasks 1, 3
- ✅ Checkstyle config pickup + two-pass security floor → Tasks 2, 4
- ✅ Ruff config pickup + extend-select → Task 5
- ✅ Bandit config pickup + severity override → Task 6
- ✅ Single LintConfigService orchestrator → Task 7
- ✅ Plumbing through CombinedAnalysisService → Task 8
- ✅ Plumbing through ReviewEngine (PR + commit paths) → Task 9
- ✅ Backend logging → Built into Tasks 3-6 (each analyzer logs its config decision)
- ✅ README documentation → Task 10
- ✅ Edge cases: malformed config (WARN + fallback), no config (defaults), pyproject.toml without tool section → Built into Tasks 5, 6, 7
