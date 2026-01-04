# CodeLens AI

AI-powered code review tool for GitHub and GitLab pull requests. Combines multiple LLM providers with static analysis to provide comprehensive code reviews.

## Features

### Core Capabilities

- **AI-Powered Code Review**: Analyzes PRs and single commits using LLMs (Claude, Gemini, OpenAI, GLM, Ollama)
- **Single Commit Review**: Review individual commits in addition to full PRs
- **Language-Specific Prompts**: Optimized prompts for Java, Python, Go, Rust, JavaScript/TypeScript
- **Static Analysis Integration**: Multi-language support with 12+ analyzers
- **Security Scanning**: OWASP dependency checks, CVE detection, language-specific security tools
- **Multi-Provider Support**: GitHub and GitLab integration with automatic language detection
- **Real-time Progress Tracking**: Live progress updates during review
- **Cost Tracking**: Monitor LLM usage and costs per provider

### LLM Providers

| Provider | Models | Use Case |
|----------|--------|----------|
| Gemini | gemini-2.5-flash | Default (fast, cost-effective) |
| Gemini Pro | gemini-2.5-pro | Security analysis (thorough) |
| Claude | claude-sonnet-4-20250514 | High-quality reviews |
| OpenAI | gpt-4o | Alternative |
| GLM | glm-4.7 | ZhipuAI |
| Ollama | devstral-small-2 | Local/offline |

### Static Analyzers

| Language | Analyzer | Purpose |
|----------|----------|---------|
| **Java** | PMD | Code smells and best practices |
| **Java** | Checkstyle | Style checking |
| **Java** | SpotBugs | Bug detection |
| **JavaScript/TypeScript** | ESLint | Linting with React/Next.js support |
| **JavaScript/TypeScript** | npm audit | Dependency vulnerabilities |
| **Python** | Ruff | Fast linter (replaces flake8, pylint) |
| **Python** | Bandit | Security vulnerability detection |
| **Python** | pip-audit | Dependency CVE scanning |
| **Go** | Staticcheck | Code quality and bug detection |
| **Go** | Gosec | Security issue detection |
| **Rust** | Clippy | Lints and best practices |

### Language-Specific Prompts

CodeLens uses optimized prompts for each language, reducing token usage by ~35-40%:

| Language | Prompt File | Covers |
|----------|-------------|--------|
| Java/Kotlin | `review-java.txt` | Spring Boot, JPA, concurrency |
| JavaScript/TypeScript | `review-javascript.txt` | React, Next.js, Node.js |
| Python | `review-python.txt` | Django, FastAPI, async |
| Go | `review-go.txt` | Concurrency, error handling |
| Rust | `review-rust.txt` | Memory safety, ownership |
| Other | `review.txt` | Generic best practices |

### Smart Context Extraction (Token Optimization)

CodeLens uses intelligent context extraction to reduce LLM token usage by 40-70% while maintaining review quality.

#### Review Modes

| Mode | When Used | Token Savings |
|------|-----------|---------------|
| `SKIP_LLM` | Config files, docs, assets, simple DTOs | 100% (static analysis only) |
| `DIFF_ONLY` | Simple utilities without DI/hooks | 60-70% |
| `SMART_CONTEXT` | React components, Spring services | 40-50% |

#### Files Automatically Skipped (Static Analysis Only)

- Configuration: `.json`, `.yaml`, `.yml`, `.xml`, `.properties`
- Documentation: `.md`, `.txt`, `.rst`
- Lock files: `package-lock.json`, `yarn.lock`, `poetry.lock`
- Assets: `.css`, `.scss`, `.svg`, `.png`, `.jpg`
- Generated files: `*.generated.java`, `*_pb2.py`
- Simple Java DTOs with Lombok annotations

#### Smart Context Extraction by Language

**JavaScript/TypeScript/React:**
- Extracts imports (React, hooks, types used in diff)
- Extracts state declarations (`useState`, `useReducer`)
- Extracts custom hook declarations
- Extracts TypeScript type definitions referenced in changes

