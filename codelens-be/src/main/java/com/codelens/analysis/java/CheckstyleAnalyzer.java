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
import org.springframework.stereotype.Component;
import org.xml.sax.InputSource;

import java.io.File;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;

@Slf4j
@Component
public class CheckstyleAnalyzer implements StaticAnalyzer {

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("java");

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
                <!-- Suppress HiddenField for with* builder methods -->
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
        List<AnalysisIssue> issues = new ArrayList<>();

        try {
            // Write content to temp file (Checkstyle requires file input)
            Path tempFile = Files.createTempFile("checkstyle", ".java");
            Files.writeString(tempFile, content);

            try {
                Configuration config = ConfigurationLoader.loadConfiguration(
                    new InputSource(new StringReader(DEFAULT_CONFIG)),
                    new PropertiesExpander(new Properties()),
                    ConfigurationLoader.IgnoredModulesOptions.OMIT
                );

                Checker checker = new Checker();
                // Use Checkstyle's own classloader to find modules (fixes fat JAR issues)
                checker.setModuleClassLoader(Checker.class.getClassLoader());
                checker.configure(config);

                IssueCollector collector = new IssueCollector(filename);
                checker.addListener(collector);

                checker.process(List.of(tempFile.toFile()));
                issues.addAll(collector.getIssues());

                checker.destroy();
            } finally {
                Files.deleteIfExists(tempFile);
            }
        } catch (Exception e) {
            log.warn("Checkstyle analysis failed for {}: {}", filename, e.getMessage());
        }

        return issues;
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
