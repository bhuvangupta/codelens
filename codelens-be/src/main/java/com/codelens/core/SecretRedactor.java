package com.codelens.core;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects and redacts secrets from code before sending to LLM providers.
 * This prevents accidental leakage of API keys, passwords, tokens, and other sensitive data.
 */
@Slf4j
@Component
public class SecretRedactor {

    private static final String REDACTED = "[REDACTED]";

    /**
     * Secret patterns with their descriptions for logging
     */
    private static final List<SecretPattern> SECRET_PATTERNS = new ArrayList<>();

    static {
        // === Cloud Provider Keys ===

        // AWS Access Key ID (starts with AKIA, ABIA, ACCA, ASIA)
        SECRET_PATTERNS.add(new SecretPattern(
            "AWS Access Key",
            Pattern.compile("\\b(A[KBS]IA[A-Z0-9]{16})\\b")
        ));

        // AWS Secret Access Key (40 char base64)
        SECRET_PATTERNS.add(new SecretPattern(
            "AWS Secret Key",
            Pattern.compile("(?i)(aws_secret_access_key|aws_secret_key|secret_access_key)\\s*[=:]\\s*['\"]?([A-Za-z0-9/+=]{40})['\"]?")
        ));

        // Google Cloud API Key
        SECRET_PATTERNS.add(new SecretPattern(
            "Google API Key",
            Pattern.compile("\\bAIza[A-Za-z0-9_-]{35}\\b")
        ));

        // Azure Storage Key
        SECRET_PATTERNS.add(new SecretPattern(
            "Azure Key",
            Pattern.compile("(?i)(azure[_-]?(storage)?[_-]?(account)?[_-]?key)\\s*[=:]\\s*['\"]?([A-Za-z0-9+/]{86}==)['\"]?")
        ));

        // === Code Platform Tokens ===

        // GitHub tokens (ghp_, gho_, ghs_, ghr_, github_pat_)
        SECRET_PATTERNS.add(new SecretPattern(
            "GitHub Token",
            Pattern.compile("\\b(ghp_[A-Za-z0-9]{36}|gho_[A-Za-z0-9]{36}|ghs_[A-Za-z0-9]{36}|ghr_[A-Za-z0-9]{36}|github_pat_[A-Za-z0-9_]{22,82})\\b")
        ));

        // GitLab tokens
        SECRET_PATTERNS.add(new SecretPattern(
            "GitLab Token",
            Pattern.compile("\\b(glpat-[A-Za-z0-9_-]{20,}|gldt-[A-Za-z0-9_-]{20,})\\b")
        ));

        // Bitbucket App Password
        SECRET_PATTERNS.add(new SecretPattern(
            "Bitbucket Token",
            Pattern.compile("(?i)(bitbucket[_-]?(app)?[_-]?password)\\s*[=:]\\s*['\"]?([A-Za-z0-9]{20,})['\"]?")
        ));

        // === Payment/SaaS Tokens ===

        // Stripe API keys
        SECRET_PATTERNS.add(new SecretPattern(
            "Stripe Key",
            Pattern.compile("\\b(sk_live_[A-Za-z0-9]{24,}|pk_live_[A-Za-z0-9]{24,}|sk_test_[A-Za-z0-9]{24,}|pk_test_[A-Za-z0-9]{24,}|rk_live_[A-Za-z0-9]{24,}|rk_test_[A-Za-z0-9]{24,})\\b")
        ));

        // Slack tokens
        SECRET_PATTERNS.add(new SecretPattern(
            "Slack Token",
            Pattern.compile("\\b(xox[baprs]-[A-Za-z0-9-]{10,})\\b")
        ));

        // Twilio
        SECRET_PATTERNS.add(new SecretPattern(
            "Twilio Key",
            Pattern.compile("\\bSK[a-f0-9]{32}\\b")
        ));

        // SendGrid
        SECRET_PATTERNS.add(new SecretPattern(
            "SendGrid Key",
            Pattern.compile("\\bSG\\.[A-Za-z0-9_-]{22}\\.[A-Za-z0-9_-]{43}\\b")
        ));

        // Mailchimp
        SECRET_PATTERNS.add(new SecretPattern(
            "Mailchimp Key",
            Pattern.compile("\\b[a-f0-9]{32}-us[0-9]{1,2}\\b")
        ));

        // === AI/LLM API Keys ===

        // OpenAI API Key
        SECRET_PATTERNS.add(new SecretPattern(
            "OpenAI Key",
            Pattern.compile("\\bsk-[A-Za-z0-9]{48,}\\b")
        ));

        // Anthropic API Key
        SECRET_PATTERNS.add(new SecretPattern(
            "Anthropic Key",
            Pattern.compile("\\bsk-ant-[A-Za-z0-9_-]{40,}\\b")
        ));

        // === Database Connection Strings ===

        // MongoDB connection string with password
        SECRET_PATTERNS.add(new SecretPattern(
            "MongoDB URI",
            Pattern.compile("mongodb(\\+srv)?://[^:]+:([^@]+)@[^\\s\"']+", Pattern.CASE_INSENSITIVE)
        ));

        // PostgreSQL/MySQL connection string with password
        SECRET_PATTERNS.add(new SecretPattern(
            "Database URI",
            Pattern.compile("(postgres|mysql|jdbc:[a-z]+)://[^:]+:([^@]+)@[^\\s\"']+", Pattern.CASE_INSENSITIVE)
        ));

        // Redis connection with password
        SECRET_PATTERNS.add(new SecretPattern(
            "Redis URI",
            Pattern.compile("redis://[^:]*:([^@]+)@[^\\s\"']+", Pattern.CASE_INSENSITIVE)
        ));

        // === Generic Patterns ===

        // Private Keys (RSA, EC, DSA, etc.)
        SECRET_PATTERNS.add(new SecretPattern(
            "Private Key",
            Pattern.compile("-----BEGIN (RSA |EC |DSA |OPENSSH |PGP )?PRIVATE KEY( BLOCK)?-----[\\s\\S]*?-----END (RSA |EC |DSA |OPENSSH |PGP )?PRIVATE KEY( BLOCK)?-----")
        ));

        // JWT tokens (3 base64 parts separated by dots)
        SECRET_PATTERNS.add(new SecretPattern(
            "JWT Token",
            Pattern.compile("\\beyJ[A-Za-z0-9_-]*\\.[A-Za-z0-9_-]*\\.[A-Za-z0-9_-]*\\b")
        ));

        // Bearer tokens in code
        SECRET_PATTERNS.add(new SecretPattern(
            "Bearer Token",
            Pattern.compile("(?i)(bearer|authorization)\\s*[=:]\\s*['\"]?(Bearer\\s+)?([A-Za-z0-9_-]{20,})['\"]?")
        ));

        // Basic auth (base64 encoded)
        SECRET_PATTERNS.add(new SecretPattern(
            "Basic Auth",
            Pattern.compile("(?i)basic\\s+[A-Za-z0-9+/]{20,}={0,2}")
        ));

        // Generic password assignments
        SECRET_PATTERNS.add(new SecretPattern(
            "Password",
            Pattern.compile("(?i)(password|passwd|pwd|secret|api_key|apikey|api-key|auth_token|access_token|private_key|encryption_key)\\s*[=:]\\s*['\"]([^'\"\\s]{8,})['\"]")
        ));

        // Environment variable style secrets
        SECRET_PATTERNS.add(new SecretPattern(
            "Env Secret",
            Pattern.compile("(?i)(PASSWORD|SECRET|TOKEN|API_KEY|APIKEY|PRIVATE_KEY|ACCESS_KEY|AUTH_KEY)\\s*=\\s*['\"]?([^'\"\\s\\n]{8,})['\"]?")
        ));

        // Hex-encoded secrets (32+ chars, commonly used for encryption keys)
        SECRET_PATTERNS.add(new SecretPattern(
            "Hex Secret",
            Pattern.compile("(?i)(secret|key|token|salt|hash)\\s*[=:]\\s*['\"]?([a-f0-9]{32,})['\"]?")
        ));

        // npm tokens
        SECRET_PATTERNS.add(new SecretPattern(
            "NPM Token",
            Pattern.compile("\\b(npm_[A-Za-z0-9]{36})\\b")
        ));

        // PyPI tokens
        SECRET_PATTERNS.add(new SecretPattern(
            "PyPI Token",
            Pattern.compile("\\b(pypi-[A-Za-z0-9_-]{50,})\\b")
        ));

        // NuGet API Key
        SECRET_PATTERNS.add(new SecretPattern(
            "NuGet Key",
            Pattern.compile("\\b(oy2[A-Za-z0-9]{43})\\b")
        ));
    }