**Java/Spring:**
- Extracts class annotations (`@Service`, `@Transactional`, `@RestController`)
- Extracts `@Autowired`/`@Inject` field declarations
- Extracts method-level annotations for changed methods
- Extracts key imports (Spring, JPA, security)

**Example Token Savings:**
```
Before: 1000-line file → ~4000 input tokens
After:  Smart context  → ~800-1500 input tokens

Per-file savings: 40-70%
Per-PR savings: 50-60% (varies by file types)
```

### Project-Level ESLint Config

CodeLens automatically detects and uses your project's ESLint configuration for JavaScript/TypeScript static analysis.

**Supported config files:**
- `.eslintrc`, `.eslintrc.js`, `.eslintrc.cjs`, `.eslintrc.json`
- `.eslintrc.yaml`, `.eslintrc.yml`
- `eslint.config.js`, `eslint.config.mjs`, `eslint.config.cjs` (flat config)
- `package.json` with `eslintConfig` field

When found, ESLint runs with your project's rules instead of global defaults.

### Custom Review Rules (Per-Repository)

Add project-specific review rules by creating `.codelens/review-rules.md` in your repository:

```markdown
# Backend PR Review Rules

## Architecture (CRITICAL)
- Repository/Entity must not be exposed outside service layer
- Every Entity needs a corresponding DTO
- No database calls inside converters
- Use bulk converters, never call in loops

## JPA/Domain (HIGH)
- All query fields must have @Index
- Use @Enumerated(EnumType.STRING) for enums
- New entities must extend Auditable

## Code Quality (MEDIUM)
- Use Set for unique collections
- Never return null collections
- Use BooleanUtils.isTrue() for null-safe checks
- Round decimals only in DTO layer
```

| Setting | Value |
|---------|-------|
| File path | `.codelens/review-rules.md` |
| Max size | 5000 characters (~1500 tokens) |
| Format | Markdown |

These rules are appended to the base language-specific prompt for all files in that repository.

### Organization Custom Rules (Regex-Based)

Organizations can define custom regex-based rules via the Settings > Rules page or API (`/api/rules`).

#### Rule Properties

| Field | Description | Required |
|-------|-------------|----------|
| `name` | Rule identifier (e.g., `no-console-log`) | Yes |
| `pattern` | Java regex pattern | Yes |
| `severity` | `CRITICAL`, `HIGH`, `MEDIUM`, `LOW` | Yes |
| `category` | Issue category (e.g., `security`, `style`) | Yes |
| `description` | Message shown when rule matches | Yes |
| `suggestion` | How to fix the issue | No |
| `languages` | Limit to specific languages (e.g., `["Java", "Kotlin"]`) | No |

#### Regex Pattern Syntax

Patterns use **Java regex syntax** with the following limitations for security (ReDoS prevention):

| Constraint | Value | Reason |
|------------|-------|--------|
| Timeout | 100ms per line | Prevents slow patterns from blocking reviews |
| Max line length | 2000 chars | Very long lines are skipped |
| Nested quantifiers | **Blocked** | Prevents catastrophic backtracking |

#### Blocked Pattern Examples (ReDoS Risk)

These patterns will be rejected:

```regex
(a+)+          # Nested quantifiers
(a*)*          # Nested quantifiers
(a+){10}       # Quantifier on group with quantifier
(a|aa)+        # Overlapping alternatives with quantifier
(\d+)+         # Nested quantifiers
(.*)*          # Nested quantifiers
```

#### Valid Pattern Examples

```regex
# Find console.log statements
console\.log\s*\(

# Find TODO comments
//\s*TODO

# Find hardcoded passwords
password\s*=\s*["'][^"']+["']

# Find System.out.println in Java
System\.out\.println

# Find debugger statements
\bdebugger\b

# Find SQL injection risks (string concatenation in queries)
executeQuery\s*\(\s*[^)]*\+

# Find insecure random
new Random\(\)

# Find empty catch blocks
catch\s*\([^)]+\)\s*\{\s*\}
```

