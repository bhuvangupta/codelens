package com.codelens.analysis.java;

import com.codelens.analysis.AnalysisIssue;
import com.codelens.analysis.StaticAnalyzer;
import com.puppycrawl.tools.checkstyle.Checker;
import com.puppycrawl.tools.checkstyle.ConfigurationLoader;
import com.puppycrawl.tools.checkstyle.PropertiesExpander;
import com.puppycrawl.tools.checkstyle.api.AuditEvent;
import com.puppycrawl.tools.checkstyle.api.AuditListener;
import com.puppycrawl.tools.checkstyle.api.Configuration;
import com.puppycrawl.tools.checkstyle.api.SeverityLevel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.xml.sax.InputSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

@Slf4j
@Component
public class CheckstyleAnalyzer implements StaticAnalyzer {

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("java");

    private static final List<String> CHECKSTYLE_CONFIG_FILES = List.of(
        "checkstyle.xml",
        "config/checkstyle/checkstyle.xml",
        ".checkstyle.xml"
    );

    private static final String SECURITY_FLOOR_RESOURCE = "security-floor/checkstyle-security-floor.xml";

    private static final String DEFAULT_CONFIG = """
        <?xml version="1.0"?>
        <!DOCTYPE module PUBLIC
            "-//Checkstyle//DTD Checkstyle Configuration 1.3//EN"
            "https://checkstyle.org/dtds/configuration_1_3.dtd">
        <module name="Checker">
            <module name="TreeWalker">
                <module name="AvoidStarImport"/>
                <module name="EmptyBlock"/>
                <module name="EmptyCatchBlock"/>
                <module name="EqualsHashCode"/>
                <module name="FallThrough"/>
                <module name="HiddenField">
                    <property name="ignoreConstructorParameter" value="true"/>
                    <property name="ignoreSetter" value="true"/>
                    <property name="setterCanReturnItsClass" value="true"/>
                </module>
                <module name="SuppressionXpathSingleFilter">
                    <property name="checks" value="HiddenField"/>
                    <property name="query" value="//METHOD_DEF[./IDENT[starts-with(@text, 'with')]]/PARAMETERS/PARAMETER_DEF"/>
                </module>
                <module name="IllegalCatch"/>
                <module name="IllegalThrows"/>
                <module name="MissingSwitchDefault"/>
                <module name="ModifiedControlVariable"/>
                <module name="MultipleVariableDeclarations"/>
                <module name="NestedForDepth">
                    <property name="max" value="3"/>
                </module>
                <module name="NestedIfDepth">
                    <property name="max" value="4"/>
                </module>
                <module name="NestedTryDepth">
                    <property name="max" value="2"/>
                </module>
                <module name="NoClone"/>
                <module name="NoFinalizer"/>
                <module name="SimplifyBooleanExpression"/>
                <module name="SimplifyBooleanReturn"/>
                <module name="StringLiteralEquality"/>
            </module>
        </module>
        """;

    /**
     * Holds Checkstyle configuration fetched from the repository.
     */
    public record CheckstyleConfig(String configFilename, String configContent) {}

    /**
     * Get list of Checkstyle config file names to search for in a repo.
     */
    public static List<String> getConfigFileNames() {
        return CHECKSTYLE_CONFIG_FILES;
    }

    @Override
    public String getName() {
        return "checkstyle";
    }

    @Override
    public Set<String> getSupportedExtensions() {
        return SUPPORTED_EXTENSIONS;
    }

    @Override
    public List<AnalysisIssue> analyze(String filename, String content) {
        return analyzeWithConfig(filename, content, null);
    }

    /**
     * Analyze with optional project config. If config is non-null, runs TWO passes:
     *   1. With the project's config
     *   2. With the security floor
     * Then merges results and deduplicates by (filename, line, ruleId).
     * If config is null, uses only the bundled DEFAULT_CONFIG.
     */
    public List<AnalysisIssue> analyzeWithConfig(String filename, String content, CheckstyleConfig config) {
        List<AnalysisIssue> issues = new ArrayList<>();

        try {
            Path tempFile = Files.createTempFile("checkstyle", ".java");
            Files.writeString(tempFile, content);

            try {
                if (config != null && config.configContent() != null && !config.configContent().isBlank()) {
                    issues.addAll(runCheckstyle(tempFile, filename, config.configContent()));
                    String securityFloorXml = loadSecurityFloor();
                    if (securityFloorXml != null) {
                        issues.addAll(runCheckstyle(tempFile, filename, securityFloorXml));
                    }
                    log.info("Checkstyle: Using project config from {} + security floor",
                        config.configFilename());
                    issues = deduplicateIssues(issues);
                } else {
                    issues.addAll(runCheckstyle(tempFile, filename, DEFAULT_CONFIG));
                    log.debug("Checkstyle: Using bundled CodeLens default config");
                }
            } finally {
                Files.deleteIfExists(tempFile);
            }
        } catch (Exception e) {
            log.warn("Checkstyle analysis failed for {}: {}", filename, e.getMessage());
        }

        return issues;
    }

