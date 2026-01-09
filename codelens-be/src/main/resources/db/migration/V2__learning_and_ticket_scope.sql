-- V2: Learning and Ticket Scope Features

-- =====================================================
-- Feature 1: Ticket Scope Review
-- =====================================================

-- Add ticket scope fields to reviews table
ALTER TABLE reviews
    ADD COLUMN ticket_content TEXT AFTER pr_description,
    ADD COLUMN ticket_id VARCHAR(100) AFTER ticket_content,
    ADD COLUMN ticket_scope_result TEXT AFTER ticket_id,
    ADD COLUMN ticket_scope_aligned BOOLEAN AFTER ticket_scope_result;

-- =====================================================
-- Feature 2: Continuous Learning
-- =====================================================

-- Add feedback fields to review_issues table
ALTER TABLE review_issues
    ADD COLUMN is_helpful BOOLEAN AFTER suggested_fix,
    ADD COLUMN is_false_positive BOOLEAN AFTER is_helpful,
    ADD COLUMN feedback_note TEXT AFTER is_false_positive,
    ADD COLUMN feedback_at TIMESTAMP NULL AFTER feedback_note,
    ADD COLUMN feedback_by BINARY(16) AFTER feedback_at;

-- Add foreign key for feedback_by
ALTER TABLE review_issues
    ADD CONSTRAINT fk_issues_feedback_user FOREIGN KEY (feedback_by) REFERENCES users(id) ON DELETE SET NULL;

-- Create index for feedback queries
CREATE INDEX idx_issues_feedback ON review_issues(is_false_positive, is_helpful);

-- Repository Learning table - tracks learned suppressions per repo
CREATE TABLE IF NOT EXISTS repo_learning (
    id BINARY(16) PRIMARY KEY,
    repository_id BINARY(16) NOT NULL,
    rule_id VARCHAR(255) NOT NULL,
    analyzer VARCHAR(50) NOT NULL,
    category VARCHAR(50),
    false_positive_count INT NOT NULL DEFAULT 0,
    not_helpful_count INT NOT NULL DEFAULT 0,
    auto_suppressed BOOLEAN NOT NULL DEFAULT FALSE,
    severity_override VARCHAR(20),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_learning_repo FOREIGN KEY (repository_id) REFERENCES repositories(id) ON DELETE CASCADE,
    UNIQUE KEY uk_repo_rule (repository_id, rule_id, analyzer)
);

CREATE INDEX idx_learning_repo ON repo_learning(repository_id);
CREATE INDEX idx_learning_suppressed ON repo_learning(repository_id, auto_suppressed);

-- Repository Prompt Hints table - custom hints for LLM prompts per repo
CREATE TABLE IF NOT EXISTS repo_prompt_hints (
    id BINARY(16) PRIMARY KEY,
    repository_id BINARY(16) NOT NULL,
    hint TEXT NOT NULL,
    source ENUM('USER_ADDED', 'AUTO_LEARNED') NOT NULL DEFAULT 'USER_ADDED',
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_hints_repo FOREIGN KEY (repository_id) REFERENCES repositories(id) ON DELETE CASCADE
);

CREATE INDEX idx_hints_repo ON repo_prompt_hints(repository_id, active);