#### API Usage

```bash
# Create a rule
curl -X POST /api/rules \
  -H "Content-Type: application/json" \
  -d '{
    "name": "no-console-log",
    "pattern": "console\\.log\\s*\\(",
    "severity": "LOW",
    "category": "style",
    "description": "Remove console.log before committing",
    "suggestion": "Use a proper logging framework",
    "languages": ["JavaScript", "TypeScript"]
  }'

# List rules
curl /api/rules

# Delete a rule
curl -X DELETE /api/rules/{id}
```

## Tech Stack

- **Backend**: Java 17+ / Spring Boot 3.2+
- **Database**: MySQL 8
- **Frontend**: SvelteKit 5 with Tailwind CSS
- **Auth**: Google OAuth via Auth.js

## Quick Start

### Prerequisites

- Java 17+
- Node.js 18+
- MySQL 8
- Git

### Optional: Static Analysis Tools

Install the tools for languages you want to analyze:

```bash
# Python
pip install ruff bandit pip-audit

# Go
go install honnef.co/go/tools/cmd/staticcheck@latest
go install github.com/securego/gosec/v2/cmd/gosec@latest

# Rust (clippy comes with rustup)
rustup component add clippy

# JavaScript/TypeScript (auto-installed via npm)
npm install -g eslint
```

### 1. Clone and Configure

```bash
git clone <repo-url>
cd codelens
```

Create `.env` file:

```bash
# Database
DB_USERNAME=root
DB_PASSWORD=your-password

# LLM Providers (at least one required)
GOOGLE_API_KEY=your-gemini-key
ANTHROPIC_API_KEY=your-claude-key
OPENAI_API_KEY=your-openai-key

# GitHub Integration
GITHUB_APP_ID=your-app-id
GITHUB_PRIVATE_KEY_PATH=/path/to/private-key.pem
GITHUB_WEBHOOK_SECRET=your-webhook-secret

# Optional: GitLab
GITLAB_TOKEN=your-gitlab-token
```

### 2. Start Backend

```bash
./mvnw spring-boot:run
```

Backend runs on http://localhost:9292

### 3. Start Frontend

```bash
cd ../codelens-ui
npm install
npm run dev
```

Frontend runs on http://localhost:5174

## Configuration

### application.yaml

```yaml
codelens:
  llm:
    default-provider: gemini  # claude, openai, ollama, glm

    routing:
      summary: gemini    # Fast model for summaries
      review: gemini     # Main review model
      security: claude   # Thorough model for security
      fallback: ollama   # Offline fallback

  review:
    max-files: 100
    parallel-threads: 5      # Concurrent file reviews
    skip-tests: true         # Skip test files
    skip-generated: true     # Skip generated files
    enable-security-scan: true

  analysis:
    enabled: true
    pmd:
      enabled: true
    checkstyle:
      enabled: true
    eslint:
      enabled: true
      react: true
      nextjs: true
```

## API Endpoints

### Submit PR Review

```bash
POST /api/reviews
Content-Type: application/json
Authorization: Bearer <jwt-token>

{
  "prUrl": "https://github.com/owner/repo/pull/123",
  "includeOptimization": false
}
```

### Submit Commit Review

Review a single commit instead of an entire PR.

```bash
POST /api/reviews/commit
Content-Type: application/json
Authorization: Bearer <jwt-token>

{
  "commitUrl": "https://github.com/owner/repo/commit/abc123def",
  "includeOptimization": false
}
```

**Supported commit URL formats:**
- GitHub: `https://github.com/owner/repo/commit/sha`
- GitLab: `https://gitlab.com/owner/repo/-/commit/sha`

### Get Review Status

```bash
GET /api/reviews/{id}/status

Response:
{
  "id": "uuid",
  "status": "IN_PROGRESS",
  "progress": 45,
  "totalFiles": 20,
  "filesReviewed": 9,
  "currentFile": "UserService.java"
}
```

### Get Review Details

