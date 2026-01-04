package com.codelens.core;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts smart context from files to optimize token usage in LLM reviews.
 * Instead of sending full file content, extracts only relevant context based on:
 * - File type and language
 * - Patterns that need context (hooks, DI, annotations)
 * - Changed code dependencies
 */
@Slf4j
@Component
public class SmartContextExtractor {

    /**
     * Review mode determines how much context to send to LLM
     */
    public enum ReviewMode {
        SKIP_LLM,        // Static analysis only, no LLM (lock files, assets, generated)
        DIFF_ONLY,       // Send only the diff, no file context
        SMART_CONTEXT,   // Send diff + extracted relevant context
        SECURITY_SCAN    // Lightweight security-focused review (sensitive configs)
    }

    /**
     * Result of context extraction
     */
    public record ExtractionResult(
        ReviewMode mode,
        String context,
        String reason
    ) {}

    // === JavaScript/React Patterns ===
    private static final Pattern JS_IMPORT_PATTERN = Pattern.compile(
        "^import\\s+.*?['\"];?\\s*$", Pattern.MULTILINE);
    private static final Pattern JS_USE_STATE_PATTERN = Pattern.compile(
        "\\buse(State|Reducer|Context|Ref|Memo|Callback)\\s*[<(]");
    private static final Pattern JS_USE_EFFECT_PATTERN = Pattern.compile(
        "\\buseEffect\\s*\\(");
    private static final Pattern JS_COMPONENT_PATTERN = Pattern.compile(
        "(function\\s+[A-Z]\\w*|const\\s+[A-Z]\\w*\\s*=|class\\s+[A-Z]\\w*\\s+extends)");
    private static final Pattern JS_HOOK_DECLARATION = Pattern.compile(
        "^\\s*const\\s+\\[\\s*(\\w+)\\s*,\\s*set\\w+\\s*\\]\\s*=\\s*use(State|Reducer).*$", Pattern.MULTILINE);
    private static final Pattern JS_CUSTOM_HOOK_PATTERN = Pattern.compile(
        "^\\s*const\\s+(\\w+)\\s*=\\s*use[A-Z]\\w*\\(.*$", Pattern.MULTILINE);

    // === Java Patterns ===
    private static final Pattern JAVA_CLASS_ANNOTATION = Pattern.compile(
        "^@(Service|Component|Repository|Controller|RestController|Configuration|Entity|Transactional|Async).*$",
        Pattern.MULTILINE);
    private static final Pattern JAVA_FIELD_INJECTION = Pattern.compile(
        "^\\s*@(Autowired|Inject|Value|Resource).*$\\s*^\\s*(private|protected)?\\s+\\w+.*?;",
        Pattern.MULTILINE);
    private static final Pattern JAVA_AUTOWIRED_FIELD = Pattern.compile(
        "^\\s*(?:@Autowired|@Inject|@Resource).*?$\\s*^\\s*(?:private|protected|public)?\\s+(\\w+(?:<[^>]+>)?)\\s+(\\w+)\\s*;",
        Pattern.MULTILINE | Pattern.DOTALL);
    private static final Pattern JAVA_METHOD_ANNOTATION = Pattern.compile(
        "^\\s*@(Transactional|Async|Cacheable|Scheduled|PreAuthorize|PostAuthorize).*$",
        Pattern.MULTILINE);
    private static final Pattern JAVA_CLASS_DECLARATION = Pattern.compile(
        "^(public\\s+)?(abstract\\s+)?(class|interface|enum)\\s+(\\w+).*\\{",
        Pattern.MULTILINE);
    private static final Pattern JAVA_IMPORT_PATTERN = Pattern.compile(
        "^import\\s+.*?;\\s*$", Pattern.MULTILINE);

