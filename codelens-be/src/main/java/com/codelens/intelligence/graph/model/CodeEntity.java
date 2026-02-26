package com.codelens.intelligence.graph.model;

public record CodeEntity(
    String id,
    String name,
    EntityType type,
    String filePath,
    int lineStart,
    int lineEnd,
    String language,
    String signature,
    String annotations,
    String metadata
) {
    public static String buildId(String filePath, String name) {
        return filePath + ":" + name;
    }
}
