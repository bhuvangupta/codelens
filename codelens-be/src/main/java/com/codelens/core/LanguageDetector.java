package com.codelens.core;

import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Detects programming languages from file extensions
 */
@Component
public class LanguageDetector {

    private static final Map<String, String> EXTENSION_TO_LANGUAGE = new HashMap<>();

    static {
        // Java ecosystem
        EXTENSION_TO_LANGUAGE.put("java", "Java");
        EXTENSION_TO_LANGUAGE.put("kt", "Kotlin");
        EXTENSION_TO_LANGUAGE.put("kts", "Kotlin");
        EXTENSION_TO_LANGUAGE.put("scala", "Scala");
        EXTENSION_TO_LANGUAGE.put("groovy", "Groovy");
        EXTENSION_TO_LANGUAGE.put("clj", "Clojure");

        // JavaScript/TypeScript
        EXTENSION_TO_LANGUAGE.put("js", "JavaScript");
        EXTENSION_TO_LANGUAGE.put("jsx", "JavaScript");
        EXTENSION_TO_LANGUAGE.put("mjs", "JavaScript");
        EXTENSION_TO_LANGUAGE.put("cjs", "JavaScript");
        EXTENSION_TO_LANGUAGE.put("ts", "TypeScript");
        EXTENSION_TO_LANGUAGE.put("tsx", "TypeScript");
        EXTENSION_TO_LANGUAGE.put("vue", "Vue");
        EXTENSION_TO_LANGUAGE.put("svelte", "Svelte");

        // Python
        EXTENSION_TO_LANGUAGE.put("py", "Python");
        EXTENSION_TO_LANGUAGE.put("pyw", "Python");
        EXTENSION_TO_LANGUAGE.put("pyx", "Python");
        EXTENSION_TO_LANGUAGE.put("pxd", "Python");
        EXTENSION_TO_LANGUAGE.put("pyi", "Python");

        // Go
        EXTENSION_TO_LANGUAGE.put("go", "Go");

        // Rust
        EXTENSION_TO_LANGUAGE.put("rs", "Rust");

        // C/C++
        EXTENSION_TO_LANGUAGE.put("c", "C");
        EXTENSION_TO_LANGUAGE.put("h", "C");
        EXTENSION_TO_LANGUAGE.put("cpp", "C++");
        EXTENSION_TO_LANGUAGE.put("cc", "C++");
        EXTENSION_TO_LANGUAGE.put("cxx", "C++");
        EXTENSION_TO_LANGUAGE.put("hpp", "C++");
        EXTENSION_TO_LANGUAGE.put("hxx", "C++");

        // C#
        EXTENSION_TO_LANGUAGE.put("cs", "C#");
        EXTENSION_TO_LANGUAGE.put("csx", "C#");

        // Ruby
        EXTENSION_TO_LANGUAGE.put("rb", "Ruby");
        EXTENSION_TO_LANGUAGE.put("erb", "Ruby");
        EXTENSION_TO_LANGUAGE.put("rake", "Ruby");

        // PHP
        EXTENSION_TO_LANGUAGE.put("php", "PHP");
        EXTENSION_TO_LANGUAGE.put("phtml", "PHP");

        // Swift
        EXTENSION_TO_LANGUAGE.put("swift", "Swift");

        // Objective-C
        EXTENSION_TO_LANGUAGE.put("m", "Objective-C");
        EXTENSION_TO_LANGUAGE.put("mm", "Objective-C++");

        // Shell
        EXTENSION_TO_LANGUAGE.put("sh", "Shell");
        EXTENSION_TO_LANGUAGE.put("bash", "Shell");
        EXTENSION_TO_LANGUAGE.put("zsh", "Shell");

        // PowerShell
        EXTENSION_TO_LANGUAGE.put("ps1", "PowerShell");
        EXTENSION_TO_LANGUAGE.put("psm1", "PowerShell");

        // Dart/Flutter
        EXTENSION_TO_LANGUAGE.put("dart", "Dart");

        // Elixir/Erlang
        EXTENSION_TO_LANGUAGE.put("ex", "Elixir");
        EXTENSION_TO_LANGUAGE.put("exs", "Elixir");
        EXTENSION_TO_LANGUAGE.put("erl", "Erlang");
        EXTENSION_TO_LANGUAGE.put("hrl", "Erlang");

        // Haskell
        EXTENSION_TO_LANGUAGE.put("hs", "Haskell");
        EXTENSION_TO_LANGUAGE.put("lhs", "Haskell");

        // Lua
        EXTENSION_TO_LANGUAGE.put("lua", "Lua");

        // R
        EXTENSION_TO_LANGUAGE.put("r", "R");
        EXTENSION_TO_LANGUAGE.put("R", "R");

        // Julia
        EXTENSION_TO_LANGUAGE.put("jl", "Julia");

        // Perl
        EXTENSION_TO_LANGUAGE.put("pl", "Perl");
        EXTENSION_TO_LANGUAGE.put("pm", "Perl");

        // HTML/CSS
        EXTENSION_TO_LANGUAGE.put("html", "HTML");
        EXTENSION_TO_LANGUAGE.put("htm", "HTML");
        EXTENSION_TO_LANGUAGE.put("css", "CSS");
        EXTENSION_TO_LANGUAGE.put("scss", "SCSS");
        EXTENSION_TO_LANGUAGE.put("sass", "Sass");
        EXTENSION_TO_LANGUAGE.put("less", "Less");

        // SQL
        EXTENSION_TO_LANGUAGE.put("sql", "SQL");

        // Markdown/Docs
        EXTENSION_TO_LANGUAGE.put("md", "Markdown");
        EXTENSION_TO_LANGUAGE.put("markdown", "Markdown");
        EXTENSION_TO_LANGUAGE.put("rst", "reStructuredText");

        // Config files
        EXTENSION_TO_LANGUAGE.put("json", "JSON");
        EXTENSION_TO_LANGUAGE.put("yaml", "YAML");
        EXTENSION_TO_LANGUAGE.put("yml", "YAML");
        EXTENSION_TO_LANGUAGE.put("toml", "TOML");
        EXTENSION_TO_LANGUAGE.put("xml", "XML");
        EXTENSION_TO_LANGUAGE.put("ini", "INI");
        EXTENSION_TO_LANGUAGE.put("properties", "Properties");

        // Docker
        EXTENSION_TO_LANGUAGE.put("dockerfile", "Dockerfile");

        // Terraform
        EXTENSION_TO_LANGUAGE.put("tf", "Terraform");
        EXTENSION_TO_LANGUAGE.put("tfvars", "Terraform");
    }

