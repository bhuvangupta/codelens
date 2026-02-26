package com.codelens.intelligence.parser;

import com.codelens.intelligence.graph.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class JavaScriptCodeParser implements CodeParser {

    private static final Logger log = LoggerFactory.getLogger(JavaScriptCodeParser.class);

    // Entity patterns
    private static final Pattern FUNCTION_DECL = Pattern.compile(
        "^\\s*(?:export\\s+)?(?:async\\s+)?function\\s+(\\w+)\\s*\\(");
    private static final Pattern CONST_ARROW = Pattern.compile(
        "^\\s*(?:export\\s+)?(?:const|let|var)\\s+(\\w+)\\s*=\\s*(?:async\\s+)?(?:\\([^)]*\\)|\\w+)\\s*=>");
    private static final Pattern CONST_FUNCTION = Pattern.compile(
        "^\\s*(?:export\\s+)?(?:const|let|var)\\s+(\\w+)\\s*=\\s*(?:async\\s+)?function\\s*\\(");
    private static final Pattern CLASS_DECL = Pattern.compile(
        "^\\s*(?:export\\s+)?(?:abstract\\s+)?class\\s+(\\w+)(?:\\s+extends\\s+(\\w+))?(?:\\s+implements\\s+([\\w,\\s]+))?");
    private static final Pattern INTERFACE_DECL = Pattern.compile(
        "^\\s*(?:export\\s+)?interface\\s+(\\w+)(?:\\s+extends\\s+([\\w,\\s]+))?");
    private static final Pattern TYPE_ALIAS_DECL = Pattern.compile(
        "^\\s*(?:export\\s+)?type\\s+(\\w+)\\s*(?:<[^>]*>)?\\s*=");

    // Test patterns
    private static final Pattern DESCRIBE_BLOCK = Pattern.compile(
        "^\\s*describe\\s*\\(\\s*['\"](.+?)['\"]");
    private static final Pattern TEST_BLOCK = Pattern.compile(
        "^\\s*(?:it|test)\\s*\\(\\s*['\"](.+?)['\"]");

    // Relationship patterns
    private static final Pattern ES_IMPORT = Pattern.compile(
        "^\\s*import\\s+.*?from\\s+['\"](.+?)['\"]");
    private static final Pattern REQUIRE_IMPORT = Pattern.compile(
        "(?:const|let|var)\\s+(?:\\{[^}]+\\}|\\w+)\\s*=\\s*require\\s*\\(\\s*['\"](.+?)['\"]\\s*\\)");
    private static final Pattern EXPORT_DEFAULT = Pattern.compile(
        "^\\s*export\\s+default\\s+(\\w+)");
    private static final Pattern MODULE_EXPORTS = Pattern.compile(
        "^\\s*module\\.exports\\s*=\\s*(\\w+)");
    private static final Pattern NAMED_EXPORT = Pattern.compile(
        "^\\s*export\\s+(?:const|let|var|function|class|async\\s+function)\\s+(\\w+)");

    // HTTP endpoint patterns (Express)
    private static final Pattern EXPRESS_ROUTE = Pattern.compile(
        "(?:app|router)\\s*\\.\\s*(get|post|put|delete|patch)\\s*\\(\\s*['\"](.+?)['\"]");
    // NestJS decorators
    private static final Pattern NESTJS_ROUTE = Pattern.compile(
        "^\\s*@(Get|Post|Put|Delete|Patch)\\s*\\(\\s*['\"]?(.*?)['\"]?\\s*\\)");

    // Function call pattern
    private static final Pattern FUNCTION_CALL = Pattern.compile("\\b(\\w+)\\s*\\(");

    private static final Set<String> CALL_EXCLUSIONS = Set.of(
        "if", "else", "for", "while", "switch", "case", "return", "throw", "new", "typeof",
        "instanceof", "void", "delete", "in", "of", "catch", "finally", "try",
        "import", "export", "from", "require", "console", "log", "warn", "error",
        "describe", "it", "test", "expect", "beforeEach", "afterEach", "beforeAll", "afterAll"
    );

    @Override
    public ParseResult parse(String filePath, String sourceCode) {
        List<CodeEntity> entities = new ArrayList<>();
        List<CodeRelationship> relationships = new ArrayList<>();

        try {
            String[] lines = sourceCode.split("\n");
            boolean isTypeScript = filePath.endsWith(".ts") || filePath.endsWith(".tsx");
            String language = isTypeScript ? "typescript" : "javascript";

            // Module entity for the file itself
            String moduleName = extractModuleName(filePath);
            String moduleId = CodeEntity.buildId(filePath, moduleName);
            entities.add(new CodeEntity(
                moduleId, moduleName, EntityType.MODULE, filePath,
                1, lines.length, language, filePath, "[]", "{}"
            ));

            String currentDescribe = null;

            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                int lineNum = i + 1;

                // Imports
                parseImports(line, moduleId, filePath, lineNum, relationships);

                // Exports
                parseExports(line, moduleId, filePath, lineNum, relationships);

                // Classes
                Matcher classMatcher = CLASS_DECL.matcher(line);
                if (classMatcher.find()) {
                    String className = classMatcher.group(1);
                    String classId = CodeEntity.buildId(filePath, className);
                    int blockEnd = findBraceBlockEnd(lines, i);
                    entities.add(new CodeEntity(
                        classId, className, EntityType.CLASS, filePath,
                        lineNum, blockEnd + 1, language,
                        "class " + className, "[]", "{}"
                    ));
                    if (classMatcher.group(2) != null) {
                        relationships.add(new CodeRelationship(
                            classId, classMatcher.group(2), RelationshipType.EXTENDS,
                            filePath, lineNum, null
                        ));
                    }
                    if (classMatcher.group(3) != null) {
                        for (String impl : classMatcher.group(3).split(",")) {
                            relationships.add(new CodeRelationship(
                                classId, impl.trim(), RelationshipType.IMPLEMENTS,
                                filePath, lineNum, null
                            ));
                        }
                    }
                    continue;
                }

                // Interfaces (TypeScript)
                if (isTypeScript) {
                    Matcher ifaceMatcher = INTERFACE_DECL.matcher(line);
                    if (ifaceMatcher.find()) {
                        String ifaceName = ifaceMatcher.group(1);
                        String ifaceId = CodeEntity.buildId(filePath, ifaceName);
                        int blockEnd = findBraceBlockEnd(lines, i);
                        entities.add(new CodeEntity(
                            ifaceId, ifaceName, EntityType.INTERFACE, filePath,
                            lineNum, blockEnd + 1, language,
                            "interface " + ifaceName, "[]", "{}"
                        ));
                        if (ifaceMatcher.group(2) != null) {
                            for (String ext : ifaceMatcher.group(2).split(",")) {
                                relationships.add(new CodeRelationship(
                                    ifaceId, ext.trim(), RelationshipType.EXTENDS,
                                    filePath, lineNum, null
                                ));
                            }
                        }
                        continue;
                    }

                    Matcher typeMatcher = TYPE_ALIAS_DECL.matcher(line);
                    if (typeMatcher.find()) {
                        String typeName = typeMatcher.group(1);
                        entities.add(new CodeEntity(
                            CodeEntity.buildId(filePath, typeName), typeName, EntityType.TYPE_ALIAS,
                            filePath, lineNum, lineNum, language,
                            "type " + typeName, "[]", "{}"
                        ));
                        continue;
                    }
                }

                // Test blocks
                Matcher describeMatcher = DESCRIBE_BLOCK.matcher(line);
                if (describeMatcher.find()) {
                    currentDescribe = describeMatcher.group(1);
                    int blockEnd = findBraceBlockEnd(lines, i);
                    String descId = CodeEntity.buildId(filePath, "describe:" + currentDescribe);
                    entities.add(new CodeEntity(
                        descId, "describe:" + currentDescribe, EntityType.TEST, filePath,
                        lineNum, blockEnd + 1, language,
                        "describe('" + currentDescribe + "')", "[]", "{}"
                    ));
                    continue;
                }

                Matcher testMatcher = TEST_BLOCK.matcher(line);
                if (testMatcher.find()) {
                    String testName = testMatcher.group(1);
                    int blockEnd = findBraceBlockEnd(lines, i);
                    String testId = CodeEntity.buildId(filePath, "test:" + testName);
                    entities.add(new CodeEntity(
                        testId, "test:" + testName, EntityType.TEST, filePath,
                        lineNum, blockEnd + 1, language,
                        "test('" + testName + "')", "[]", "{}"
                    ));
                    // Infer test target
                    String target = inferJsTestTarget(testName, currentDescribe);
                    if (target != null) {
                        relationships.add(new CodeRelationship(
                            testId, target, RelationshipType.TESTS,
                            filePath, lineNum, null
                        ));
                    }
                    continue;
                }

                // Functions (declaration, const arrow, const function)
                String funcName = matchFunction(line);
                if (funcName != null) {
                    String funcId = CodeEntity.buildId(filePath, funcName);
                    int blockEnd = findBraceBlockEnd(lines, i);
                    boolean isComponent = Character.isUpperCase(funcName.charAt(0));
                    String metadata = isComponent ? "{\"component\":true}" : "{}";
                    entities.add(new CodeEntity(
                        funcId, funcName, EntityType.FUNCTION, filePath,
                        lineNum, blockEnd + 1, language,
                        funcName + "()", "[]", metadata
                    ));
                    // Extract calls within the function body
                    extractCalls(lines, i, blockEnd, funcId, filePath, relationships);
                    continue;
                }

                // HTTP endpoints (Express/NestJS)
                parseHttpEndpoints(line, filePath, lineNum, moduleId, relationships);
            }
        } catch (Exception e) {
            log.warn("Failed to parse JS/TS file {}: {}", filePath, e.getMessage());
        }

        return new ParseResult(entities, relationships);
    }

    private String matchFunction(String line) {
        Matcher m = FUNCTION_DECL.matcher(line);
        if (m.find()) return m.group(1);
        m = CONST_ARROW.matcher(line);
        if (m.find()) return m.group(1);
        m = CONST_FUNCTION.matcher(line);
        if (m.find()) return m.group(1);
        return null;
    }

    private void parseImports(String line, String moduleId, String filePath, int lineNum,
                              List<CodeRelationship> relationships) {
        Matcher m = ES_IMPORT.matcher(line);
        if (m.find()) {
            relationships.add(new CodeRelationship(
                moduleId, m.group(1), RelationshipType.IMPORTS, filePath, lineNum, null
            ));
            return;
        }
        m = REQUIRE_IMPORT.matcher(line);
        if (m.find()) {
            relationships.add(new CodeRelationship(
                moduleId, m.group(1), RelationshipType.IMPORTS, filePath, lineNum, null
            ));
        }
    }

    private void parseExports(String line, String moduleId, String filePath, int lineNum,
                              List<CodeRelationship> relationships) {
        Matcher m = EXPORT_DEFAULT.matcher(line);
        if (m.find()) {
            relationships.add(new CodeRelationship(
                moduleId, m.group(1), RelationshipType.EXPORTS, filePath, lineNum, null
            ));
            return;
        }
        m = MODULE_EXPORTS.matcher(line);
        if (m.find()) {
            relationships.add(new CodeRelationship(
                moduleId, m.group(1), RelationshipType.EXPORTS, filePath, lineNum, null
            ));
            return;
        }
        m = NAMED_EXPORT.matcher(line);
        if (m.find()) {
            relationships.add(new CodeRelationship(
                moduleId, m.group(1), RelationshipType.EXPORTS, filePath, lineNum, null
            ));
        }
    }

    private void parseHttpEndpoints(String line, String filePath, int lineNum, String sourceId,
                                    List<CodeRelationship> relationships) {
        Matcher m = EXPRESS_ROUTE.matcher(line);
        if (m.find()) {
            String method = m.group(1).toUpperCase();
            String path = m.group(2);
            String metadata = "{\"method\":\"" + method + "\",\"path\":\"" + path + "\"}";
            relationships.add(new CodeRelationship(
                sourceId, method + " " + path, RelationshipType.HTTP_ENDPOINT,
                filePath, lineNum, metadata
            ));
            return;
        }
        m = NESTJS_ROUTE.matcher(line);
        if (m.find()) {
            String method = m.group(1).toUpperCase();
            String path = m.group(2).isEmpty() ? "/" : m.group(2);
            String metadata = "{\"method\":\"" + method + "\",\"path\":\"" + path + "\"}";
            relationships.add(new CodeRelationship(
                sourceId, method + " " + path, RelationshipType.HTTP_ENDPOINT,
                filePath, lineNum, metadata
            ));
        }
    }

    private void extractCalls(String[] lines, int startLine, int endLine, String sourceId,
                              String filePath, List<CodeRelationship> relationships) {
        for (int i = startLine + 1; i <= endLine && i < lines.length; i++) {
            Matcher m = FUNCTION_CALL.matcher(lines[i]);
            while (m.find()) {
                String name = m.group(1);
                if (!CALL_EXCLUSIONS.contains(name) && !Character.isUpperCase(name.charAt(0))) {
                    String targetId = CodeEntity.buildId(filePath, name);
                    relationships.add(new CodeRelationship(
                        sourceId, targetId, RelationshipType.CALLS,
                        filePath, i + 1, null
                    ));
                }
            }
        }
    }

    private int findBraceBlockEnd(String[] lines, int startLine) {
        int depth = 0;
        boolean found = false;
        for (int i = startLine; i < lines.length; i++) {
            for (char c : lines[i].toCharArray()) {
                if (c == '{') { depth++; found = true; }
                else if (c == '}') { depth--; }
                if (found && depth == 0) return i;
            }
        }
        return Math.min(startLine + 1, lines.length - 1);
    }

    private String inferJsTestTarget(String testName, String describeName) {
        // "should process order correctly" -> "processOrder"
        if (testName.startsWith("should ")) {
            String[] words = testName.substring(7).split("\\s+");
            if (words.length >= 2) {
                StringBuilder sb = new StringBuilder(words[0]);
                for (int i = 1; i < Math.min(words.length, 3); i++) {
                    if (!words[i].isEmpty()) {
                        sb.append(Character.toUpperCase(words[i].charAt(0)));
                        if (words[i].length() > 1) sb.append(words[i].substring(1));
                    }
                }
                return sb.toString();
            }
        }
        // Fall back to describe name as target
        if (describeName != null) {
            // "UserService" -> "UserService"
            return describeName;
        }
        return null;
    }

    private String extractModuleName(String filePath) {
        int lastSlash = filePath.lastIndexOf('/');
        String fileName = lastSlash >= 0 ? filePath.substring(lastSlash + 1) : filePath;
        int dot = fileName.lastIndexOf('.');
        return dot >= 0 ? fileName.substring(0, dot) : fileName;
    }
}
