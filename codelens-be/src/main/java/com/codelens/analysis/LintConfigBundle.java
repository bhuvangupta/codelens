package com.codelens.analysis;

import com.codelens.analysis.java.CheckstyleAnalyzer.CheckstyleConfig;
import com.codelens.analysis.java.PmdAnalyzer.PmdConfig;
import com.codelens.analysis.javascript.BiomeAnalyzer.BiomeConfig;
import com.codelens.analysis.javascript.EslintAnalyzer.EslintConfig;
import com.codelens.analysis.python.BanditAnalyzer.BanditConfig;
import com.codelens.analysis.python.RuffAnalyzer.RuffConfig;

/**
 * Bundle of all per-analyzer configurations fetched from the repo being reviewed.
 * Any field may be null, indicating no project config was found for that analyzer
 * (in which case the analyzer uses its bundled CodeLens default).
 */
public record LintConfigBundle(
    EslintConfig eslintConfig,
    BiomeConfig biomeConfig,
    PmdConfig pmdConfig,
    CheckstyleConfig checkstyleConfig,
    RuffConfig ruffConfig,
    BanditConfig banditConfig,
    LinterDetection linterDetection
) {
    public static LintConfigBundle empty() {
        return new LintConfigBundle(null, null, null, null, null, null, LinterDetection.defaultFallback());
    }

    /**
     * Which JS/TS linters are enabled based on package.json scripts.lint detection.
     * When fromScriptsLint is true, the booleans are authoritative.
     * When false, callers should fall back to config file presence.
     */
    public record LinterDetection(boolean eslintEnabled, boolean biomeEnabled, boolean fromScriptsLint) {
        public static LinterDetection defaultFallback() {
            return new LinterDetection(true, false, false);
        }
    }
}