    /**
     * Detect language from a single filename
     */
    public String detectFromFilename(String filename) {
        if (filename == null || filename.isEmpty()) {
            return null;
        }

        // Handle special filenames
        String lowerFilename = filename.toLowerCase();
        if (lowerFilename.equals("dockerfile") || lowerFilename.startsWith("dockerfile.")) {
            return "Dockerfile";
        }
        if (lowerFilename.equals("makefile") || lowerFilename.equals("gnumakefile")) {
            return "Makefile";
        }

        // Get extension
        int lastDot = filename.lastIndexOf('.');
        if (lastDot == -1 || lastDot == filename.length() - 1) {
            return null;
        }

        String extension = filename.substring(lastDot + 1).toLowerCase();
        return EXTENSION_TO_LANGUAGE.get(extension);
    }

    /**
     * Detect the primary language from a list of filenames
     * Returns the most common language by file count
     */
    public String detectPrimaryLanguage(List<String> filenames) {
        if (filenames == null || filenames.isEmpty()) {
            return null;
        }

        // Count occurrences of each language
        Map<String, Long> languageCounts = filenames.stream()
            .map(this::detectFromFilename)
            .filter(Objects::nonNull)
            .filter(lang -> !isConfigOrDocLanguage(lang))
            .collect(Collectors.groupingBy(lang -> lang, Collectors.counting()));

        if (languageCounts.isEmpty()) {
            return null;
        }

        // Return the most common language
        return languageCounts.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .orElse(null);
    }

    /**
     * Get language statistics from a list of filenames
     * Returns a map of language -> file count
     */
    public Map<String, Long> getLanguageStats(List<String> filenames) {
        if (filenames == null || filenames.isEmpty()) {
            return Collections.emptyMap();
        }

        return filenames.stream()
            .map(this::detectFromFilename)
            .filter(Objects::nonNull)
            .collect(Collectors.groupingBy(lang -> lang, Collectors.counting()));
    }

    /**
     * Check if a language is primarily config or documentation
     */
    private boolean isConfigOrDocLanguage(String language) {
        return switch (language) {
            case "JSON", "YAML", "TOML", "XML", "INI", "Properties",
                 "Markdown", "reStructuredText", "HTML", "CSS",
                 "SCSS", "Sass", "Less" -> true;
            default -> false;
        };
    }

    /**
     * Check if a file should be analyzed for code review
     */
    public boolean isReviewableFile(String filename) {
        String language = detectFromFilename(filename);
        if (language == null) {
            return false;
        }

        // Skip config and doc files from deep analysis
        return !isConfigOrDocLanguage(language);
    }

    /**
     * Get all supported extensions
     */
    public Set<String> getSupportedExtensions() {
        return Collections.unmodifiableSet(EXTENSION_TO_LANGUAGE.keySet());
    }

    /**
     * Get all supported languages
     */
    public Set<String> getSupportedLanguages() {
        return EXTENSION_TO_LANGUAGE.values().stream()
            .collect(Collectors.toUnmodifiableSet());
    }
}
