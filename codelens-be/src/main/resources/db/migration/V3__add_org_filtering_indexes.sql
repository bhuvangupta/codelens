-- Migration: Add indexes to optimize organization-scoped queries
-- These indexes support the multi-tenant org filtering added for domain-based OAuth
-- A stored procedure is used because older MySQL 8.0 versions do not support
-- "CREATE INDEX IF NOT EXISTS" or "DROP INDEX IF EXISTS".

DROP PROCEDURE IF EXISTS CreateIndexIfNotExists;

DELIMITER $$

CREATE PROCEDURE CreateIndexIfNotExists(
    IN tableName VARCHAR(64),
    IN indexName VARCHAR(64),
    IN columnList VARCHAR(255)
)
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = tableName
          AND index_name = indexName
    ) THEN
        SET @sql = CONCAT('CREATE INDEX ', indexName, ' ON ', tableName, ' (', columnList, ')');
        PREPARE stmt FROM @sql;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END$$

DELIMITER ;

-- ============================================================
-- CRITICAL: User organization lookups
-- ============================================================
CALL CreateIndexIfNotExists('users', 'idx_users_organization_id', 'organization_id');

-- ============================================================
-- CRITICAL: Review queries with user/org filtering
-- ============================================================
CALL CreateIndexIfNotExists('reviews', 'idx_reviews_user_created', 'user_id, created_at');
CALL CreateIndexIfNotExists('reviews', 'idx_reviews_status_created', 'status, created_at');
CALL CreateIndexIfNotExists('reviews', 'idx_reviews_repository_created', 'repository_id, created_at');
CALL CreateIndexIfNotExists('reviews', 'idx_reviews_created_date', 'created_at');

-- ============================================================
-- CRITICAL: Review issues queries
-- ============================================================
CALL CreateIndexIfNotExists('review_issues', 'idx_issues_review_severity', 'review_id, severity');
CALL CreateIndexIfNotExists('review_issues', 'idx_issues_review_category', 'review_id, category');
CALL CreateIndexIfNotExists('review_issues', 'idx_issues_created', 'created_at');

-- ============================================================
-- HIGH: LLM usage analytics
-- ============================================================
CALL CreateIndexIfNotExists('llm_usage', 'idx_llm_org_created', 'organization_id, created_at');

-- ============================================================
-- MEDIUM: Additional composite indexes for common query patterns
-- ============================================================
CALL CreateIndexIfNotExists('reviews', 'idx_reviews_user_repo_name', 'user_id, repository_name(100)');
CALL CreateIndexIfNotExists('review_issues', 'idx_issues_created_category', 'created_at, category');

DROP PROCEDURE CreateIndexIfNotExists;