```bash
GET /api/reviews/{id}

Response:
{
  "id": "uuid",
  "prUrl": "https://github.com/...",
  "status": "COMPLETED",
  "summary": "...",
  "issues": [...],
  "criticalIssues": 0,
  "highIssues": 2,
  "mediumIssues": 5,
  "lowIssues": 3
}
```

## CI/CD Integration

CodeLens provides a dedicated API for Jenkins, GitHub Actions, GitLab CI, and other CI tools.

See [docs/jenkins-integration.md](docs/jenkins-integration.md) for detailed examples.

### Quick Example

```bash
# Trigger PR review
curl -X POST http://localhost:9292/api/ci/review \
  -H "X-API-Key: your-api-key" \
  -H "Content-Type: application/json" \
  -d '{"prUrl": "https://github.com/owner/repo/pull/123"}'

# Trigger commit review
curl -X POST http://localhost:9292/api/reviews/commit \
  -H "Authorization: Bearer <jwt-token>" \
  -H "Content-Type: application/json" \
  -d '{"commitUrl": "https://github.com/owner/repo/commit/abc123"}'

# Check status
curl http://localhost:9292/api/ci/review/{reviewId} \
  -H "X-API-Key: your-api-key"
```

## Database Management

### Delete a Review

```bash
./scripts/delete-review.sh <review-uuid>
```

Example:
```bash
./scripts/delete-review.sh c1a25dce-e1b7-4f78-b09a-29d945b14466
```

## Project Structure

```
codelens/
├── src/main/java/com/codelens/
│   ├── api/              # REST controllers
│   │   ├── ReviewController.java
│   │   ├── AnalyticsController.java
│   │   ├── CiController.java
│   │   └── dto/          # Request/Response DTOs
│   ├── core/             # Core review engine
│   │   ├── ReviewEngine.java
│   │   ├── SmartContextExtractor.java  # Token optimization
│   │   ├── DiffParser.java
│   │   └── CommentFormatter.java
│   ├── llm/              # LLM integration
│   │   ├── LlmProvider.java
│   │   ├── LlmRouter.java
│   │   └── providers/    # Claude, Gemini, OpenAI, etc.
│   ├── git/              # Git providers
│   │   ├── GitProvider.java
│   │   ├── github/
│   │   └── gitlab/
│   ├── analysis/         # Static analysis
│   │   ├── java/         # PMD, Checkstyle, SpotBugs
│   │   ├── javascript/   # ESLint, npm audit
│   │   ├── python/       # Ruff, Bandit, pip-audit
│   │   ├── go/           # Staticcheck, Gosec
│   │   └── rust/         # Clippy
│   ├── model/entity/     # JPA entities
│   ├── repository/       # Spring Data repos
│   └── service/          # Business logic
├── src/main/resources/
│   ├── application.yaml
│   ├── prompts/          # LLM prompts
│   └── rules/            # Static analysis rules
├── scripts/
│   └── delete-review.sh  # Database utilities
└── docs/
    └── jenkins-integration.md

codelens-ui/
├── src/
│   ├── routes/
│   │   ├── dashboard/    # Main dashboard
│   │   ├── reviews/      # Review list & details
│   │   ├── analytics/    # Usage analytics
│   │   └── settings/     # Configuration
│   └── lib/
│       └── api/          # API client
```

## Features Implemented

### Review Features
- [x] Submit PR for review via URL
- [x] Submit single commit for review via URL
- [x] Duplicate review prevention (same PR + commit SHA)
- [x] Parallel file processing for performance
- [x] Real-time progress tracking with polling
- [x] Skip test files, generated files, lock files
- [x] Priority ordering (controllers/services first)

### Analysis
- [x] AI-powered code review with multiple LLMs
- [x] Language-specific prompts (Java, Python, Go, Rust, JS/TS)
- [x] Automatic language detection from file extensions
- [x] Repository language detection from GitHub/GitLab API
- [x] Static analysis (PMD, Checkstyle, SpotBugs, ESLint, Ruff, Bandit, Staticcheck, Gosec, Clippy)
- [x] Security scanning with CVE detection (npm audit, pip-audit, Bandit, Gosec)
- [x] Combined AI + static analysis results
- [x] Issue severity classification (Critical, High, Medium, Low)

