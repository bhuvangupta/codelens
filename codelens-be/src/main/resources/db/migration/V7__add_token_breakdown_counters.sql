-- Record the token counters that make the cost and caching levers measurable.
--
-- cached_tokens   : input tokens served from a prompt cache. The review prompts were
--                   reordered so that PR-invariant content forms a long shared prefix;
--                   without this counter there is no way to tell whether that produced
--                   cache hits or changed nothing.
-- thinking_tokens : reasoning tokens. These bill as output, so a reasoning tier's real
--                   cost is invisible unless recorded separately.
--
-- Stored procedure is used because MySQL does not support
-- "ALTER TABLE ... ADD COLUMN IF NOT EXISTS".

DROP PROCEDURE IF EXISTS AddColumnIfNotExists;

DELIMITER $$

CREATE PROCEDURE AddColumnIfNotExists(
    IN tableName VARCHAR(64),
    IN columnName VARCHAR(64),
    IN columnDef VARCHAR(255)
)
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = tableName
          AND column_name = columnName
    ) THEN
        SET @sql = CONCAT('ALTER TABLE ', tableName, ' ADD COLUMN ', columnName, ' ', columnDef);
        PREPARE stmt FROM @sql;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END$$

DELIMITER ;

CALL AddColumnIfNotExists('reviews', 'cached_tokens', 'INT NULL');
CALL AddColumnIfNotExists('reviews', 'thinking_tokens', 'INT NULL');
CALL AddColumnIfNotExists('llm_usage', 'cached_tokens', 'INT NULL');
CALL AddColumnIfNotExists('llm_usage', 'thinking_tokens', 'INT NULL');

DROP PROCEDURE AddColumnIfNotExists;
