-- Add helpful_count to repo_learning for balanced feedback tracking
ALTER TABLE repo_learning ADD COLUMN helpful_count INT NOT NULL DEFAULT 0;

-- Add metadata columns to repo_prompt_hints for auto-learned hints
ALTER TABLE repo_prompt_hints ADD COLUMN confidence DOUBLE DEFAULT 0.0;
ALTER TABLE repo_prompt_hints ADD COLUMN generated_from_rule VARCHAR(255);
ALTER TABLE repo_prompt_hints ADD COLUMN feedback_count INT DEFAULT 0;
