package com.codelens.analysis;

import com.codelens.analysis.java.CheckstyleAnalyzer.CheckstyleConfig;
import com.codelens.analysis.java.PmdAnalyzer.PmdConfig;
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
    PmdConfig pmdConfig,
    CheckstyleConfig checkstyleConfig,
    RuffConfig ruffConfig,
    BanditConfig banditConfig
) {
    /**
     * Convenience: a bundle with no project configs (everything uses CodeLens defaults).
     */
    public static LintConfigBundle empty() {
        return new LintConfigBundle(null, null, null, null, null);
    }
}
