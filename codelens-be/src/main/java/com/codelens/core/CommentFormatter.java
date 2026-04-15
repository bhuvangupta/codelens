package com.codelens.core;

import com.codelens.model.entity.ReviewComment;
import com.codelens.model.entity.ReviewIssue;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class CommentFormatter {

    public String formatSummary(String summary, List<ReviewIssue> issues, ReviewStats stats, String reviewUrl) {
        StringBuilder sb = new StringBuilder();
        sb.append("## CodeLens Review");
        if (!issues.isEmpty()) {
            sb.append(" \u2014 ").append(issues.size()).append(issues.size() == 1 ? " issue" : " issues");
        }
        sb.append("\n\n");

        if (!issues.isEmpty()) {
            List<ReviewIssue> critical = filterBySeverity(issues, ReviewIssue.Severity.CRITICAL);
            List<ReviewIssue> high = filterBySeverity(issues, ReviewIssue.Severity.HIGH);
            List<ReviewIssue> medium = filterBySeverity(issues, ReviewIssue.Severity.MEDIUM);
            List<ReviewIssue> low = filterBySeverity(issues, ReviewIssue.Severity.LOW);

            if (!critical.isEmpty()) {
                sb.append("### :rotating_light: Critical (").append(critical.size()).append(")\n");
                formatIssueBullets(sb, critical);
            }
            if (!high.isEmpty()) {
                sb.append("### :warning: High (").append(high.size()).append(")\n");
                formatIssueBullets(sb, high);
            }
            if (!medium.isEmpty()) {
                sb.append("### :yellow_circle: Medium (").append(medium.size()).append(")\n");
                formatIssueBullets(sb, medium);
            }
            if (!low.isEmpty()) {
                sb.append("### :information_source: Low (").append(low.size()).append(")\n");
                formatIssueBullets(sb, low);
            }
        } else {
            sb.append(":white_check_mark: **No issues found!**\n\n");
        }

        if (reviewUrl != null && !reviewUrl.isEmpty()) {
            sb.append("[View Full Review](").append(reviewUrl).append(") \u00b7 ");
        }
        sb.append("CodeLens AI");

        return sb.toString();
    }

    private void formatIssueBullets(StringBuilder sb, List<ReviewIssue> issues) {
        for (ReviewIssue issue : issues) {
            String file = issue.getFilePath() != null ? truncateFilePath(issue.getFilePath()) : "";
            String line = issue.getLineNumber() != null ? ":" + issue.getLineNumber() : "";
            String desc = truncateDescription(issue.getDescription());
            String source = issue.getSource() == ReviewIssue.Source.AI ? "" : " [" + formatSource(issue) + "]";

            sb.append("- **`").append(file).append(line).append("`**");
            sb.append(" \u2014 ").append(desc).append(source).append("\n");
        }
        sb.append("\n");
    }

    /**
     * Format issue source (AI or analyzer name)
     */
    private String formatSource(ReviewIssue issue) {
        if (issue.getSource() == ReviewIssue.Source.AI) {
            return "AI";
        } else if (issue.getAnalyzer() != null) {
            // Capitalize analyzer name (pmd -> PMD, eslint -> ESLint)
            String analyzer = issue.getAnalyzer().toLowerCase();
            return switch (analyzer) {
                case "pmd" -> "PMD";
                case "checkstyle" -> "Checkstyle";
                case "spotbugs" -> "SpotBugs";
                case "eslint" -> "ESLint";
                case "npm-audit" -> "NPM Audit";
                default -> analyzer.toUpperCase();
            };
        }
        return "Static";
    }

    private String truncateFilePath(String path) {
        if (path == null) return "-";
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash >= 0 && lastSlash < path.length() - 1) {
            return path.substring(lastSlash + 1);
        }
        return path;
    }

    private String truncateDescription(String desc) {
        if (desc == null) return "-";
        desc = desc.replace("\n", " ");
        if (desc.length() > 120) {
            return desc.substring(0, 117) + "...";
        }
        return desc;
    }

    public String formatInlineComment(ReviewComment comment) {
        StringBuilder sb = new StringBuilder();

        String severity = comment.getSeverity() != null ? comment.getSeverity().name() : "INFO";
        String category = comment.getCategory() != null ? comment.getCategory() : "Review";
        sb.append("**").append(severity).append(" \u00b7 ").append(category).append("** \u2014 ");
        sb.append(comment.getBody()).append("\n");

        if (comment.getSuggestion() != null && !comment.getSuggestion().isEmpty()) {
            sb.append("\n```suggestion\n");
            sb.append(comment.getSuggestion());
            sb.append("\n```\n");
        }

        return sb.toString();
    }

    /**
     * Format a CVE issue for display
     */
    public String formatCveIssue(ReviewIssue issue) {
        StringBuilder sb = new StringBuilder();
        sb.append(":shield: **Security Vulnerability**\n\n");

        if (issue.getCveId() != null) {
            sb.append("**CVE:** ").append(issue.getCveId()).append("\n");
        }
        if (issue.getCvssScore() != null) {
            sb.append("**CVSS Score:** ").append(issue.getCvssScore()).append("\n");
        }

        sb.append("\n").append(issue.getDescription()).append("\n");

        if (issue.getSuggestion() != null) {
            sb.append("\n**Remediation:**\n").append(issue.getSuggestion()).append("\n");
        }

        return sb.toString();
    }

    /**
     * Format issues for a specific file
     */
    public String formatFileIssues(String filePath, List<ReviewIssue> issues) {
        StringBuilder sb = new StringBuilder();
        sb.append("### Issues in `").append(filePath).append("`\n\n");

        for (ReviewIssue issue : issues) {
            sb.append("- ");
            sb.append(getSeverityBadge(issue.getSeverity())).append(" ");
            if (issue.getLineNumber() != null) {
                sb.append("Line ").append(issue.getLineNumber()).append(": ");
            }
            sb.append(issue.getDescription()).append("\n");
        }

        return sb.toString();
    }

    private List<ReviewIssue> filterBySeverity(List<ReviewIssue> issues, ReviewIssue.Severity severity) {
        return issues.stream()
            .filter(i -> i.getSeverity() == severity)
            .toList();
    }

    private String getSeverityBadge(ReviewIssue.Severity severity) {
        return switch (severity) {
            case CRITICAL -> ":rotating_light:";
            case HIGH -> ":warning:";
            case MEDIUM -> ":yellow_circle:";
            case LOW -> ":information_source:";
            case INFO -> ":bulb:";
        };
    }

    /**
     * Review statistics
     */
    public record ReviewStats(
        int filesReviewed,
        int linesAdded,
        int linesRemoved
    ) {}
}
