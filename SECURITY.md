# Security Policy

## Supported Versions

| Version | Supported          |
| ------- | ------------------ |
| 1.x.x   | :white_check_mark: |

## Reporting a Vulnerability

We take security vulnerabilities seriously. If you discover a security issue, please report it responsibly.

### How to Report

**Please do NOT report security vulnerabilities through public GitHub issues.**

Instead, please report them via email to: **bhuvangupta@yahoo.com**

Include the following information in your report:

- Type of vulnerability (e.g., SQL injection, XSS, authentication bypass)
- Location of the affected code (file path, line numbers if known)
- Step-by-step instructions to reproduce the issue
- Proof of concept or exploit code (if available)
- Potential impact of the vulnerability

### What to Expect

- **Acknowledgment**: We will acknowledge receipt of your report within 48 hours
- **Assessment**: We will investigate and assess the vulnerability within 7 days
- **Updates**: We will keep you informed of our progress
- **Resolution**: We aim to resolve critical vulnerabilities within 30 days
- **Credit**: We will credit you in our security advisories (unless you prefer to remain anonymous)

### Safe Harbor

We consider security research conducted in accordance with this policy to be:

- Authorized under applicable anti-hacking laws
- Exempt from DMCA restrictions on circumvention
- Conducted in good faith

We will not pursue legal action against researchers who:

- Act in good faith to avoid privacy violations and data destruction
- Only interact with accounts they own or have explicit permission to access
- Do not exploit vulnerabilities beyond what is necessary to demonstrate the issue

## Security Best Practices for Deployment

When deploying CodeLens, ensure you:

1. **Use strong secrets**: Generate unique values for `JWT_SECRET` and `ENCRYPTION_KEY` (minimum 32 characters)
   ```bash
   openssl rand -base64 32
   ```

2. **Secure database credentials**: Use strong, unique passwords for MySQL

3. **Enable HTTPS**: Always use TLS in production environments

4. **Restrict CORS**: Configure `CORS_ALLOWED_ORIGINS` to only allow your frontend domain

5. **Protect API keys**: Never commit LLM API keys or Git tokens to version control

6. **Use webhook secrets**: Configure `GITHUB_WEBHOOK_SECRET` and `GITLAB_WEBHOOK_SECRET` to verify webhook authenticity

7. **Regular updates**: Keep dependencies updated to patch known vulnerabilities

## Security Features

CodeLens includes several security features:

- **Encrypted token storage**: Git provider tokens are encrypted at rest
- **JWT authentication**: Secure, stateless authentication with configurable expiration
- **HMAC webhook verification**: Validates webhook payloads from GitHub/GitLab
- **Secret redaction**: Automatically redacts secrets from code review outputs
- **Rate limiting**: Protects against abuse and DoS attacks

## Vulnerability Disclosure Timeline

| Day | Action |
|-----|--------|
| 0 | Vulnerability reported |
| 1-2 | Report acknowledged |
| 3-7 | Vulnerability assessed and confirmed |
| 8-30 | Fix developed and tested |
| 30+ | Fix released, advisory published |

For critical vulnerabilities, we may expedite this timeline.
