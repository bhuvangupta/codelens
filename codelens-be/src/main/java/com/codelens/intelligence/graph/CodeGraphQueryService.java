package com.codelens.intelligence.graph;

import com.codelens.intelligence.graph.model.CodeEntity;
import com.codelens.intelligence.model.GraphContext;
import com.codelens.intelligence.model.GraphContext.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class CodeGraphQueryService {

    private static final Logger log = LoggerFactory.getLogger(CodeGraphQueryService.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final CodeGraphManager graphManager;

    public CodeGraphQueryService(CodeGraphManager graphManager) {
        this.graphManager = graphManager;
    }

    public GraphContext buildGraphContext(UUID repoId, List<String> changedEntityIds) {
        List<CallerInfo> callers = new ArrayList<>();
        List<TestInfo> tests = new ArrayList<>();
        List<EndpointInfo> endpoints = new ArrayList<>();
        List<ImplementorInfo> implementors = new ArrayList<>();
        List<InjectorInfo> injectors = new ArrayList<>();

        for (String entityId : changedEntityIds) {
            String entityName = entityId.contains(":") ? entityId.substring(entityId.lastIndexOf(':') + 1) : entityId;

            for (CodeEntity caller : graphManager.getCallers(repoId, entityId)) {
                callers.add(new CallerInfo(caller.name(), caller.filePath(), caller.lineStart(), caller.signature()));
            }

            for (CodeEntity test : graphManager.getTests(repoId, entityId)) {
                tests.add(new TestInfo(entityName, test.name(), test.filePath()));
            }

            for (String endpointMeta : graphManager.getEndpoints(repoId, entityId)) {
                try {
                    JsonNode node = mapper.readTree(endpointMeta);
                    endpoints.add(new EndpointInfo(
                        node.path("method").asText(""), node.path("path").asText(""), entityName
                    ));
                } catch (Exception e) {
                    log.warn("Failed to parse endpoint metadata: {}", endpointMeta);
                }
            }

            for (CodeEntity impl : graphManager.getImplementors(repoId, entityId)) {
                implementors.add(new ImplementorInfo(impl.name(), entityName, impl.filePath()));
            }

            for (CodeEntity injector : graphManager.getInjectors(repoId, entityId)) {
                injectors.add(new InjectorInfo(injector.name(), entityName, injector.filePath()));
            }
        }

        return new GraphContext(callers, tests, endpoints, implementors, injectors);
    }
}