### Git Integration
- [x] GitHub PR fetching and comment posting
- [x] GitLab MR support
- [x] Webhook handlers for auto-review
- [x] Inline comments on high/critical issues

### Dashboard & UI
- [x] Real-time progress bar during review
- [x] Issue grouping by file
- [x] Filter by status (All, Completed, In Progress, Pending)
- [x] Analytics with cost tracking
- [x] Review ID display with copy-to-clipboard
- [x] Recent activity feed with user attribution

### CI/CD
- [x] Dedicated CI API with API key auth
- [x] IP whitelisting support
- [x] Jenkins pipeline examples
- [x] GitHub Actions examples
- [x] GitLab CI examples

### Cost Tracking & Quota Enforcement
- [x] Per-provider cost calculation
- [x] Token usage tracking
- [x] Daily/monthly cost analytics
- [x] Daily cost quota enforcement
- [x] Automatic blocking when quota exceeded

#### Daily Quota Configuration

| Setting | Environment Variable | Default | Description |
|---------|---------------------|---------|-------------|
| Daily Limit | `DAILY_LLM_LIMIT` | `10.00` | Maximum daily spend in USD |
| Enforcement | `ENFORCE_DAILY_LIMIT` | `true` | Block reviews when quota exceeded |

When quota is exceeded, new reviews return HTTP 429 with remaining time until reset (midnight UTC).

```yaml
# application.yaml
codelens:
  cost:
    track-usage: true
    enforce-daily-limit: true
    daily-limit-usd: 10.00
```

### Logging

CodeLens uses file-based logging with automatic rotation in addition to console output.

#### Log Files

| File | Contents |
|------|----------|
| `logs/codelens.log` | Main application log |
| `logs/codelens-error.log` | Errors only |

#### Rotation Settings

| Setting | Value |
|---------|-------|
| Max file size | 100MB (main), 50MB (error) |
| Rotation | Daily + size-based |
| Retention | 30 days |
| Total size cap | 3GB (main), 1GB (error) |
| Compression | gzip on rotation |

#### Configuration

```bash
# Custom log directory
LOG_PATH=/var/log/codelens ./scripts/run.sh prod

# Or via environment variable
export LOG_PATH=/var/log/codelens
```

Log files after rotation:
```
logs/
├── codelens.log                      # Current
├── codelens-error.log                # Current errors
├── codelens-2026-01-05.0.log.gz      # Archived
└── codelens-error-2026-01-05.0.log.gz
```

## Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `SERVER_PORT` | Backend port | 9292 |
| `DB_USERNAME` | MySQL username | root |
| `DB_PASSWORD` | MySQL password | - |
| `LLM_DEFAULT_PROVIDER` | Default LLM | gemini |
| `GOOGLE_API_KEY` | Gemini API key | - |
| `ANTHROPIC_API_KEY` | Claude API key | - |
| `OPENAI_API_KEY` | OpenAI API key | - |
| `ZAI_API_KEY` | ZhipuAI GLM key | - |
| `OLLAMA_BASE_URL` | Ollama server | http://localhost:11434 |
| `GITHUB_APP_ID` | GitHub App ID | - |
| `GITHUB_PRIVATE_KEY_PATH` | Path to .pem file | - |
| `GITHUB_WEBHOOK_SECRET` | Webhook secret | - |
| `GITLAB_TOKEN` | GitLab access token | - |
| `CI_API_KEY_1` | CI/CD API key | - |
| `JWT_SECRET` | JWT signing secret | - |
| `FRONTEND_URL` | Frontend URL for review links in PR comments | http://localhost:5174 |
| `LOG_PATH` | Log file directory | logs |
| `DAILY_LLM_LIMIT` | Daily LLM cost limit in USD | 10.00 |
| `ENFORCE_DAILY_LIMIT` | Enable daily quota enforcement | true |
