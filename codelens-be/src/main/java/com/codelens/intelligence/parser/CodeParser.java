package com.codelens.intelligence.parser;

import com.codelens.intelligence.graph.model.CodeEntity;
import com.codelens.intelligence.graph.model.CodeRelationship;

import java.util.List;

public interface CodeParser {

    record ParseResult(List<CodeEntity> entities, List<CodeRelationship> relationships) {}

    ParseResult parse(String filePath, String sourceCode);
}
