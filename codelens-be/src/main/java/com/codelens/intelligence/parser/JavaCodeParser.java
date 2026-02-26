package com.codelens.intelligence.parser;

import com.codelens.intelligence.graph.model.*;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Component
public class JavaCodeParser {

    private static final Logger log = LoggerFactory.getLogger(JavaCodeParser.class);

    public record ParseResult(List<CodeEntity> entities, List<CodeRelationship> relationships) {}

    public ParseResult parse(String filePath, String sourceCode) {
        List<CodeEntity> entities = new ArrayList<>();
        List<CodeRelationship> relationships = new ArrayList<>();

        try {
            CompilationUnit cu = StaticJavaParser.parse(sourceCode);
            String basePath = extractBaseMappingPath(cu);

            for (var type : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                parseType(filePath, type, basePath, entities, relationships);
            }
            for (var enumDecl : cu.findAll(EnumDeclaration.class)) {
                parseEnum(filePath, enumDecl, entities);
            }
        } catch (Exception e) {
            log.warn("Failed to parse Java file {}: {}", filePath, e.getMessage());
        }

        return new ParseResult(entities, relationships);
    }

    private void parseType(String filePath, ClassOrInterfaceDeclaration type, String basePath,
                           List<CodeEntity> entities, List<CodeRelationship> relationships) {
        String className = type.getNameAsString();
        String classId = CodeEntity.buildId(filePath, className);
        EntityType entityType = type.isInterface() ? EntityType.INTERFACE : EntityType.CLASS;

        entities.add(new CodeEntity(
            classId, className, entityType, filePath,
            type.getBegin().map(p -> p.line).orElse(0),
            type.getEnd().map(p -> p.line).orElse(0),
            "java", type.getNameAsString(),
            toAnnotationJson(type), "{}"
        ));

        // extends
        type.getExtendedTypes().forEach(ext ->
            relationships.add(new CodeRelationship(
                classId, ext.getNameAsString(), RelationshipType.EXTENDS,
                filePath, type.getBegin().map(p -> p.line).orElse(0), null
            ))
        );

        // implements
        type.getImplementedTypes().forEach(impl ->
            relationships.add(new CodeRelationship(
                classId, impl.getNameAsString(), RelationshipType.IMPLEMENTS,
                filePath, type.getBegin().map(p -> p.line).orElse(0), null
            ))
        );

        // DI: @Autowired fields
        for (var field : type.getFields()) {
            if (hasAnnotation(field, "Autowired") || hasAnnotation(field, "Inject")) {
                field.getVariables().forEach(v -> {
                    String fieldType = field.getElementType().asString();
                    relationships.add(new CodeRelationship(
                        classId, fieldType, RelationshipType.INJECTS,
                        filePath, field.getBegin().map(p -> p.line).orElse(0), null
                    ));
                });
            }
        }

        // DI: constructor injection in Spring-managed classes
        for (var ctor : type.getConstructors()) {
            ctor.getParameters().forEach(param -> {
                String paramType = param.getTypeAsString();
                if (hasAnnotation(type, "Service") || hasAnnotation(type, "Component")
                    || hasAnnotation(type, "RestController") || hasAnnotation(type, "Controller")) {
                    relationships.add(new CodeRelationship(
                        classId, paramType, RelationshipType.INJECTS,
                        filePath, ctor.getBegin().map(p -> p.line).orElse(0), null
                    ));
                }
            });
        }

        // Methods
        for (var method : type.getMethods()) {
            parseMethod(filePath, classId, method, basePath, entities, relationships);
        }
    }

    private void parseMethod(String filePath, String classId,
                             MethodDeclaration method, String basePath,
                             List<CodeEntity> entities, List<CodeRelationship> relationships) {
        String methodName = method.getNameAsString();
        String methodId = CodeEntity.buildId(filePath, methodName);
        boolean isTest = hasAnnotation(method, "Test") || hasAnnotation(method, "ParameterizedTest");
        EntityType type = isTest ? EntityType.TEST : EntityType.FUNCTION;

        entities.add(new CodeEntity(
            methodId, methodName, type, filePath,
            method.getBegin().map(p -> p.line).orElse(0),
            method.getEnd().map(p -> p.line).orElse(0),
            "java", method.getDeclarationAsString(true, true, true),
            toAnnotationJson(method), "{}"
        ));

        // HTTP endpoints
        extractHttpEndpoint(method, methodId, filePath, basePath, relationships);

        // Method calls within this method
        method.findAll(MethodCallExpr.class).forEach(call -> {
            String callTarget = call.getNameAsString();
            String targetId = CodeEntity.buildId(filePath, callTarget);
            relationships.add(new CodeRelationship(
                methodId, targetId, RelationshipType.CALLS,
                filePath, call.getBegin().map(p -> p.line).orElse(0), null
            ));
        });

        // throws
        method.getThrownExceptions().forEach(ex ->
            relationships.add(new CodeRelationship(
                methodId, ex.asString(), RelationshipType.THROWS,
                filePath, method.getBegin().map(p -> p.line).orElse(0), null
            ))
        );

        // Test target heuristic
        if (isTest) {
            String targetMethod = inferTestTarget(methodName);
            if (targetMethod != null) {
                relationships.add(new CodeRelationship(
                    methodId, targetMethod, RelationshipType.TESTS,
                    filePath, method.getBegin().map(p -> p.line).orElse(0), null
                ));
            }
        }
    }

