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
