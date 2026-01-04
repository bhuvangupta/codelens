# CodeLens Onboarding Guide

Complete setup guide for CodeLens - from local development to production deployment.

## Table of Contents

1. [Screenshots](#screenshots)
2. [Prerequisites](#prerequisites)
3. [Quick Start](#quick-start)
4. [Configuration](#configuration)
5. [OAuth Setup (Required)](#oauth-setup-required)
6. [Admin Setup](#admin-setup)
7. [Webhook Setup (Optional)](#webhook-setup-optional)
8. [Verification](#verification)
9. [Troubleshooting](#troubleshooting)

---

## Screenshots

### Dashboard
![Dashboard](screenshots/2_dashboard.jpg)
*Overview of recent reviews and key metrics*

### Submit a Review
![Submit Review](screenshots/4_submit_review.jpg)
*Paste any GitHub or GitLab pull request URL*

### Review Details
![Review Details](screenshots/5_review_details_1.jpg)
*AI-generated findings organized by severity*

![Review Details - Issues](screenshots/5_review_details_2.jpg)
*Detailed issue descriptions with suggested fixes*

### Analytics
![Analytics](screenshots/6_analytics.jpg)
*Track review trends, issue patterns, and LLM costs*

### Settings - Custom Rules
![Custom Rules](screenshots/8_settings_custom_rules.jpg)
*Define your own review rules with regex patterns*

### Settings - Webhooks
![Webhooks](screenshots/9_settings_webhooks.jpg)
*Configure webhooks for CI/CD integration*

---

## Prerequisites

### Required

| Tool | Version | Purpose |
|------|---------|---------|
| Java | 17+ | Backend runtime |
| Node.js | 18+ | Frontend & ESLint |
| MySQL | 8.0+ | Database |
| Maven | 3.8+ | Build tool (or use `./mvnw`) |

### Optional Static Analysis Tools

CodeLens auto-detects available tools. Install what you need:

```bash
# Python
pip install ruff bandit pip-audit

# Go
go install honnef.co/go/tools/cmd/staticcheck@latest
go install github.com/securego/gosec/v2/cmd/gosec@latest

# Rust
rustup component add clippy
```

---

## Quick Start

### 1. Database

```sql
CREATE DATABASE codelens CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'codelens'@'localhost' IDENTIFIED BY 'your-secure-password';
GRANT ALL PRIVILEGES ON codelens.* TO 'codelens'@'localhost';
FLUSH PRIVILEGES;
```

### 2. Backend

```bash
cd codelens-be
cp .env.example .env
# Edit .env with your credentials
./mvnw spring-boot:run
```

Backend runs on http://localhost:9292

### 3. Frontend

```bash
cd codelens-fe
cp .env.example .env
# Edit .env
npm install
npm run dev
```

Frontend runs on http://localhost:5174

---

## Configuration

### Backend Environment Variables

Create `.env` in `codelens-be/`:

#### Required

```bash
# Database
DB_URL=jdbc:mysql://localhost:3306/codelens
DB_USERNAME=codelens
DB_PASSWORD=your-secure-password

# Security (generate with: openssl rand -base64 32)
JWT_SECRET=your-jwt-secret-min-32-chars
ENCRYPTION_KEY=your-encryption-key-min-32-chars

# OAuth
GOOGLE_CLIENT_ID=your-google-client-id
GOOGLE_CLIENT_SECRET=your-google-client-secret
```

#### LLM Providers (at least one required)

```bash
# Anthropic Claude (recommended)
ANTHROPIC_API_KEY=your-key    # https://console.anthropic.com/

# Google Gemini
GOOGLE_AI_API_KEY=your-key    # https://makersuite.google.com/app/apikey

# OpenAI
OPENAI_API_KEY=your-key       # https://platform.openai.com/api-keys

# GLM (ZhipuAI)
GLM_API_KEY=your-key          # https://open.bigmodel.cn/

# Ollama (local)
OLLAMA_BASE_URL=http://localhost:11434
```

#### Git Providers (at least one required)

```bash
# GitHub
GITHUB_TOKEN=your-personal-access-token
# Create at: https://github.com/settings/tokens
# Scopes: repo, read:org

# GitLab
GITLAB_TOKEN=your-personal-access-token
GITLAB_URL=https://gitlab.com
# Create at: https://gitlab.com/-/profile/personal_access_tokens
# Scopes: api, read_repository
```

#### Optional

```bash
# Webhook secrets (recommended for production)
GITHUB_WEBHOOK_SECRET=your-secret
GITLAB_WEBHOOK_SECRET=your-secret

# GitHub OAuth (for GitHub login)
GITHUB_OAUTH_CLIENT_ID=your-client-id
GITHUB_OAUTH_CLIENT_SECRET=your-client-secret
GITHUB_REQUIRED_ORG=your-org-name  # Restrict login to org members

# Frontend URL for PR comment links
FRONTEND_URL=https://codelens.example.com

# CORS (comma-separated for multiple origins)
CORS_ALLOWED_ORIGINS=http://localhost:5174,https://codelens.example.com
```

### Frontend Environment Variables

Create `.env` in `codelens-fe/`:

```bash
BACKEND_URL=http://localhost:9292
PUBLIC_GOOGLE_LOGIN_ENABLED=true
PUBLIC_GITHUB_LOGIN_ENABLED=false
```

### Application Settings

Key settings in `application.yaml`:

```yaml
codelens:
  llm:
    default-provider: claude  # Options: claude, gemini, openai, glm, ollama
    routing:
      summary: gemini         # Cheap model for summaries
      describe: gemini        # Cheap model for descriptions
      review: claude          # Best model for code review
      security: claude        # Best model for security

  review:
    max-files: 50
    max-lines-per-file: 1000

  analysis:
    parallel: true
    timeout-seconds: 60
```

---

## OAuth Setup (Required)

> **Important**: You must configure at least one OAuth provider (Google or GitHub) for users to log in.

### Google OAuth

1. Go to [Google Cloud Console](https://console.cloud.google.com/)
2. Create/select a project
3. Navigate to **APIs & Services > Credentials**
4. Click **Create Credentials > OAuth client ID**
5. Select **Web application**
6. Add redirect URIs:
   - Dev: `http://localhost:5174/auth/callback/google`
   - Prod: `https://your-domain.com/auth/callback/google`
7. Copy Client ID and Secret to `.env`

### GitHub OAuth (Optional)

1. Go to [GitHub Developer Settings](https://github.com/settings/developers)
2. Click **New OAuth App**
3. Configure:
   - Homepage URL: `http://localhost:5174`
   - Callback URL: `http://localhost:5174/auth/callback/github`
4. Add credentials to backend `.env`
5. Set `PUBLIC_GITHUB_LOGIN_ENABLED=true` in frontend `.env`

---

## Admin Setup

### Using Setup Script (Recommended)

```bash
cd codelens-be

# Basic usage (org name derived from email domain)
DB_PASSWORD=your-db-password ./scripts/setup-admin.sh admin@acme.com

# With explicit org name
DB_PASSWORD=your-db-password ./scripts/setup-admin.sh admin@acme.com "Acme Corp"
```

The admin user can then log in via **Google OAuth** or **GitHub OAuth** (if configured).

### Using SQL

```sql
-- Make existing user an admin
UPDATE users SET role = 'ADMIN' WHERE email = 'admin@acme.com';
```

### User Association Rules

| Scenario | Auto-Joins Org? |
|----------|-----------------|
| `user@acme.com` submits PR from `acme/repo` | Yes |
| `user@gmail.com` submits PR from `acme/repo` | No |

Admins can manually add users via **Settings > Team**.

---

## Webhook Setup (Optional)

Webhooks enable automatic reviews when PRs are opened/updated.

### GitHub

1. Go to repository **Settings > Webhooks > Add webhook**
2. Configure:
   - Payload URL: `https://your-api.com/api/webhooks/github`
   - Content type: `application/json`
   - Secret: Your `GITHUB_WEBHOOK_SECRET`
   - Events: **Pull requests**

### GitLab

1. Go to project **Settings > Webhooks**
2. Configure:
   - URL: `https://your-api.com/api/webhooks/gitlab`
   - Secret token: Your `GITLAB_WEBHOOK_SECRET`
   - Trigger: **Merge request events**

---

## Verification

### Check Services

```bash
# Backend health
curl http://localhost:9292/actuator/health

# Available analyzers
curl http://localhost:9292/api/analyzers

# LLM providers (requires auth)
curl http://localhost:9292/api/llm/providers
```

### End-to-End Test

1. Open http://localhost:5174
2. Sign in with Google
3. Go to **Settings** - verify LLM providers show "Available"
4. Submit a test PR URL

### Supported Languages

| Language | Static Analyzers |
|----------|------------------|
| Java/Kotlin | PMD, Checkstyle, SpotBugs |
| JavaScript/TypeScript | ESLint, npm audit |
| Python | Ruff, Bandit, pip-audit |
| Go | Staticcheck, Gosec |
| Rust | Clippy |
| Other | LLM review only |

---

## Troubleshooting

### Backend won't start

```bash
# Check MySQL
mysql -u codelens -p -e "SELECT 1"

# Check logs
tail -f logs/codelens.log
```

### LLM provider not available

- Verify API key is set correctly
- For Ollama: ensure model is pulled (`ollama pull codellama`)
- Check provider status pages

### GitHub/GitLab not working

- Verify token has required scopes
- Check token hasn't expired
- For private repos, ensure token has repo access

### Webhooks not triggering

- Verify URL is publicly accessible
- Check webhook secret matches
- Review delivery history in GitHub/GitLab settings

---

## Security Checklist

- [ ] Never commit secrets to version control
- [ ] Use strong, unique passwords
- [ ] Enable HTTPS in production
- [ ] Set webhook secrets
- [ ] Rotate tokens periodically
- [ ] Review CORS settings
