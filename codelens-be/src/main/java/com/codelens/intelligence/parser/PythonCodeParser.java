package com.codelens.intelligence.parser;

import com.codelens.intelligence.graph.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class PythonCodeParser implements CodeParser {

    private static final Logger log = LoggerFactory.getLogger(PythonCodeParser.class);

    // Entity patterns
    private static final Pattern FUNCTION_DEF = Pattern.compile(
        "^(\\s*)(?:async\\s+)?def\\s+(\\w+)\\s*\\((.*)\\)");
    private static final Pattern CLASS_DEF = Pattern.compile(
        "^(\\s*)class\\s+(\\w+)(?:\\(([^)]+)\\))?\\s*:");

    // Relationship patterns
    private static final Pattern IMPORT_MODULE = Pattern.compile(
        "^\\s*import\\s+([\\w.]+)");
    private static final Pattern FROM_IMPORT = Pattern.compile(
        "^\\s*from\\s+([\\w.]+)\\s+import\\s+(.+)");

    // HTTP endpoint patterns (Flask/FastAPI)
    private static final Pattern FLASK_ROUTE = Pattern.compile(
        "^\\s*@(?:app|blueprint|bp)\\.route\\s*\\(\\s*['\"](.+?)['\"](?:.*?methods\\s*=\\s*\\[([^]]+)])?" );
    private static final Pattern FASTAPI_ROUTE = Pattern.compile(
        "^\\s*@(?:app|router)\\.(get|post|put|delete|patch)\\s*\\(\\s*['\"](.+?)['\"]");

    // Decorator pattern
    private static final Pattern DECORATOR = Pattern.compile(
        "^\\s*@(\\w+(?:\\.\\w+)*)");

    // Function call pattern
    private static final Pattern FUNCTION_CALL = Pattern.compile("\\b(\\w+)\\s*\\(");

    private static final Set<String> CALL_EXCLUSIONS = Set.of(
        "if", "elif", "else", "for", "while", "with", "return", "raise", "yield",
        "import", "from", "print", "len", "range", "enumerate", "zip", "map", "filter",
        "isinstance", "issubclass", "hasattr", "getattr", "setattr", "type", "super",
        "str", "int", "float", "bool", "list", "dict", "set", "tuple", "bytes",
        "None", "True", "False"
    );

    @Override
    public ParseResult parse(String filePath, String sourceCode) {
        List<CodeEntity> entities = new ArrayList<>();
        List<CodeRelationship> relationships = new ArrayList<>();

        try {
            String[] lines = sourceCode.split("\n");

            // Module entity
            String moduleName = extractModuleName(filePath);
            String moduleId = CodeEntity.buildId(filePath, moduleName);
            entities.add(new CodeEntity(
                moduleId, moduleName, EntityType.MODULE, filePath,
                1, lines.length, "python", filePath, "[]", "{}"
            ));

            List<String> pendingDecorators = new ArrayList<>();

            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                int lineNum = i + 1;

                // Imports
                parseImports(line, moduleId, filePath, lineNum, relationships);

                // Collect decorators
                Matcher decoMatcher = DECORATOR.matcher(line);
                if (decoMatcher.find() && !line.trim().startsWith("@property")) {
                    pendingDecorators.add(decoMatcher.group(1));
                    // Check for Flask/FastAPI routes in decorators
                    parseHttpDecorator(line, filePath, lineNum, moduleId, relationships);
                    continue;
                }

                // Classes
                Matcher classMatcher = CLASS_DEF.matcher(line);
                if (classMatcher.find()) {
                    String indent = classMatcher.group(1);
                    String className = classMatcher.group(2);
                    String bases = classMatcher.group(3);
                    String classId = CodeEntity.buildId(filePath, className);
                    int blockEnd = findPythonBlockEnd(lines, i, indent.length());
                    boolean isTest = className.startsWith("Test") || className.endsWith("Test");
                    EntityType type = isTest ? EntityType.TEST : EntityType.CLASS;

                    String annotations = formatAnnotations(pendingDecorators);
                    pendingDecorators.clear();

                    entities.add(new CodeEntity(
                        classId, className, type, filePath,
                        lineNum, blockEnd + 1, "python",
                        "class " + className, annotations, "{}"
                    ));

                    // Inheritance
                    if (bases != null) {
                        for (String base : bases.split(",")) {
                            String baseName = base.trim().split("[\\[(]")[0].trim();
                            if (!baseName.isEmpty() && !baseName.equals("object")) {
                                relationships.add(new CodeRelationship(
                                    classId, baseName, RelationshipType.EXTENDS,
                                    filePath, lineNum, null
                                ));
                            }
                        }
                    }
                    continue;
                }

                // Functions
                Matcher funcMatcher = FUNCTION_DEF.matcher(line);
                if (funcMatcher.find()) {
                    String indent = funcMatcher.group(1);
                    String funcName = funcMatcher.group(2);
                    String funcId = CodeEntity.buildId(filePath, funcName);
                    int blockEnd = findPythonBlockEnd(lines, i, indent.length());
                    boolean isTest = funcName.startsWith("test_") || funcName.startsWith("test");
                    EntityType type = isTest ? EntityType.TEST : EntityType.FUNCTION;

                    String annotations = formatAnnotations(pendingDecorators);
                    pendingDecorators.clear();

                    entities.add(new CodeEntity(
                        funcId, funcName, type, filePath,
                        lineNum, blockEnd + 1, "python",
                        funcName + "()", annotations, "{}"
                    ));

                    // Extract calls within function body
                    extractCalls(lines, i, blockEnd, funcId, filePath, relationships);

                    // Test target inference
                    if (isTest) {
                        String target = inferPythonTestTarget(funcName);
                        if (target != null) {
                            relationships.add(new CodeRelationship(
                                funcId, target, RelationshipType.TESTS,
                                filePath, lineNum, null
                            ));
                        }
                    }
                    continue;
                }

                // Clear decorators if line is not a decorator, class, or function
                if (!line.trim().isEmpty()) {
                    pendingDecorators.clear();
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse Python file {}: {}", filePath, e.getMessage());
        }

        return new ParseResult(entities, relationships);
    }

    private void parseImports(String line, String moduleId, String filePath, int lineNum,
                              List<CodeRelationship> relationships) {
        Matcher m = FROM_IMPORT.matcher(line);
        if (m.find()) {
            relationships.add(new CodeRelationship(
                moduleId, m.group(1), RelationshipType.IMPORTS, filePath, lineNum, null
            ));
            return;
        }
        m = IMPORT_MODULE.matcher(line);
        if (m.find()) {
            relationships.add(new CodeRelationship(
                moduleId, m.group(1), RelationshipType.IMPORTS, filePath, lineNum, null
            ));
        }
    }

    private void parseHttpDecorator(String line, String filePath, int lineNum, String sourceId,
                                    List<CodeRelationship> relationships) {
        Matcher m = FASTAPI_ROUTE.matcher(line);
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
        m = FLASK_ROUTE.matcher(line);
        if (m.find()) {
            String path = m.group(1);
            String methods = m.group(2);
            String method = "GET";
            if (methods != null) {
                method = methods.replaceAll("['\"]", "").split(",")[0].trim().toUpperCase();
            }
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
                if (!CALL_EXCLUSIONS.contains(name) && !name.startsWith("_")
                        && Character.isLowerCase(name.charAt(0))) {
                    String targetId = CodeEntity.buildId(filePath, name);
                    relationships.add(new CodeRelationship(
                        sourceId, targetId, RelationshipType.CALLS,
                        filePath, i + 1, null
                    ));
                }
            }
        }
    }

    /**
     * Find the end of a Python block using indentation.
     * A block ends when a non-empty line has indentation <= the block's defining line.
     */
    private int findPythonBlockEnd(String[] lines, int startLine, int defIndent) {
        for (int i = startLine + 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.trim().isEmpty()) continue;
            int indent = countIndent(line);
            if (indent <= defIndent) return i - 1;
        }
        return lines.length - 1;
    }

    private int countIndent(String line) {
        int count = 0;
        for (char c : line.toCharArray()) {
            if (c == ' ') count++;
            else if (c == '\t') count += 4;
            else break;
        }
        return count;
    }

    private String inferPythonTestTarget(String testName) {
        // test_process_order -> process_order
        if (testName.startsWith("test_") && testName.length() > 5) {
            return testName.substring(5);
        }
        return null;
    }

    private String formatAnnotations(List<String> decorators) {
        if (decorators.isEmpty()) return "[]";
        var sb = new StringBuilder("[");
        for (int i = 0; i < decorators.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(decorators.get(i)).append("\"");
        }
        sb.append("]");
        return sb.toString();
    }

    private String extractModuleName(String filePath) {
        int lastSlash = filePath.lastIndexOf('/');
        String fileName = lastSlash >= 0 ? filePath.substring(lastSlash + 1) : filePath;
        int dot = fileName.lastIndexOf('.');
        return dot >= 0 ? fileName.substring(0, dot) : fileName;
    }
}
