# Repo-Level Lint Config Pickup with Security Floor

**Date:** 2026-04-16
**Goal:** Allow repos to override CodeLens's default lint configurations for PMD, Checkstyle, Ruff, and Bandit, while enforcing a non-negotiable security floor.

---

## Background

Currently only **ESLint** picks up project config from the repo being reviewed (`.eslintrc.*`, `eslint.config.*`, or `package.json#eslintConfig`). All other static analyzers — PMD, Checkstyle, Ruff, Bandit, Staticcheck, Gosec, Clippy — use hardcoded CodeLens defaults and ignore any config in the repo.

For Java/Python/TypeScript-heavy teams, this is a friction point: their CI already enforces project-specific lint rules, but CodeLens reviews don't reflect those rules.

## Decision

Implement repo-level config pickup for **PMD, Checkstyle, Ruff, Bandit** with a **"replace with security floor"** model:

- If a repo has its own config, CodeLens uses it instead of the bundled defaults.
- A non-negotiable security floor is always layered on top. The repo cannot disable security-critical rules.

(Go and Rust analyzers are out of scope — they are effectively inactive in source-only review flows.)

## Architecture

Single pattern, mirrored from the existing `fetchEslintConfig()` flow at `ReviewEngine.java:1530`:

```
ReviewEngine.executeReviewInternal()
  ↓ (fetched once per review at PR head SHA)
fetch<Analyzer>Config(gitProvider, owner, repo, headSha)
  ↓
Pass repoConfig (nullable) through CombinedAnalysisService.analyzeFile()
  ↓
Per analyzer: applySecurityFloor(repoConfig) → run analyzer
```

A new orchestrator class `LintConfigService` handles the fetch logic for all four analyzers (the file-name candidate lists, the silent-fallback pattern). Per-analyzer security-floor logic lives in the respective analyzer classes.

## Per-Analyzer Behavior

### PMD (Java)

| | |
|---|---|
| **Files detected (in order)** | `pmd-ruleset.xml`, `.pmd-ruleset.xml`, `config/pmd/ruleset.xml` |
| **Security floor** | New bundled file `security-floor/pmd-security-floor.xml` containing PMD's `category/java/security.xml` rules |
| **Merge strategy** | PMD's `PMDConfiguration.addRuleSet()` accepts multiple rulesets. Load BOTH the repo's ruleset (written to a temp file) AND the security floor (classpath resource) in the same configuration. |
| **Fallback** | If repo provides no config: load only the existing bundled `pmd/codelens-ruleset.xml` (current behavior, unchanged) |

### Checkstyle (Java)

| | |
|---|---|
| **Files detected** | `checkstyle.xml`, `config/checkstyle/checkstyle.xml`, `.checkstyle.xml` |
| **Security floor** | New bundled file `security-floor/checkstyle-security-floor.xml` with: `EmptyCatchBlock`, `IllegalCatch`, `IllegalThrows`, `NoClone`, `NoFinalizer` |
| **Merge strategy** | Two passes. Run Checkstyle once with the repo's config, once with the security floor. Merge issues, deduplicate by `(filename, line, check name)`. |
| **Fallback** | If repo provides no config: use the existing `DEFAULT_CONFIG` text block (current behavior, unchanged) |

### Ruff (Python)

| | |
|---|---|
| **Files detected** | `ruff.toml`, `.ruff.toml`, `pyproject.toml` (look for `[tool.ruff]` section) |
| **Security floor** | CLI flag `--extend-select S,B` (flake8-bandit + flake8-bugbear) — these cannot be disabled by repo config |
| **Merge strategy** | Write the repo's config file to a temp directory alongside the Python source file. Drop the current hardcoded `--select` flag (let repo config decide what runs). Always append `--extend-select S,B` to the command. |
| **Fallback** | If repo provides no config: keep current hardcoded `--select E,F,W,...,RUF` (unchanged) |

### Bandit (Python)

| | |
|---|---|
| **Files detected** | `.bandit`, `bandit.yaml`, `pyproject.toml` (look for `[tool.bandit]` section) |
| **Security floor** | CLI flag `--severity-level low --confidence-level medium` — repo can customize WHICH tests run, but cannot raise the severity floor |
| **Merge strategy** | Write the repo's config to a temp directory. Run with `-c <config-path>` AND override `-ll` to ensure all severity levels surface. |
| **Fallback** | If repo provides no config: keep current `-ll` (medium+) behavior (unchanged) |

## File Structure

### New Files (3)

- `codelens-be/src/main/java/com/codelens/analysis/LintConfigService.java` — orchestrator that fetches lint configs from the git provider for all 4 analyzers
- `codelens-be/src/main/resources/security-floor/pmd-security-floor.xml` — PMD security ruleset
- `codelens-be/src/main/resources/security-floor/checkstyle-security-floor.xml` — Checkstyle security checks

### Modified Files (6)

- `codelens-be/src/main/java/com/codelens/core/ReviewEngine.java` — add 4 fetch calls at review start (mirror `fetchEslintConfig`); pass configs through to `CombinedAnalysisService`
- `codelens-be/src/main/java/com/codelens/analysis/CombinedAnalysisService.java` — accept and route per-analyzer configs
- `codelens-be/src/main/java/com/codelens/analysis/java/PmdAnalyzer.java` — accept optional repo ruleset, load both repo + security floor
- `codelens-be/src/main/java/com/codelens/analysis/java/CheckstyleAnalyzer.java` — accept optional repo config, run two-pass merge
- `codelens-be/src/main/java/com/codelens/analysis/python/RuffAnalyzer.java` — accept optional repo config, write to temp dir, swap `--select` for `--extend-select`
- `codelens-be/src/main/java/com/codelens/analysis/python/BanditAnalyzer.java` — accept optional repo config, override severity floor

### Updated Documentation (1)

- `codelens-be/README.md` — add a section documenting the security floor for each analyzer (which rules are non-negotiable)

## Logging

Each analyzer logs which config it used at INFO level:

```
PMD: Using project ruleset from .pmd-ruleset.xml + security floor (15 rules)
PMD: No project ruleset found, using bundled CodeLens defaults

Checkstyle: Using project config from config/checkstyle/checkstyle.xml + security floor (5 checks)
Checkstyle: No project config found, using bundled CodeLens defaults

Ruff: Using project config from pyproject.toml + security floor (--extend-select S,B)
Ruff: No project config found, using bundled CodeLens defaults

Bandit: Using project config from .bandit + severity floor (--severity-level low)
Bandit: No project config found, using bundled CodeLens defaults
```

## Edge Cases

| Scenario | Behavior |
|---|---|
| Repo config is malformed XML | Log WARN, fall back to defaults, review continues |
| Repo config references missing custom rules | Pass through to the analyzer; let the analyzer decide (likely error logged) |
| Multiple candidate config files exist | First match wins (in declared order) |
| `pyproject.toml` exists but lacks `[tool.ruff]` | Treated as "no config found" |
| Network/auth error fetching config | Same as ESLint pattern: log TRACE, treat as "no config found" |
| Repo config disables a security-floor rule | Security floor re-enables it (rule still fires) |

## Out of Scope

- Go (Staticcheck/Gosec) and Rust (Clippy) — these are inactive in current source-only review flow
- Per-organization or per-user lint configs (only per-repo)
- Config merge UI / preview
- TypeScript compiler integration (`tsconfig.json`) — TS files go through ESLint only
- SpotBugs config — SpotBugs needs bytecode and is effectively inactive
