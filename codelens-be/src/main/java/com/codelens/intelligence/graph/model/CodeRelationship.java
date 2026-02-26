package com.codelens.intelligence.graph.model;

public record CodeRelationship(
    String sourceId,
    String targetId,
    RelationshipType type,
    String filePath,
    int line,
    String metadata
) {}