    /**
     * Redact all detected secrets from the given content.
     *
     * @param content The content to scan and redact
     * @return Content with secrets replaced by [REDACTED]
     */
    public String redactSecrets(String content) {
        if (content == null || content.isEmpty()) {
            return content;
        }

        String redacted = content;
        int totalRedactions = 0;

        for (SecretPattern sp : SECRET_PATTERNS) {
            Matcher matcher = sp.pattern().matcher(redacted);
            int count = 0;

            // Use StringBuffer for replacement
            StringBuffer sb = new StringBuffer();
            while (matcher.find()) {
                // For patterns with groups, we want to redact just the secret part
                // For most patterns, the whole match is the secret
                String replacement = REDACTED;

                // Special handling for patterns where we want to keep some context
                if (sp.name().equals("Password") || sp.name().equals("Env Secret") ||
                    sp.name().equals("Hex Secret") || sp.name().equals("AWS Secret Key") ||
                    sp.name().equals("Azure Key") || sp.name().equals("Bearer Token")) {
                    // Keep the key name, just redact the value
                    if (matcher.groupCount() >= 2) {
                        String fullMatch = matcher.group(0);
                        String secretValue = matcher.group(matcher.groupCount());
                        replacement = fullMatch.replace(secretValue, REDACTED);
                    }
                } else if (sp.name().equals("MongoDB URI") || sp.name().equals("Database URI") ||
                           sp.name().equals("Redis URI")) {
                    // Keep the URI structure, just redact the password
                    if (matcher.groupCount() >= 2) {
                        String fullMatch = matcher.group(0);
                        String password = matcher.group(2);
                        if (password != null && !password.isEmpty()) {
                            replacement = fullMatch.replace(":" + password + "@", ":" + REDACTED + "@");
                        }
                    }
                }

                matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
                count++;
            }
            matcher.appendTail(sb);
            redacted = sb.toString();

            if (count > 0) {
                totalRedactions += count;
                log.debug("Redacted {} {} occurrence(s)", count, sp.name());
            }
        }

        if (totalRedactions > 0) {
            log.info("Redacted {} secret(s) from content before LLM submission", totalRedactions);
        }

        return redacted;
    }

    /**
     * Check if content contains any secrets without redacting.
     * Useful for pre-flight checks.
     *
     * @param content The content to scan
     * @return true if secrets are detected
     */
    public boolean containsSecrets(String content) {
        if (content == null || content.isEmpty()) {
            return false;
        }

        for (SecretPattern sp : SECRET_PATTERNS) {
            if (sp.pattern().matcher(content).find()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Get a list of detected secret types in the content.
     *
     * @param content The content to scan
     * @return List of secret type names found
     */
    public List<String> detectSecretTypes(String content) {
        List<String> found = new ArrayList<>();
        if (content == null || content.isEmpty()) {
            return found;
        }

        for (SecretPattern sp : SECRET_PATTERNS) {
            if (sp.pattern().matcher(content).find()) {
                found.add(sp.name());
            }
        }
        return found;
    }

    /**
     * Internal record for pattern + name
     */
    private record SecretPattern(String name, Pattern pattern) {}
}
