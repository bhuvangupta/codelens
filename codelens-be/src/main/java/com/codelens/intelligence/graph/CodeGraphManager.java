package com.codelens.intelligence.graph;

import com.codelens.intelligence.graph.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class CodeGraphManager {

    private static final Logger log = LoggerFactory.getLogger(CodeGraphManager.class);

    private final String storagePath;
    private final Map<UUID, Connection> connections = new ConcurrentHashMap<>();

    public CodeGraphManager(@Value("${codelens.intelligence.graph-storage-path:./data/graphs}") String storagePath) {
        this.storagePath = storagePath;
        new java.io.File(storagePath).mkdirs();
    }

    public void initGraph(UUID repoId) {
        var conn = getConnection(repoId);
        try (var stmt = conn.createStatement()) {
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS entities (
                    id TEXT PRIMARY KEY,
                    name TEXT NOT NULL,
                    type TEXT NOT NULL,
                    file_path TEXT NOT NULL,
                    line_start INTEGER,
                    line_end INTEGER,
                    language TEXT NOT NULL,
                    signature TEXT,
                    annotations TEXT,
                    metadata TEXT,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
            """);
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS relationships (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    source_id TEXT NOT NULL,
                    target_id TEXT NOT NULL,
                    type TEXT NOT NULL,
                    file_path TEXT,
                    line INTEGER,
                    metadata TEXT,
                    UNIQUE(source_id, target_id, type)
                )
            """);
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_entities_file ON entities(file_path)");
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_entities_type ON entities(type)");
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_entities_name ON entities(name)");
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_rel_source ON relationships(source_id)");
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_rel_target ON relationships(target_id)");
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_rel_type ON relationships(type)");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to init graph for repo " + repoId, e);
        }
    }

    public void insertEntities(UUID repoId, List<CodeEntity> entities) {
        var conn = getConnection(repoId);
        var sql = "INSERT OR REPLACE INTO entities (id, name, type, file_path, line_start, line_end, language, signature, annotations, metadata) VALUES (?,?,?,?,?,?,?,?,?,?)";
        try (var ps = conn.prepareStatement(sql)) {
            for (var e : entities) {
                ps.setString(1, e.id());
                ps.setString(2, e.name());
                ps.setString(3, e.type().name());
                ps.setString(4, e.filePath());
                ps.setInt(5, e.lineStart());
                ps.setInt(6, e.lineEnd());
                ps.setString(7, e.language());
                ps.setString(8, e.signature());
                ps.setString(9, e.annotations());
                ps.setString(10, e.metadata());
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to insert entities", e);
        }
    }

    public void insertRelationships(UUID repoId, List<CodeRelationship> relationships) {
        var conn = getConnection(repoId);
        var sql = "INSERT OR IGNORE INTO relationships (source_id, target_id, type, file_path, line, metadata) VALUES (?,?,?,?,?,?)";
        try (var ps = conn.prepareStatement(sql)) {
            for (var r : relationships) {
                ps.setString(1, r.sourceId());
                ps.setString(2, r.targetId());
                ps.setString(3, r.type().name());
                ps.setString(4, r.filePath());
                ps.setInt(5, r.line());
                ps.setString(6, r.metadata());
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to insert relationships", e);
        }
    }

    public List<CodeEntity> getEntitiesByFile(UUID repoId, String filePath) {
        var conn = getConnection(repoId);
        try (var ps = conn.prepareStatement("SELECT * FROM entities WHERE file_path = ?")) {
            ps.setString(1, filePath);
            return mapEntities(ps.executeQuery());
        } catch (SQLException e) {
            throw new RuntimeException("Query failed", e);
        }
    }

    public List<CodeEntity> getCallers(UUID repoId, String entityId) {
        var conn = getConnection(repoId);
        try (var ps = conn.prepareStatement("""
            SELECT e.* FROM entities e
            JOIN relationships r ON r.source_id = e.id
            WHERE r.target_id = ? AND r.type = 'CALLS'
        """)) {
            ps.setString(1, entityId);
            return mapEntities(ps.executeQuery());
        } catch (SQLException e) {
            throw new RuntimeException("Query failed", e);
        }
    }

    public List<CodeEntity> getTransitiveCallers(UUID repoId, String entityId) {
        var conn = getConnection(repoId);
        try (var ps = conn.prepareStatement("""
            WITH RECURSIVE impact AS (
                SELECT source_id FROM relationships WHERE target_id = ? AND type = 'CALLS'
                UNION
                SELECT r.source_id FROM relationships r
                JOIN impact i ON r.target_id = i.source_id WHERE r.type = 'CALLS'
            )
            SELECT e.* FROM entities e WHERE e.id IN (SELECT source_id FROM impact)
        """)) {
            ps.setString(1, entityId);
            return mapEntities(ps.executeQuery());
        } catch (SQLException e) {
            throw new RuntimeException("Query failed", e);
        }
    }

    public List<CodeEntity> getTests(UUID repoId, String entityId) {
        var conn = getConnection(repoId);
        try (var ps = conn.prepareStatement("""
            SELECT e.* FROM entities e
            JOIN relationships r ON r.source_id = e.id
            WHERE r.target_id = ? AND r.type = 'TESTS'
        """)) {
            ps.setString(1, entityId);
            return mapEntities(ps.executeQuery());
        } catch (SQLException e) {
            throw new RuntimeException("Query failed", e);
        }
    }

    public List<String> getEndpoints(UUID repoId, String entityId) {
        var conn = getConnection(repoId);
        try (var ps = conn.prepareStatement("""
            SELECT r.metadata FROM relationships r
            WHERE r.source_id = ? AND r.type = 'HTTP_ENDPOINT'
        """)) {
            ps.setString(1, entityId);
            var rs = ps.executeQuery();
            List<String> results = new ArrayList<>();
            while (rs.next()) results.add(rs.getString("metadata"));
            return results;
        } catch (SQLException e) {
            throw new RuntimeException("Query failed", e);
        }
    }

    public List<CodeEntity> getImplementors(UUID repoId, String interfaceId) {
        var conn = getConnection(repoId);
        try (var ps = conn.prepareStatement("""
            SELECT e.* FROM entities e
            JOIN relationships r ON r.source_id = e.id
            WHERE r.target_id = ? AND r.type = 'IMPLEMENTS'
        """)) {
            ps.setString(1, interfaceId);
            return mapEntities(ps.executeQuery());
        } catch (SQLException e) {
            throw new RuntimeException("Query failed", e);
        }
    }

    public List<CodeEntity> getInjectors(UUID repoId, String serviceId) {
        var conn = getConnection(repoId);
        try (var ps = conn.prepareStatement("""
            SELECT e.* FROM entities e
            JOIN relationships r ON r.source_id = e.id
            WHERE r.target_id = ? AND r.type = 'INJECTS'
        """)) {
            ps.setString(1, serviceId);
            return mapEntities(ps.executeQuery());
        } catch (SQLException e) {
            throw new RuntimeException("Query failed", e);
        }
    }

    public void deleteEntitiesByFile(UUID repoId, String filePath) {
        var conn = getConnection(repoId);
        try {
            try (var ps = conn.prepareStatement("""
                DELETE FROM relationships WHERE source_id IN (SELECT id FROM entities WHERE file_path = ?)
                   OR target_id IN (SELECT id FROM entities WHERE file_path = ?)
            """)) {
                ps.setString(1, filePath);
                ps.setString(2, filePath);
                ps.executeUpdate();
            }
            try (var ps = conn.prepareStatement("DELETE FROM entities WHERE file_path = ?")) {
                ps.setString(1, filePath);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete entities for file " + filePath, e);
        }
    }

    public boolean graphExists(UUID repoId) {
        return java.nio.file.Files.exists(java.nio.file.Path.of(storagePath, repoId + ".db"));
    }

    public void close() {
        connections.values().forEach(c -> {
            try { c.close(); } catch (SQLException ignored) {}
        });
        connections.clear();
    }

    private Connection getConnection(UUID repoId) {
        return connections.computeIfAbsent(repoId, id -> {
            try {
                var url = "jdbc:sqlite:" + storagePath + "/" + id + ".db";
                var conn = DriverManager.getConnection(url);
                conn.createStatement().execute("PRAGMA journal_mode=WAL");
                conn.createStatement().execute("PRAGMA foreign_keys=ON");
                return conn;
            } catch (SQLException e) {
                throw new RuntimeException("Failed to connect to graph DB for " + id, e);
            }
        });
    }

    private List<CodeEntity> mapEntities(ResultSet rs) throws SQLException {
        List<CodeEntity> results = new ArrayList<>();
        while (rs.next()) {
            results.add(new CodeEntity(
                rs.getString("id"),
                rs.getString("name"),
                EntityType.valueOf(rs.getString("type")),
                rs.getString("file_path"),
                rs.getInt("line_start"),
                rs.getInt("line_end"),
                rs.getString("language"),
                rs.getString("signature"),
                rs.getString("annotations"),
                rs.getString("metadata")
            ));
        }
        return results;
    }
}
