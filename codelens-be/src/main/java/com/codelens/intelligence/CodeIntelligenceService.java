package com.codelens.intelligence;

import com.codelens.intelligence.graph.*;
import com.codelens.intelligence.graph.model.*;
import com.codelens.intelligence.model.GraphContext;
import com.codelens.git.GitProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class CodeIntelligenceService {

    private static final Logger log = LoggerFactory.getLogger(CodeIntelligenceService.class);

    private final CodeGraphManager graphManager;
    private final CodeGraphQueryService queryService;
    private final GraphUpdateService updateService;

    public CodeIntelligenceService(CodeGraphManager graphManager,
                                   CodeGraphQueryService queryService,
                                   GraphUpdateService updateService) {
        this.graphManager = graphManager;
        this.queryService = queryService;
        this.updateService = updateService;
    }

    public void initializeGraph(UUID repoId) {
        log.info("Initializing code graph for repo {}", repoId);
        graphManager.initGraph(repoId);
    }

    public void updateGraphForPR(UUID repoId, GitProvider gitProvider, String owner, String repo,
                                 List<GitProvider.ChangedFile> changedFiles, String commitSha) {
        if (!graphManager.graphExists(repoId)) {
            graphManager.initGraph(repoId);
        }

        for (var file : changedFiles) {
            String language = detectLanguage(file.filename());
            if (language == null) continue;

            if ("deleted".equals(file.status()) || "removed".equals(file.status())) {
                updateService.removeFile(repoId, file.filename());
            } else {
                try {
                    String content = gitProvider.getFileContent(owner, repo, file.filename(), commitSha);
                    if (content != null) {
                        updateService.updateFile(repoId, file.filename(), content, language);
                    }
                } catch (Exception e) {
                    log.warn("Failed to update graph for {}: {}", file.filename(), e.getMessage());
                }
            }
        }
    }

    public GraphContext enrichFile(UUID repoId, String filePath) {
        if (!graphManager.graphExists(repoId)) {
            return emptyContext();
        }

        List<CodeEntity> fileEntities = graphManager.getEntitiesByFile(repoId, filePath);
        if (fileEntities.isEmpty()) {
            return emptyContext();
        }

        List<String> entityIds = fileEntities.stream().map(CodeEntity::id).toList();
        return queryService.buildGraphContext(repoId, entityIds);
    }

    /**
     * Per-section entry cap for the prompt block.
     *
     * <p>The graph query applies no limit, so a change to a widely-called utility can
     * return hundreds of callers and emit an unbounded prompt section. That is the one
     * uncapped input in the review prompt: rules, learned hints and the change manifest
     * are all bounded. Truncating per section keeps every relationship type represented
     * rather than letting callers crowd out test coverage and API impact.
     */
    static final int MAX_ENTRIES_PER_SECTION = 25;

    /** Renders one section, disclosing anything it had to leave out. */
    private static <T> void appendSection(StringBuilder sb, String heading, List<T> entries,
            java.util.function.BiConsumer<StringBuilder, T> renderer) {
        if (entries.isEmpty()) {
            return;
        }
        sb.append(heading).append("\n");
        int shown = Math.min(entries.size(), MAX_ENTRIES_PER_SECTION);
        for (int i = 0; i < shown; i++) {
            renderer.accept(sb, entries.get(i));
        }
        int omitted = entries.size() - shown;
        if (omitted > 0) {
            sb.append("- ...and ").append(omitted).append(" more (not shown)\n");
        }
        sb.append("\n");
    }

    public String formatForPrompt(GraphContext context) {
        if (context.isEmpty()) return "";

        var sb = new StringBuilder();
        sb.append("\n## Codebase Context (structural analysis)\n\n");

        appendSection(sb, "### Callers of changed functions:", context.callers(), (b, c) -> {
            b.append("- ").append(c.name()).append("() in ").append(c.filePath())
             .append(":").append(c.line()).append("\n");
            if (c.signature() != null) {
                b.append("  Signature: ").append(c.signature()).append("\n");
            }
        });

        if (!context.tests().isEmpty()) {
            appendSection(sb, "### Test coverage:", context.tests(), (b, t) ->
                b.append("- ").append(t.functionName()).append("(): Tested by ")
                 .append(t.testName()).append(" in ").append(t.testFile()).append("\n"));
        } else {
            sb.append("### Test coverage:\n- WARNING: No tests found for changed functions\n\n");
        }

        appendSection(sb, "### API impact:", context.endpoints(), (b, e) ->
            b.append("- ").append(e.method()).append(" ").append(e.path())
             .append(" -> exposed by ").append(e.functionName()).append("()\n"));

        appendSection(sb, "### Interface implementors:", context.implementors(), (b, i) ->
            b.append("- ").append(i.className()).append(" implements ").append(i.interfaceName())
             .append(" in ").append(i.filePath()).append(" - may need updating\n"));

        appendSection(sb, "### Dependency injection:", context.injectors(), (b, i) ->
            b.append("- ").append(i.className()).append(" depends on ").append(i.dependencyName())
             .append(" in ").append(i.filePath()).append("\n"));

        sb.append("""
            Use this context to:
            1. Flag if callers don't handle new error cases or changed return types
            2. Flag if changes affect public API contracts
            3. Flag if critical changes lack test coverage
            4. Flag if interface changes require implementor updates
            5. Flag if DI dependencies could cause circular references
            """);

        return sb.toString();
    }

    public boolean isGraphReady(UUID repoId) {
        return graphManager.graphExists(repoId);
    }

    private GraphContext emptyContext() {
        return new GraphContext(List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private String detectLanguage(String filename) {
        if (filename.endsWith(".java")) return "java";
        if (filename.endsWith(".js") || filename.endsWith(".jsx") || filename.endsWith(".mjs")) return "javascript";
        if (filename.endsWith(".ts") || filename.endsWith(".tsx")) return "typescript";
        if (filename.endsWith(".py")) return "python";
        return null;
    }
}
