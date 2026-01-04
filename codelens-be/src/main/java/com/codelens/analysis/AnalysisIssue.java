package com.codelens.analysis;

/**
 * Issue found by static analysis
 */
public record AnalysisIssue(
    String analyzer,
    String ruleId,
    Severity severity,
    String category,
    String message,
    String filePath,
    int line,
    int column,
    int endLine,
    int endColumn,
    String cveId,
    Double cvssScore,
    String suggestion,
    String codeSnippet
) {
    public enum Severity {
        CRITICAL,
        HIGH,
        MEDIUM,
        LOW,
        INFO
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String analyzer;
        private String ruleId;
        private Severity severity = Severity.MEDIUM;
        private String category;
        private String message;
        private String filePath;
        private int line;
        private int column;
        private int endLine;
        private int endColumn;
        private String cveId;
        private Double cvssScore;
        private String suggestion;
        private String codeSnippet;

        public Builder analyzer(String analyzer) {
            this.analyzer = analyzer;
            return this;
        }

        public Builder ruleId(String ruleId) {
            this.ruleId = ruleId;
            return this;
        }

        public Builder severity(Severity severity) {
            this.severity = severity;
            return this;
        }

        public Builder category(String category) {
            this.category = category;
            return this;
        }

        public Builder message(String message) {
            this.message = message;
            return this;
        }

        public Builder filePath(String filePath) {
            this.filePath = filePath;
            return this;
        }

        public Builder line(int line) {
            this.line = line;
            return this;
        }

        public Builder column(int column) {
            this.column = column;
            return this;
        }

        public Builder endLine(int endLine) {
            this.endLine = endLine;
            return this;
        }

        public Builder endColumn(int endColumn) {
            this.endColumn = endColumn;
            return this;
        }

        public Builder cveId(String cveId) {
            this.cveId = cveId;
            return this;
        }

        public Builder cvssScore(Double cvssScore) {
            this.cvssScore = cvssScore;
            return this;
        }

        public Builder suggestion(String suggestion) {
            this.suggestion = suggestion;
            return this;
        }

        public Builder codeSnippet(String codeSnippet) {
            this.codeSnippet = codeSnippet;
            return this;
        }

        public AnalysisIssue build() {
            return new AnalysisIssue(
                analyzer, ruleId, severity, category, message,
                filePath, line, column, endLine, endColumn,
                cveId, cvssScore, suggestion, codeSnippet
            );
        }
    }
}
