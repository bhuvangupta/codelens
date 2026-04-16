package com.codelens.analysis;

import com.codelens.analysis.java.CheckstyleAnalyzer;
import com.codelens.analysis.java.CheckstyleAnalyzer.CheckstyleConfig;
import com.codelens.analysis.java.PmdAnalyzer;
import com.codelens.analysis.java.PmdAnalyzer.PmdConfig;
import com.codelens.analysis.javascript.EslintAnalyzer;
import com.codelens.analysis.javascript.EslintAnalyzer.EslintConfig;
import com.codelens.analysis.python.BanditAnalyzer;
import com.codelens.analysis.python.BanditAnalyzer.BanditConfig;
import com.codelens.analysis.python.RuffAnalyzer;
import com.codelens.analysis.python.RuffAnalyzer.RuffConfig;
import com.codelens.git.GitProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Fetches lint configurations from the repository being reviewed.
 * For each analyzer, walks the candidate file list and returns the first
 * non-blank file content found, or null if none found.
 */
@Slf4j
@Service
public class LintConfigService {

    /**
     * Fetch all per-analyzer configs from the repo at the given commit SHA.
     * Returns a bundle where each field is either the config found, or null.
     */
    public LintConfigBundle fetchAll(GitProvider gitProvider, String owner, String repo, String commitSha) {
        return new LintConfigBundle(
            fetchEslintConfig(gitProvider, owner, repo, commitSha),
            fetchPmdConfig(gitProvider, owner, repo, commitSha),
            fetchCheckstyleConfig(gitProvider, owner, repo, commitSha),
            fetchRuffConfig(gitProvider, owner, repo, commitSha),
            fetchBanditConfig(gitProvider, owner, repo, commitSha)
        );
    }

    private EslintConfig fetchEslintConfig(GitProvider gitProvider, String owner, String repo, String commitSha) {
        for (String configFilename : EslintAnalyzer.getConfigFileNames()) {
            try {
                String content = gitProvider.getFileContent(owner, repo, configFilename, commitSha);
                if (content != null && !content.isBlank()) {
                    log.debug("Found ESLint config: {}", configFilename);
                    return new EslintConfig(configFilename, content);
                }
            } catch (Exception e) {
                log.trace("ESLint config not found: {}", configFilename);
            }
        }
        // Also check package.json for eslintConfig
        try {
            String packageJson = gitProvider.getFileContent(owner, repo, "package.json", commitSha);
            if (packageJson != null && packageJson.contains("\"eslintConfig\"")) {
                log.debug("Found eslintConfig in package.json");
                return new EslintConfig("package.json", packageJson);
            }
        } catch (Exception e) {
            log.trace("No package.json found");
        }
        return null;
    }

    private PmdConfig fetchPmdConfig(GitProvider gitProvider, String owner, String repo, String commitSha) {
        for (String configFilename : PmdAnalyzer.getConfigFileNames()) {
            try {
                String content = gitProvider.getFileContent(owner, repo, configFilename, commitSha);
                if (content != null && !content.isBlank()) {
                    log.debug("Found PMD config: {}", configFilename);
                    return new PmdConfig(configFilename, content);
                }
            } catch (Exception e) {
                log.trace("PMD config not found: {}", configFilename);
            }
        }
        return null;
    }

    private CheckstyleConfig fetchCheckstyleConfig(GitProvider gitProvider, String owner, String repo, String commitSha) {
        for (String configFilename : CheckstyleAnalyzer.getConfigFileNames()) {
            try {
                String content = gitProvider.getFileContent(owner, repo, configFilename, commitSha);
                if (content != null && !content.isBlank()) {
                    log.debug("Found Checkstyle config: {}", configFilename);
                    return new CheckstyleConfig(configFilename, content);
                }
            } catch (Exception e) {
                log.trace("Checkstyle config not found: {}", configFilename);
            }
        }
        return null;
    }

    private RuffConfig fetchRuffConfig(GitProvider gitProvider, String owner, String repo, String commitSha) {
        for (String configFilename : RuffAnalyzer.getConfigFileNames()) {
            try {
                String content = gitProvider.getFileContent(owner, repo, configFilename, commitSha);
                if (content != null && !content.isBlank()) {
                    // pyproject.toml only counts if it has [tool.ruff] section
                    if ("pyproject.toml".equals(configFilename) && !content.contains("[tool.ruff")) {
                        continue;
                    }
                    log.debug("Found Ruff config: {}", configFilename);
                    return new RuffConfig(configFilename, content);
                }
            } catch (Exception e) {
                log.trace("Ruff config not found: {}", configFilename);
            }
        }
        return null;
    }

    private BanditConfig fetchBanditConfig(GitProvider gitProvider, String owner, String repo, String commitSha) {
        for (String configFilename : BanditAnalyzer.getConfigFileNames()) {
            try {
                String content = gitProvider.getFileContent(owner, repo, configFilename, commitSha);
                if (content != null && !content.isBlank()) {
                    // pyproject.toml only counts if it has [tool.bandit] section
                    if ("pyproject.toml".equals(configFilename) && !content.contains("[tool.bandit")) {
                        continue;
                    }
                    log.debug("Found Bandit config: {}", configFilename);
                    return new BanditConfig(configFilename, content);
                }
            } catch (Exception e) {
                log.trace("Bandit config not found: {}", configFilename);
            }
        }
        return null;
    }
}