    // === File Type Patterns for Skip (truly static, no security concerns) ===
    private static final List<Pattern> SKIP_LLM_PATTERNS = List.of(
        // Documentation
        Pattern.compile(".*\\.(md|txt|rst|adoc)$", Pattern.CASE_INSENSITIVE),
        // Lock files
        Pattern.compile(".*(package-lock|yarn\\.lock|pnpm-lock|Gemfile\\.lock|poetry\\.lock|composer\\.lock).*"),
        // Generated files
        Pattern.compile(".*\\.generated\\.(java|ts|js)$"),
        Pattern.compile(".*_pb2?\\.py$"),
        // Assets
        Pattern.compile(".*\\.(css|scss|less|svg|png|jpg|gif|ico|woff|ttf|eot)$", Pattern.CASE_INSENSITIVE),
        // Schema/type definition files (not secrets)
        Pattern.compile(".*\\.(xsd|dtd|wsdl)$", Pattern.CASE_INSENSITIVE),
        // .env files - NEVER send to LLM, too sensitive (static analysis will catch patterns)
        Pattern.compile(".*\\.env(\\.\\w+)?$", Pattern.CASE_INSENSITIVE)
    );

    // === Sensitive Config Files that need Security Scan ===
    // These could contain secrets, credentials, or security misconfigurations
    // NOTE: .env files are excluded - too sensitive, static analysis only
    private static final List<Pattern> SECURITY_SCAN_PATTERNS = List.of(
        // Application configs (may contain credentials, API keys)
        Pattern.compile(".*application[.-]?(\\w+)?\\.(yaml|yml|properties)$", Pattern.CASE_INSENSITIVE),
        // .env files are EXCLUDED - they should never be sent to LLM
        Pattern.compile(".*config\\.(yaml|yml|json|properties)$", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*/config/.*\\.(yaml|yml|json|properties)$", Pattern.CASE_INSENSITIVE),
        // Docker/Container configs
        Pattern.compile(".*[Dd]ockerfile.*"),
        Pattern.compile(".*docker-compose.*\\.(yaml|yml)$", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*\\.dockerignore$"),
        // CI/CD configs (may expose secrets, have insecure steps)
        Pattern.compile(".*\\.github/workflows/.*\\.(yaml|yml)$"),
        Pattern.compile(".*\\.gitlab-ci\\.(yaml|yml)$"),
        Pattern.compile(".*[Jj]enkinsfile.*"),
        Pattern.compile(".*\\.travis\\.yml$"),
        Pattern.compile(".*azure-pipelines.*\\.(yaml|yml)$"),
        // Kubernetes/Infrastructure
        Pattern.compile(".*\\.(k8s|kubernetes)\\.(yaml|yml)$", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*/k8s/.*\\.(yaml|yml)$"),
        Pattern.compile(".*/kubernetes/.*\\.(yaml|yml)$"),
        Pattern.compile(".*values.*\\.(yaml|yml)$", Pattern.CASE_INSENSITIVE),  // Helm values
        // Security-specific configs
        Pattern.compile(".*security.*\\.(xml|yaml|yml|json)$", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*auth.*\\.(xml|yaml|yml|json)$", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*oauth.*\\.(xml|yaml|yml|json)$", Pattern.CASE_INSENSITIVE),
        // Database configs
        Pattern.compile(".*database.*\\.(yaml|yml|properties|xml)$", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*persistence.*\\.xml$", Pattern.CASE_INSENSITIVE),
        // Web server configs
        Pattern.compile(".*nginx.*\\.conf$", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*apache.*\\.conf$", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*\\.htaccess$")
    );

    // === Non-sensitive Config Files (skip LLM) ===
    // Config files that rarely contain secrets
    private static final List<Pattern> SAFE_CONFIG_PATTERNS = List.of(
        Pattern.compile(".*eslint.*\\.(json|js|yaml|yml)$", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*prettier.*\\.(json|js|yaml|yml)$", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*tsconfig.*\\.json$", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*package\\.json$"),
        Pattern.compile(".*\\.editorconfig$"),
        Pattern.compile(".*\\.gitignore$"),
        Pattern.compile(".*\\.gitattributes$"),
        Pattern.compile(".*babel.*\\.(json|js)$", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*jest.*\\.(json|js)$", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*webpack.*\\.(json|js)$", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*vite.*\\.(json|js|ts)$", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*pom\\.xml$"),
        Pattern.compile(".*build\\.gradle(\\.kts)?$")
    );

    // Patterns for simple Java files (DTOs, entities with only getters/setters)
    private static final Pattern JAVA_SIMPLE_DTO = Pattern.compile(
        "@(Data|Getter|Setter|Builder|NoArgsConstructor|AllArgsConstructor|Entity)");
    private static final Pattern JAVA_ONLY_ACCESSORS = Pattern.compile(
        "^\\s*(public|private|protected)?\\s+\\w+\\s+(get|set|is)\\w+\\s*\\(");

    /**
     * Determine review mode and extract context for a file
     */
    public ExtractionResult extract(String filename, String fileContent, String patch) {
        // 1. Check if file should skip LLM entirely (assets, lock files, docs)
        for (Pattern pattern : SKIP_LLM_PATTERNS) {
            if (pattern.matcher(filename).matches()) {
                return new ExtractionResult(ReviewMode.SKIP_LLM, null,
                    "Asset/lock/doc file - static analysis only");
            }
        }

        // 2. Check for safe config files (no secrets, skip LLM)
        for (Pattern pattern : SAFE_CONFIG_PATTERNS) {
            if (pattern.matcher(filename).matches()) {
                return new ExtractionResult(ReviewMode.SKIP_LLM, null,
                    "Build/lint config - static analysis only");
            }
        }

        // 3. Check for sensitive config files that need security scan
        // NOTE: Only send the DIFF, not full file content, to avoid leaking existing secrets
        for (Pattern pattern : SECURITY_SCAN_PATTERNS) {
            if (pattern.matcher(filename).matches()) {
                log.debug("Sensitive config file detected: {}", filename);
                // Pass null for context - ReviewEngine will use diff-only with security prompt
                // This ensures we only scan NEW/CHANGED lines, not existing secrets
                return new ExtractionResult(ReviewMode.SECURITY_SCAN, null,
                    "Sensitive config - security scan (diff only)");
            }
        }

        if (fileContent == null || fileContent.isBlank()) {
            return new ExtractionResult(ReviewMode.DIFF_ONLY, null, "No file content available");
        }

        // Detect language and extract appropriate context
        String lowerFilename = filename.toLowerCase();

        if (isJavaScript(lowerFilename)) {
            return extractJavaScriptContext(filename, fileContent, patch);
        } else if (isJava(lowerFilename)) {
            return extractJavaContext(filename, fileContent, patch);
        } else if (isPython(lowerFilename)) {
            return extractPythonContext(filename, fileContent, patch);
        }

        // Default: diff-only for unknown languages
        return new ExtractionResult(ReviewMode.DIFF_ONLY, null,
            "Unknown language - using diff only");
    }

    // === JavaScript/React Context Extraction ===

    private ExtractionResult extractJavaScriptContext(String filename, String content, String patch) {
        // Check if file uses React hooks or state
        boolean hasHooks = JS_USE_STATE_PATTERN.matcher(content).find() ||
                          JS_USE_EFFECT_PATTERN.matcher(content).find();
        boolean isComponent = JS_COMPONENT_PATTERN.matcher(content).find();

        if (!hasHooks && !isComponent) {
            // Simple utility file - diff only
            return new ExtractionResult(ReviewMode.DIFF_ONLY, null,
                "Simple JS utility - no hooks/state");
        }

        // Extract smart context for React files
        StringBuilder context = new StringBuilder();

        // 1. Extract imports (needed to understand dependencies)
        List<String> imports = extractMatches(content, JS_IMPORT_PATTERN);
        if (!imports.isEmpty()) {
            context.append("// Imports:\n");
            // Only include relevant imports (React, hooks, types)
            for (String imp : imports) {
                if (imp.contains("react") || imp.contains("use") ||
                    imp.contains("type") || imp.contains("interface") ||
                    isImportUsedInPatch(imp, patch)) {
                    context.append(imp).append("\n");
                }
            }
            context.append("\n");
        }

        // 2. Extract state declarations
        List<String> stateDeclarations = extractMatches(content, JS_HOOK_DECLARATION);
        List<String> customHooks = extractMatches(content, JS_CUSTOM_HOOK_PATTERN);

        if (!stateDeclarations.isEmpty() || !customHooks.isEmpty()) {
            context.append("// State & Hooks:\n");
            for (String state : stateDeclarations) {
                context.append(state.trim()).append("\n");
            }
            for (String hook : customHooks) {
                context.append(hook.trim()).append("\n");
            }
            context.append("\n");
        }

        // 3. Extract type definitions if TypeScript
        if (filename.endsWith(".ts") || filename.endsWith(".tsx")) {
            String types = extractTypeDefinitions(content, patch);
            if (!types.isEmpty()) {
                context.append("// Types:\n").append(types).append("\n");
            }
        }

        String contextStr = context.toString().trim();
        if (contextStr.isEmpty()) {
            return new ExtractionResult(ReviewMode.DIFF_ONLY, null,
                "React file but no extractable context");
        }

        log.debug("Extracted {} chars of JS context for {}", contextStr.length(), filename);
        return new ExtractionResult(ReviewMode.SMART_CONTEXT, contextStr,
            "React component with hooks/state");
    }

    // === Java Context Extraction ===

    private ExtractionResult extractJavaContext(String filename, String content, String patch) {
        // Check if simple DTO/Entity with only Lombok annotations
        if (isSimpleJavaDto(content)) {
            return new ExtractionResult(ReviewMode.SKIP_LLM, null,
                "Simple DTO/Entity - static analysis only");
        }

        // Check if file has DI or annotations that need context
        boolean hasFieldInjection = JAVA_AUTOWIRED_FIELD.matcher(content).find();
        boolean hasClassAnnotations = JAVA_CLASS_ANNOTATION.matcher(content).find();
        boolean hasMethodAnnotations = JAVA_METHOD_ANNOTATION.matcher(content).find();

        if (!hasFieldInjection && !hasClassAnnotations && !hasMethodAnnotations) {
            // Simple utility class - diff only
            return new ExtractionResult(ReviewMode.DIFF_ONLY, null,
                "Simple Java class - no DI/annotations");
        }

        // Extract smart context
        StringBuilder context = new StringBuilder();

        // 1. Extract relevant imports
        List<String> imports = extractMatches(content, JAVA_IMPORT_PATTERN);
        if (!imports.isEmpty()) {
            context.append("// Key imports:\n");
            for (String imp : imports) {
                // Only include Spring, JPA, security imports
                if (imp.contains("springframework") || imp.contains("javax.persistence") ||
                    imp.contains("jakarta.persistence") || imp.contains("security") ||
                    imp.contains("transaction") || isImportUsedInPatch(imp, patch)) {
                    context.append(imp).append("\n");
                }
            }
            context.append("\n");
        }

        // 2. Extract class declaration with annotations
        String classHeader = extractClassHeader(content);
        if (classHeader != null) {
            context.append("// Class definition:\n").append(classHeader).append("\n\n");
        }

        // 3. Extract injected fields
        List<String> injectedFields = extractInjectedFields(content);
        if (!injectedFields.isEmpty()) {
            context.append("// Injected dependencies:\n");
            for (String field : injectedFields) {
                context.append(field).append("\n");
            }
            context.append("\n");
        }

        // 4. Extract method-level annotations for context
        if (hasMethodAnnotations) {
            String methodAnnotations = extractMethodAnnotations(content, patch);
            if (!methodAnnotations.isEmpty()) {
                context.append("// Method annotations:\n").append(methodAnnotations).append("\n");
            }
        }

        String contextStr = context.toString().trim();
        if (contextStr.isEmpty()) {
            return new ExtractionResult(ReviewMode.DIFF_ONLY, null,
                "Java file but no extractable context");
        }

        log.debug("Extracted {} chars of Java context for {}", contextStr.length(), filename);
        return new ExtractionResult(ReviewMode.SMART_CONTEXT, contextStr,
            "Spring/JPA class with DI/annotations");
    }

    // === Python Context Extraction ===

    private ExtractionResult extractPythonContext(String filename, String content, String patch) {
        // For now, simple approach - could be enhanced later
        StringBuilder context = new StringBuilder();

        // Extract imports
        Pattern importPattern = Pattern.compile("^(import|from)\\s+.*$", Pattern.MULTILINE);
        List<String> imports = extractMatches(content, importPattern);

        if (!imports.isEmpty()) {
            context.append("# Imports:\n");
            for (String imp : imports) {
                context.append(imp).append("\n");
            }
        }

        // Extract class definitions
        Pattern classPattern = Pattern.compile("^class\\s+\\w+.*:$", Pattern.MULTILINE);
        List<String> classes = extractMatches(content, classPattern);
        if (!classes.isEmpty()) {
            context.append("\n# Classes:\n");
            for (String cls : classes) {
                context.append(cls).append("\n");
            }
        }

        String contextStr = context.toString().trim();
        if (contextStr.isEmpty()) {
            return new ExtractionResult(ReviewMode.DIFF_ONLY, null, "Simple Python file");
        }

        return new ExtractionResult(ReviewMode.SMART_CONTEXT, contextStr, "Python with imports/classes");
    }

    // === Helper Methods ===

    private boolean isJavaScript(String filename) {
        return filename.endsWith(".js") || filename.endsWith(".jsx") ||
               filename.endsWith(".ts") || filename.endsWith(".tsx") ||
               filename.endsWith(".mjs") || filename.endsWith(".vue") ||
               filename.endsWith(".svelte");
    }

    private boolean isJava(String filename) {
        return filename.endsWith(".java");
    }

    private boolean isPython(String filename) {
        return filename.endsWith(".py");
    }

    private boolean isSimpleJavaDto(String content) {
        // Check if class has Lombok annotations and mostly just fields
        boolean hasLombok = JAVA_SIMPLE_DTO.matcher(content).find();
        if (!hasLombok) return false;

        // Count methods vs fields - if mostly accessors or Lombok-generated, it's simple
        String[] lines = content.split("\n");
        int methodCount = 0;
        int fieldCount = 0;

        for (String line : lines) {
            if (line.trim().startsWith("private ") || line.trim().startsWith("protected ")) {
                if (line.contains("(")) methodCount++;
                else if (line.contains(";")) fieldCount++;
            }
            if (JAVA_ONLY_ACCESSORS.matcher(line).find()) {
                methodCount++; // But these are simple accessors
            }
        }

        // If mostly fields with Lombok, skip LLM
        return fieldCount > 0 && methodCount <= 2;
    }

    private List<String> extractMatches(String content, Pattern pattern) {
        List<String> matches = new ArrayList<>();
        Matcher matcher = pattern.matcher(content);
        while (matcher.find()) {
            matches.add(matcher.group().trim());
        }
        return matches;
    }

    private boolean isImportUsedInPatch(String importLine, String patch) {
        if (patch == null) return false;
        // Extract the imported name and check if it's used in patch
        // e.g., "import { useState } from 'react'" -> check for "useState"
        // e.g., "import OrderService" -> check for "OrderService"

        Pattern namePattern = Pattern.compile("\\b(\\w+)\\b");
        Matcher matcher = namePattern.matcher(importLine);
        while (matcher.find()) {
            String name = matcher.group(1);
            // Skip common keywords
            if (name.equals("import") || name.equals("from") || name.equals("as") ||
                name.equals("type") || name.equals("static") || name.length() < 3) {
                continue;
            }
            if (patch.contains(name)) {
                return true;
            }
        }
        return false;
    }

    private String extractClassHeader(String content) {
        String[] lines = content.split("\n");
        StringBuilder header = new StringBuilder();
        boolean inAnnotations = false;

        for (String line : lines) {
            String trimmed = line.trim();

            // Capture class-level annotations
            if (trimmed.startsWith("@") && !trimmed.contains("(") ||
                (trimmed.startsWith("@") && trimmed.endsWith(")"))) {
                inAnnotations = true;
                header.append(trimmed).append("\n");
            } else if (inAnnotations && trimmed.startsWith("@")) {
                header.append(trimmed).append("\n");
            } else if (JAVA_CLASS_DECLARATION.matcher(trimmed).find()) {
                header.append(trimmed);
                break;
            } else if (inAnnotations && !trimmed.isEmpty() && !trimmed.startsWith("@") &&
                       !trimmed.startsWith("//") && !trimmed.startsWith("/*") &&
                       !trimmed.startsWith("*") && !trimmed.startsWith("package") &&
                       !trimmed.startsWith("import")) {
                // Non-annotation line before class - reset
                if (!JAVA_CLASS_DECLARATION.matcher(trimmed).find()) {
                    inAnnotations = false;
                    header.setLength(0);
                }
            }
        }

        String result = header.toString().trim();
        return result.isEmpty() ? null : result;
    }

    private List<String> extractInjectedFields(String content) {
        List<String> fields = new ArrayList<>();
        String[] lines = content.split("\n");

        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if (trimmed.startsWith("@Autowired") || trimmed.startsWith("@Inject") ||
                trimmed.startsWith("@Resource") || trimmed.startsWith("@Value")) {
                StringBuilder field = new StringBuilder(trimmed);
                // Get the next line which should be the field declaration
                if (i + 1 < lines.length) {
                    String nextLine = lines[i + 1].trim();
                    if (nextLine.contains(";") && !nextLine.startsWith("@")) {
                        field.append("\n    ").append(nextLine);
                    }
                }
                fields.add(field.toString());
            }
        }

        return fields;
    }

    private String extractMethodAnnotations(String content, String patch) {
        if (patch == null) return "";

        StringBuilder annotations = new StringBuilder();
        String[] lines = content.split("\n");

        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if (JAVA_METHOD_ANNOTATION.matcher(trimmed).find()) {
                // Check if this method is in the patch
                if (i + 1 < lines.length) {
                    String methodLine = lines[i + 1].trim();
                    // Extract method name
                    Pattern methodName = Pattern.compile("\\s+(\\w+)\\s*\\(");
                    Matcher m = methodName.matcher(methodLine);
                    if (m.find() && patch.contains(m.group(1))) {
                        annotations.append(trimmed).append("\n");
                        annotations.append("    ").append(methodLine).append("\n");
                    }
                }
            }
        }

        return annotations.toString();
    }

    private String extractTypeDefinitions(String content, String patch) {
        if (patch == null) return "";

        StringBuilder types = new StringBuilder();
        Pattern typePattern = Pattern.compile(
            "^(export\\s+)?(interface|type)\\s+(\\w+).*?(?=^(export\\s+)?(interface|type|class|function|const)|\\Z)",
            Pattern.MULTILINE | Pattern.DOTALL);

        Matcher matcher = typePattern.matcher(content);
        while (matcher.find()) {
            String typeName = matcher.group(3);
            // Only include if type is used in patch
            if (patch.contains(typeName)) {
                String typeDef = matcher.group().trim();
                // Limit size
                if (typeDef.length() > 500) {
                    typeDef = typeDef.substring(0, 500) + "\n  // ... truncated";
                }
                types.append(typeDef).append("\n\n");
            }
        }

        return types.toString();
    }
}