    private void parseEnum(String filePath, EnumDeclaration enumDecl, List<CodeEntity> entities) {
        String name = enumDecl.getNameAsString();
        entities.add(new CodeEntity(
            CodeEntity.buildId(filePath, name), name, EntityType.ENUM, filePath,
            enumDecl.getBegin().map(p -> p.line).orElse(0),
            enumDecl.getEnd().map(p -> p.line).orElse(0),
            "java", "enum " + name, toAnnotationJson(enumDecl), "{}"
        ));
    }

    private void extractHttpEndpoint(MethodDeclaration method, String methodId,
                                     String filePath, String basePath,
                                     List<CodeRelationship> relationships) {
        Map<String, String> mappingAnnotations = Map.of(
            "GetMapping", "GET", "PostMapping", "POST",
            "PutMapping", "PUT", "DeleteMapping", "DELETE",
            "PatchMapping", "PATCH", "RequestMapping", "REQUEST"
        );

        for (var ann : method.getAnnotations()) {
            String annName = ann.getNameAsString();
            String httpMethod = mappingAnnotations.get(annName);
            if (httpMethod == null) continue;

            String path = basePath;
            if (ann instanceof SingleMemberAnnotationExpr single) {
                path += extractStringValue(single.getMemberValue());
            } else if (ann instanceof NormalAnnotationExpr normal) {
                for (var pair : normal.getPairs()) {
                    if ("value".equals(pair.getNameAsString()) || "path".equals(pair.getNameAsString())) {
                        path += extractStringValue(pair.getValue());
                    }
                    if ("method".equals(pair.getNameAsString())) {
                        httpMethod = pair.getValue().toString().replace("RequestMethod.", "");
                    }
                }
            }

            String metadata = "{\"method\":\"" + httpMethod + "\",\"path\":\"" + path + "\"}";
            relationships.add(new CodeRelationship(
                methodId, httpMethod + " " + path, RelationshipType.HTTP_ENDPOINT,
                filePath, method.getBegin().map(p -> p.line).orElse(0), metadata
            ));
        }
    }

    private String extractBaseMappingPath(CompilationUnit cu) {
        for (var type : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            for (var ann : type.getAnnotations()) {
                if ("RequestMapping".equals(ann.getNameAsString())) {
                    if (ann instanceof SingleMemberAnnotationExpr single) {
                        return extractStringValue(single.getMemberValue());
                    } else if (ann instanceof NormalAnnotationExpr normal) {
                        for (var pair : normal.getPairs()) {
                            if ("value".equals(pair.getNameAsString())) {
                                return extractStringValue(pair.getValue());
                            }
                        }
                    }
                }
            }
        }
        return "";
    }

    private String extractStringValue(Expression expr) {
        if (expr instanceof StringLiteralExpr str) return str.getValue();
        return expr.toString().replace("\"", "");
    }

    private boolean hasAnnotation(NodeWithAnnotations<?> node, String name) {
        return node.getAnnotations().stream()
            .anyMatch(a -> a.getNameAsString().equals(name));
    }

    private String toAnnotationJson(NodeWithAnnotations<?> node) {
        var names = node.getAnnotations().stream()
            .map(a -> "\"" + a.getNameAsString() + "\"")
            .collect(Collectors.joining(","));
        return "[" + names + "]";
    }

    private String inferTestTarget(String testMethodName) {
        if (testMethodName.startsWith("test") && testMethodName.length() > 4) {
            String target = testMethodName.substring(4);
            return Character.toLowerCase(target.charAt(0)) + target.substring(1);
        }
        return null;
    }
}
