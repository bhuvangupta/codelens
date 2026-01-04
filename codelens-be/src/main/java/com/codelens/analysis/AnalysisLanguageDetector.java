package com.codelens.analysis;

import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Detects programming language from file extension for static analysis routing.
 * Used by CombinedAnalysisService to determine which analyzers to run.
 */
@Component("analysisLanguageDetector")
public class AnalysisLanguageDetector {

    public enum Language {
        JAVA,
        JAVASCRIPT,
        TYPESCRIPT,
        JSX,
        TSX,
        PYTHON,
        GO,
        RUST,
        KOTLIN,
        SCALA,
        SQL,
        HTML,
        CSS,
        SCSS,
        JSON,
        YAML,
        XML,
        MARKDOWN,
        SHELL,
        DOCKERFILE,
        UNKNOWN
    }

    private static final Set<String> JAVA_EXTENSIONS = Set.of("java");
    private static final Set<String> JS_EXTENSIONS = Set.of("js", "mjs", "cjs");
    private static final Set<String> TS_EXTENSIONS = Set.of("ts", "mts", "cts");
    private static final Set<String> JSX_EXTENSIONS = Set.of("jsx");
    private static final Set<String> TSX_EXTENSIONS = Set.of("tsx");
    private static final Set<String> PYTHON_EXTENSIONS = Set.of("py", "pyw");
    private static final Set<String> GO_EXTENSIONS = Set.of("go");
    private static final Set<String> RUST_EXTENSIONS = Set.of("rs");
    private static final Set<String> KOTLIN_EXTENSIONS = Set.of("kt", "kts");
    private static final Set<String> SCALA_EXTENSIONS = Set.of("scala", "sc");
    private static final Set<String> SQL_EXTENSIONS = Set.of("sql");
    private static final Set<String> HTML_EXTENSIONS = Set.of("html", "htm");
    private static final Set<String> CSS_EXTENSIONS = Set.of("css");
    private static final Set<String> SCSS_EXTENSIONS = Set.of("scss", "sass", "less");
    private static final Set<String> JSON_EXTENSIONS = Set.of("json");
    private static final Set<String> YAML_EXTENSIONS = Set.of("yaml", "yml");
    private static final Set<String> XML_EXTENSIONS = Set.of("xml", "xsd", "xsl", "pom");
    private static final Set<String> MARKDOWN_EXTENSIONS = Set.of("md", "markdown");
    private static final Set<String> SHELL_EXTENSIONS = Set.of("sh", "bash", "zsh");

    /**
     * Detect language from filename
     */
    public Language detect(String filename) {
        String ext = getExtension(filename).toLowerCase();
        String basename = getBasename(filename).toLowerCase();

        // Check for special files
        if ("dockerfile".equals(basename)) {
            return Language.DOCKERFILE;
        }

        // Check by extension
        if (JAVA_EXTENSIONS.contains(ext)) return Language.JAVA;
        if (JS_EXTENSIONS.contains(ext)) return Language.JAVASCRIPT;
        if (TS_EXTENSIONS.contains(ext)) return Language.TYPESCRIPT;
        if (JSX_EXTENSIONS.contains(ext)) return Language.JSX;
        if (TSX_EXTENSIONS.contains(ext)) return Language.TSX;
        if (PYTHON_EXTENSIONS.contains(ext)) return Language.PYTHON;
        if (GO_EXTENSIONS.contains(ext)) return Language.GO;
        if (RUST_EXTENSIONS.contains(ext)) return Language.RUST;
        if (KOTLIN_EXTENSIONS.contains(ext)) return Language.KOTLIN;
        if (SCALA_EXTENSIONS.contains(ext)) return Language.SCALA;
        if (SQL_EXTENSIONS.contains(ext)) return Language.SQL;
        if (HTML_EXTENSIONS.contains(ext)) return Language.HTML;
        if (CSS_EXTENSIONS.contains(ext)) return Language.CSS;
        if (SCSS_EXTENSIONS.contains(ext)) return Language.SCSS;
        if (JSON_EXTENSIONS.contains(ext)) return Language.JSON;
        if (YAML_EXTENSIONS.contains(ext)) return Language.YAML;
        if (XML_EXTENSIONS.contains(ext)) return Language.XML;
        if (MARKDOWN_EXTENSIONS.contains(ext)) return Language.MARKDOWN;
        if (SHELL_EXTENSIONS.contains(ext)) return Language.SHELL;

        return Language.UNKNOWN;
    }

    /**
     * Check if file is a JavaScript/TypeScript family file
     */
    public boolean isJavaScriptFamily(String filename) {
        Language lang = detect(filename);
        return lang == Language.JAVASCRIPT || lang == Language.TYPESCRIPT ||
               lang == Language.JSX || lang == Language.TSX;
    }

    /**
     * Check if file is Java
     */
    public boolean isJava(String filename) {
        return detect(filename) == Language.JAVA;
    }

    /**
     * Check if file is a config file (JSON, YAML, XML)
     */
    public boolean isConfigFile(String filename) {
        Language lang = detect(filename);
        return lang == Language.JSON || lang == Language.YAML || lang == Language.XML;
    }

    /**
     * Check if file should be analyzed by static analyzers
     */
    public boolean shouldAnalyze(String filename) {
        Language lang = detect(filename);
        return lang != Language.UNKNOWN && lang != Language.MARKDOWN;
    }

    private String getExtension(String filename) {
        int lastDot = filename.lastIndexOf('.');
        return lastDot > 0 ? filename.substring(lastDot + 1) : "";
    }

    private String getBasename(String filename) {
        int lastSlash = Math.max(filename.lastIndexOf('/'), filename.lastIndexOf('\\'));
        String basename = lastSlash >= 0 ? filename.substring(lastSlash + 1) : filename;
        int lastDot = basename.lastIndexOf('.');
        return lastDot > 0 ? basename.substring(0, lastDot) : basename;
    }
}