    private List<AnalysisIssue> runCheckstyle(Path tempFile, String originalFilename, String configXml)
            throws Exception {
        Configuration config = ConfigurationLoader.loadConfiguration(
            new InputSource(new StringReader(configXml)),
            new PropertiesExpander(new Properties()),
            ConfigurationLoader.IgnoredModulesOptions.OMIT
        );

        Checker checker = new Checker();
        checker.setModuleClassLoader(Checker.class.getClassLoader());
        checker.configure(config);

        IssueCollector collector = new IssueCollector(originalFilename);
        checker.addListener(collector);

        try {
            checker.process(List.of(tempFile.toFile()));
            return collector.getIssues();
        } finally {
            checker.destroy();
        }
    }

    private String loadSecurityFloor() {
        try (InputStream is = new ClassPathResource(SECURITY_FLOOR_RESOURCE).getInputStream()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Failed to load Checkstyle security floor: {}", e.getMessage());
            return null;
        }
    }

    private List<AnalysisIssue> deduplicateIssues(List<AnalysisIssue> issues) {
        Set<String> seen = new HashSet<>();
        List<AnalysisIssue> deduped = new ArrayList<>();
        for (AnalysisIssue issue : issues) {
            String key = issue.filePath() + ":" + issue.line() + ":" + issue.ruleId();
            if (seen.add(key)) {
                deduped.add(issue);
            }
        }
        return deduped;
    }

    @Override
    public List<AnalysisIssue> analyzeFile(String filePath) {
        try {
            String content = Files.readString(Path.of(filePath));
            return analyze(filePath, content);
        } catch (Exception e) {
            log.error("Failed to analyze file: {}", filePath, e);
            return List.of();
        }
    }

    @Override
    public boolean isAvailable() {
        try {
            Class.forName("com.puppycrawl.tools.checkstyle.Checker");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private class IssueCollector implements AuditListener {
        private final String originalFilename;
        private final List<AnalysisIssue> issues = new ArrayList<>();

        public IssueCollector(String originalFilename) {
            this.originalFilename = originalFilename;
        }

        @Override
        public void auditStarted(AuditEvent event) {}

        @Override
        public void auditFinished(AuditEvent event) {}

        @Override
        public void fileStarted(AuditEvent event) {}

        @Override
        public void fileFinished(AuditEvent event) {}

        @Override
        public void addError(AuditEvent event) {
            issues.add(AnalysisIssue.builder()
                .analyzer(getName())
                .ruleId(extractRuleName(event.getSourceName()))
                .severity(mapSeverity(event.getSeverityLevel()))
                .category("Code Style")
                .message(event.getMessage())
                .filePath(originalFilename)
                .line(event.getLine())
                .column(event.getColumn())
                .build());
        }

        @Override
        public void addException(AuditEvent event, Throwable throwable) {
            log.warn("Checkstyle exception: {}", throwable.getMessage());
        }

        public List<AnalysisIssue> getIssues() {
            return issues;
        }

        private String extractRuleName(String sourceName) {
            if (sourceName == null) return "Unknown";
            int lastDot = sourceName.lastIndexOf('.');
            return lastDot > 0 ? sourceName.substring(lastDot + 1) : sourceName;
        }

        private AnalysisIssue.Severity mapSeverity(SeverityLevel level) {
            return switch (level) {
                case ERROR -> AnalysisIssue.Severity.HIGH;
                case WARNING -> AnalysisIssue.Severity.MEDIUM;
                case INFO -> AnalysisIssue.Severity.LOW;
                case IGNORE -> AnalysisIssue.Severity.INFO;
            };
        }
    }
}
