# Repository Customization

CodeLens automatically detects and uses configuration files from your repository to provide more accurate and context-aware code reviews.

## Custom Review Rules

Add project-specific review guidelines that the AI will follow during code reviews.

### Setup

Create a file at `.codelens/review-rules.md` in your repository:

```
your-repo/
├── .codelens/
│   └── review-rules.md    # Custom review rules
├── src/
└── ...
```

### Example

```markdown
# Project Review Rules

## Code Style
- Use camelCase for variables and functions
- Use PascalCase for classes and components
- Maximum line length: 100 characters

## Architecture
- Controllers should not contain business logic
- All database access must go through repository classes
- Use dependency injection, avoid static methods

## Security
- Never log sensitive data (passwords, tokens, PII)
- All API endpoints must have authentication
- Validate all user input

## Testing
- All new features must have unit tests
- Minimum 80% code coverage for new code
- Integration tests required for API endpoints

## Naming Conventions
- REST endpoints: /api/v1/resource-name (kebab-case)
- Database tables: snake_case
- Environment variables: SCREAMING_SNAKE_CASE
```

### Limits

- Maximum size: 5,000 characters (~1,500 tokens)
- Larger files will be truncated with a warning

---

## ESLint Configuration

CodeLens automatically detects and uses your project's ESLint configuration for JavaScript/TypeScript analysis.

### Supported Config Files

CodeLens looks for these files in order:

1. `.eslintrc`
2. `.eslintrc.js`
3. `.eslintrc.cjs`
4. `.eslintrc.json`
5. `.eslintrc.yaml`
6. `.eslintrc.yml`
7. `eslint.config.js` (flat config)
8. `eslint.config.mjs`
9. `eslint.config.cjs`

### How It Works

1. When reviewing a PR, CodeLens fetches your ESLint config from the repository
2. The config is applied when running ESLint analysis
3. Your custom rules, plugins, and extends are respected
4. Results appear alongside AI-generated findings

### Example `.eslintrc.json`

```json
{
  "extends": ["eslint:recommended", "plugin:@typescript-eslint/recommended"],
  "parser": "@typescript-eslint/parser",
  "plugins": ["@typescript-eslint"],
  "rules": {
    "no-unused-vars": "error",
    "no-console": "warn",
    "@typescript-eslint/explicit-function-return-type": "error"
  }
}
```

---

## Other Auto-Detected Configs

CodeLens also respects these project configurations:

| File | Purpose |
|------|---------|
| `pom.xml` | Java project detection, PMD/Checkstyle analysis |
| `package.json` | Node.js detection, npm audit for vulnerabilities |
| `requirements.txt` / `pyproject.toml` | Python detection, pip-audit for vulnerabilities |
| `go.mod` | Go detection, Staticcheck/Gosec analysis |
| `Cargo.toml` | Rust detection, Clippy analysis |

---

## Ignoring Files or Lines

### Ignore Entire Files

Add files to your `.gitignore` or use path patterns in the PR to exclude them from review.

### Ignore Specific Lines

Use standard linter ignore comments:

```javascript
// eslint-disable-next-line
const x = 'ignored by eslint';

/* eslint-disable */
// This entire block is ignored
/* eslint-enable */
```

```python
# noqa: E501
long_line_that_exceeds_limit = "..."

# type: ignore
untyped_variable = some_function()
```

```java
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
public class MyClass {
    // PMD warning suppressed
}
```

---

## Best Practices

1. **Keep rules focused**: Only include rules specific to your project
2. **Be specific**: Vague rules lead to inconsistent review feedback
3. **Update regularly**: Keep rules in sync with your team's standards
4. **Use examples**: Include code examples in your rules for clarity
5. **Prioritize**: List most important rules first (file may be truncated)

---

## Email Notifications

CodeLens can send email notifications when reviews complete, fail, or find critical issues.

### Setup

1. Configure SMTP in your `.env` file:

```bash
SMTP_ENABLED=true
SMTP_HOST=smtp.gmail.com
SMTP_PORT=587
SMTP_USERNAME=your-email@gmail.com
SMTP_PASSWORD=your-app-password
SMTP_FROM=noreply@yourdomain.com
SMTP_FROM_NAME=CodeLens
CODELENS_BASE_URL=https://codelens.yourdomain.com
```

2. Users enable email notifications in **Settings > Notifications**

### Email Types

| Email | Trigger | Description |
|-------|---------|-------------|
| Review Completed | Review finishes successfully | Shows issue count and critical issues |
| Review Failed | Review encounters an error | Shows error message |
| Critical Issues | Critical issues found | Highlights critical count with alert |

### Customizing Email Templates

Email templates are Thymeleaf HTML files located at:

```
codelens-be/src/main/resources/templates/email/
├── base.html              # Base template (reference)
├── review-completed.html  # Green header, issue stats
├── review-failed.html     # Red header, error details
└── critical-issues.html   # Red alert banner
```

#### Template Variables

| Variable | Available In | Description |
|----------|--------------|-------------|
| `${userName}` | All | User's display name |
| `${prTitle}` | All | Pull request title |
| `${reviewUrl}` | All | Link to review page |
| `${baseUrl}` | All | Base URL for settings links |
| `${issuesFound}` | review-completed | Total issues found |
| `${criticalIssues}` | review-completed | Critical issue count |
| `${criticalCount}` | critical-issues | Critical issue count |
| `${errorMessage}` | review-failed | Error message |

#### Example Customization

To change the header color from green to blue in `review-completed.html`:

```html
<!-- Before -->
<td style="background-color: #059669; padding: 24px 32px;">

<!-- After -->
<td style="background-color: #2563eb; padding: 24px 32px;">
```

Changes take effect after restarting the backend.

### Gmail SMTP Setup

For Gmail, use an App Password (not your regular password):

1. Enable 2-Factor Authentication on your Google account
2. Go to [Google App Passwords](https://myaccount.google.com/apppasswords)
3. Generate a new app password for "Mail"
4. Use this password as `SMTP_PASSWORD`

### Other SMTP Providers

| Provider | Host | Port |
|----------|------|------|
| Gmail | smtp.gmail.com | 587 |
| Outlook | smtp.office365.com | 587 |
| SendGrid | smtp.sendgrid.net | 587 |
| Mailgun | smtp.mailgun.org | 587 |
| Amazon SES | email-smtp.{region}.amazonaws.com | 587 |
