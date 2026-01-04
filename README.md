<!-- JMD -->
# CodeLens

AI-powered code review tool for GitHub and GitLab pull requests.

## Overview

CodeLens combines multiple LLM providers with static analysis to provide comprehensive, actionable code reviews. It catches security vulnerabilities, bugs, and performance issues before they reach production.

## Features

- **Multi-LLM Support**: Ollama (local), Claude, Gemini, OpenAI, GLM
- **Static Analysis**: PMD, Checkstyle, SpotBugs (Java), ESLint (JS/TS), Ruff/Bandit (Python), Staticcheck/Gosec (Go), Clippy (Rust)
- **CVE Detection**: npm audit, pip-audit, OWASP Dependency-Check
- **Git Integration**: GitHub and GitLab webhooks
- **Real-time Progress**: Live updates during review
- **Cost Tracking**: Monitor LLM usage and costs
- **Custom Rules**: Add project-specific review rules via `.codelens/review-rules.md`
- **Config Auto-Detection**: Automatically uses your `.eslintrc`, `pom.xml`, etc.

## Screenshots

### Dashboard
![Dashboard](docs/screenshots/2_dashboard.jpg)

### Analytics
![Analytics](docs/screenshots/6_analytics.jpg)

See more screenshots in the [Onboarding Guide](docs/onboarding.md).

## Tech Stack

| Component | Technology |
|-----------|------------|
| Backend | Java 17+ / Spring Boot 3.2+ |
| Frontend | SvelteKit 5 / Tailwind CSS |
| Database | MySQL 8 |
| LLM | LangChain4j |
| Auth | Google OAuth (Auth.js) |

## Project Structure

```
codelens/
├── codelens-be/     # Java Spring Boot backend
├── codelens-fe/     # SvelteKit frontend
└── docs/            # Documentation
```

## Quick Start

### Prerequisites

- Java 17+
- Node.js 18+
- MySQL 8
- [Ollama](https://ollama.ai) (default, runs locally - no API key needed)
  - Or: API key for Claude, Gemini, or OpenAI

### 1. Clone and Configure

```bash
git clone https://github.com/bhuvangupta/codelens.git
cd codelens
```

### 2. Setup Ollama (Default LLM)

```bash
# Install Ollama from https://ollama.ai, then:
ollama pull devstral-small
```

### 3. Backend Setup

```bash
cd codelens-be
cp .env.example .env
# Edit .env with your API keys and database credentials
./mvnw spring-boot:run
```

Backend runs on http://localhost:9292

### 4. Frontend Setup

```bash
cd codelens-fe
cp .env.example .env
# Edit .env with Auth.js secrets
npm install
npm run dev
```

Frontend runs on http://localhost:5174

### 5. Full Stack (Docker)

```bash
docker-compose up -d
```

### 6. Configure OAuth (Required)

Users log in via OAuth. Configure at least one provider:

**Google OAuth** ([Console](https://console.cloud.google.com/apis/credentials)):
```bash
# In codelens-be/.env
GOOGLE_CLIENT_ID=your-client-id
GOOGLE_CLIENT_SECRET=your-client-secret
```

**GitHub OAuth** ([Developer Settings](https://github.com/settings/developers)):
```bash
# In codelens-be/.env
GITHUB_OAUTH_CLIENT_ID=your-client-id
GITHUB_OAUTH_CLIENT_SECRET=your-client-secret

# In codelens-fe/.env
PUBLIC_GITHUB_LOGIN_ENABLED=true
```

Callback URLs: `http://localhost:5174/auth/callback/google` or `/github`

### 7. Setup Admin User

After deployment, create an admin user:

```bash
cd codelens-be
DB_PASSWORD=your-db-password ./scripts/setup-admin.sh admin@yourdomain.com
```

This creates an organization and grants admin access.

## Supported LLM Providers & Models

| Provider | Models | Notes |
|----------|--------|-------|
| **ollama** | `devstral-small`, `codellama`, `llama3.1`, `deepseek-coder-v2`, `qwen2.5-coder` | Default, local, no API key |
| **claude** | `claude-sonnet-4-20250514`, `claude-opus-4-20250514`, `claude-3-5-sonnet-20241022` | Best for complex reviews |
| **gemini** | `gemini-2.5-flash`, `gemini-2.0-flash`, `gemini-1.5-flash` | Fast & cost-effective |
| **gemini-pro** | `gemini-2.5-pro`, `gemini-1.5-pro` | Higher quality |
| **openai** | `gpt-4o`, `gpt-4o-mini`, `gpt-4-turbo`, `o1`, `o1-mini` | Widely used |
| **glm** | `glm-4`, `glm-4-flash` | ZhipuAI (Chinese) |

Set provider in `.env`:
```bash
LLM_DEFAULT_PROVIDER=ollama  # or claude, gemini, openai, glm
OLLAMA_MODEL=devstral-small  # for ollama
```

### Smart Model Routing

Use different models for different tasks to optimize cost and quality:

```yaml
# In application.yaml
routing:
  summary: gemini      # Fast, cheap model for PR summaries
  review: claude       # Best model for detailed code review
  security: claude     # Thorough model for security analysis
  fallback: ollama     # Local fallback when APIs fail
```

**Example**: Use Gemini Flash for quick summaries ($0.01/review), Claude for deep code analysis ($0.05/review), and Ollama as free offline backup.

## Documentation

- [Backend README](codelens-be/README.md)
- [Frontend README](codelens-fe/README.md)
- [Customization Guide](docs/customization.md) - Custom rules, ESLint config, etc.
- [Onboarding Guide](docs/onboarding.md)
- [Jenkins Integration](docs/jenkins-integration.md)
- [Architecture](docs/architecture.md)

## API

### Submit Review

```bash
POST /api/reviews
{
  "prUrl": "https://github.com/owner/repo/pull/123"
}
```

### Get Review Status

```bash
GET /api/reviews/{id}/status
```

See [Backend README](codelens-be/README.md) for full API documentation.

## Contributing

We welcome contributions! Please see [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines.

## License

Licensed under the Apache License 2.0 - see [LICENSE](LICENSE) for details.

---

## Disclaimer

> **⚠️ Experimental Project:** This is an experimental project. It has not been thoroughly tested for production use. Please evaluate and test thoroughly in your own environment before deploying to any production system. Use at your own risk. This project is not yet tested with Gitlab.
