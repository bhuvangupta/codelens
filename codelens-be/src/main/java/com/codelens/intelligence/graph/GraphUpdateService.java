package com.codelens.intelligence.graph;

import com.codelens.intelligence.parser.JavaCodeParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class GraphUpdateService {

    private static final Logger log = LoggerFactory.getLogger(GraphUpdateService.class);

    private final CodeGraphManager graphManager;
    private final JavaCodeParser javaParser;

    public GraphUpdateService(CodeGraphManager graphManager, JavaCodeParser javaParser) {
        this.graphManager = graphManager;
        this.javaParser = javaParser;
    }

    public void updateFile(UUID repoId, String filePath, String sourceCode, String language) {
        log.debug("Updating graph for file: {} ({})", filePath, language);
        graphManager.deleteEntitiesByFile(repoId, filePath);

        if ("java".equalsIgnoreCase(language)) {
            var result = javaParser.parse(filePath, sourceCode);
            graphManager.insertEntities(repoId, result.entities());
            graphManager.insertRelationships(repoId, result.relationships());
            log.debug("Indexed {} entities, {} relationships for {}", result.entities().size(), result.relationships().size(), filePath);
        } else {
            log.debug("Skipping unsupported language: {} for file: {}", language, filePath);
        }
    }

    public void removeFile(UUID repoId, String filePath) {
        log.debug("Removing file from graph: {}", filePath);
        graphManager.deleteEntitiesByFile(repoId, filePath);
    }
}
