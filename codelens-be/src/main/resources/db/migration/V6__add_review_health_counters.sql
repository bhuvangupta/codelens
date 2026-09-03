-- Add review health counters to the reviews table.
--
-- Before this, a file whose LLM response was missing, truncated or unparseable
-- contributed zero findings and was indistinguishable from a genuinely clean file.
-- Files dropped by the max-files cap were logged at WARN and never recorded, so a
-- partially reviewed PR still reported as fully reviewed.
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

CALL AddColumnIfNotExists('reviews', 'files_failed_count', 'INT NULL');
CALL AddColumnIfNotExists('reviews', 'files_skipped_count', 'INT NULL');

DROP PROCEDURE AddColumnIfNotExists;
