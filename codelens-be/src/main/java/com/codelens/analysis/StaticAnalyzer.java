package com.codelens.analysis;

import java.util.List;
import java.util.Set;

/**
 * Interface for static code analyzers
 */
public interface StaticAnalyzer {

    /**
     * Get the analyzer name
     */
    String getName();

    /**
     * Get supported file extensions
     */
    Set<String> getSupportedExtensions();

    /**
     * Check if this analyzer supports the given file
     */
    default boolean supports(String filename) {
        String ext = getExtension(filename);
        return getSupportedExtensions().contains(ext);
    }

    /**
     * Analyze file content
     */
    List<AnalysisIssue> analyze(String filename, String content);

    /**
     * Analyze a file from path
     */
    List<AnalysisIssue> analyzeFile(String filePath);

    /**
     * Check if analyzer is available (dependencies installed)
     */
    boolean isAvailable();

    private String getExtension(String filename) {
        int lastDot = filename.lastIndexOf('.');
        return lastDot > 0 ? filename.substring(lastDot + 1).toLowerCase() : "";
    }
}
