-- Add helpful_count to repo_learning for balanced feedback tracking
-- Add metadata columns to repo_prompt_hints for auto-learned hints
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

CALL AddColumnIfNotExists('repo_learning', 'helpful_count', 'INT NOT NULL DEFAULT 0');
CALL AddColumnIfNotExists('repo_prompt_hints', 'confidence', 'DOUBLE DEFAULT 0.0');
CALL AddColumnIfNotExists('repo_prompt_hints', 'generated_from_rule', 'VARCHAR(255)');
CALL AddColumnIfNotExists('repo_prompt_hints', 'feedback_count', 'INT DEFAULT 0');

DROP PROCEDURE AddColumnIfNotExists;
