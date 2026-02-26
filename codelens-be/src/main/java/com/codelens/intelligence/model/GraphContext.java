package com.codelens.intelligence.model;

import java.util.List;

public record GraphContext(
    List<CallerInfo> callers,
    List<TestInfo> tests,
    List<EndpointInfo> endpoints,
    List<ImplementorInfo> implementors,
    List<InjectorInfo> injectors
) {
    public record CallerInfo(String name, String filePath, int line, String signature) {}
    public record TestInfo(String functionName, String testName, String testFile) {}
    public record EndpointInfo(String method, String path, String functionName) {}
    public record ImplementorInfo(String className, String interfaceName, String filePath) {}
    public record InjectorInfo(String className, String dependencyName, String filePath) {}

    public boolean isEmpty() {
        return callers.isEmpty() && tests.isEmpty() && endpoints.isEmpty()
            && implementors.isEmpty() && injectors.isEmpty();
    }

    public int estimateTokens() {
        return callers.size() * 30 + tests.size() * 20 + endpoints.size() * 25
            + implementors.size() * 25 + injectors.size() * 25;
    }
}
