-- Add confidence columns to review_issues and review_comments
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

CALL AddColumnIfNotExists('review_issues', 'confidence', 'VARCHAR(10)');
CALL AddColumnIfNotExists('review_comments', 'confidence', 'VARCHAR(10)');

DROP PROCEDURE AddColumnIfNotExists;
