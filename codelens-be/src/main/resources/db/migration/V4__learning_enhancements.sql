-- Add helpful_count to repo_learning for balanced feedback tracking
-- IF NOT EXISTS makes this idempotent for databases created by Hibernate ddl-auto.
ALTER TABLE repo_learning ADD COLUMN IF NOT EXISTS helpful_count INT NOT NULL DEFAULT 0;

-- Add metadata columns to repo_prompt_hints for auto-learned hints
ALTER TABLE repo_prompt_hints ADD COLUMN IF NOT EXISTS confidence DOUBLE DEFAULT 0.0;
ALTER TABLE repo_prompt_hints ADD COLUMN IF NOT EXISTS generated_from_rule VARCHAR(255);
ALTER TABLE repo_prompt_hints ADD COLUMN IF NOT EXISTS feedback_count INT DEFAULT 0;
