# Changelog

All notable changes to CodeLens will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.0.0] - 2025-01-07

### Added

- **Multi-LLM Support**: Claude, Gemini, GPT-4, GLM, and Ollama providers
- **Smart LLM Routing**: Automatic provider selection based on task type
- **Static Analysis Integration**:
  - Java: PMD, Checkstyle, SpotBugs
  - JavaScript/TypeScript: ESLint
  - Python: Ruff, Bandit
  - Go: Staticcheck, Gosec
  - Rust: Clippy
- **CVE Detection**: npm audit, pip-audit, OWASP Dependency-Check
- **GitHub Integration**: PR reviews, webhooks, inline comments
- **GitLab Integration**: MR reviews, webhooks
- **Real-time Progress**: WebSocket-based live review updates
- **Custom Review Rules**: Regex-based custom rules per organization
- **Cost Tracking**: LLM usage and cost analytics per organization
- **Notifications**: In-app notifications for review completion
- **Webhooks**: Outgoing webhooks for CI/CD integration
- **Review Cancellation**: Cancel in-progress reviews
- **Diff Viewer**: Built-in diff viewer with syntax highlighting
- **Inline PR Comments**: Automatic comments on critical/high issues (enabled by default)
- **Google OAuth**: Authentication via Google accounts
- **GitHub OAuth**: Optional authentication via GitHub
- **Organization Management**: Multi-tenant support with team management
- **Secret Redaction**: Automatic redaction of secrets in review output

### Security

- Encrypted storage for Git provider tokens
- JWT-based authentication with refresh tokens
- HMAC verification for incoming webhooks
- Rate limiting for API endpoints
- CORS configuration support

---

## Version History

| Version | Date | Highlights |
|---------|------|------------|
| 1.0.0 | 2025-01-07 | Initial open source release |

[Unreleased]: https://github.com/bhuvangupta/codelens/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/bhuvangupta/codelens/releases/tag/v1.0.0
