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

    public String formatForPrompt(GraphContext context) {
        if (context.isEmpty()) return "";

        var sb = new StringBuilder();
        sb.append("\n## Codebase Context (structural analysis)\n\n");

        if (!context.callers().isEmpty()) {
            sb.append("### Callers of changed functions:\n");
            for (var c : context.callers()) {
                sb.append("- ").append(c.name()).append("() in ").append(c.filePath())
                  .append(":").append(c.line()).append("\n");
                if (c.signature() != null) {
                    sb.append("  Signature: ").append(c.signature()).append("\n");
                }
            }
            sb.append("\n");
        }

        if (!context.tests().isEmpty()) {
            sb.append("### Test coverage:\n");
            for (var t : context.tests()) {
                sb.append("- ").append(t.functionName()).append("(): Tested by ")
                  .append(t.testName()).append(" in ").append(t.testFile()).append("\n");
            }
            sb.append("\n");
        } else {
            sb.append("### Test coverage:\n- WARNING: No tests found for changed functions\n\n");
        }

        if (!context.endpoints().isEmpty()) {
            sb.append("### API impact:\n");
            for (var e : context.endpoints()) {
                sb.append("- ").append(e.method()).append(" ").append(e.path())
                  .append(" -> exposed by ").append(e.functionName()).append("()\n");
            }
            sb.append("\n");
        }

        if (!context.implementors().isEmpty()) {
            sb.append("### Interface implementors:\n");
            for (var i : context.implementors()) {
                sb.append("- ").append(i.className()).append(" implements ").append(i.interfaceName())
                  .append(" in ").append(i.filePath()).append(" - may need updating\n");
            }
            sb.append("\n");
        }

        if (!context.injectors().isEmpty()) {
            sb.append("### Dependency injection:\n");
            for (var i : context.injectors()) {
                sb.append("- ").append(i.className()).append(" depends on ").append(i.dependencyName())
                  .append(" in ").append(i.filePath()).append("\n");
            }
            sb.append("\n");
        }

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
        return null;
    }
}
