-- =============================================================================
-- CodeLens Database Setup Script
-- =============================================================================
-- This script creates the entire database schema from scratch.
-- Run this for fresh installations or to reset the database.
--
-- Usage:
--   mysql -u root -p < scripts/create_database.sql
--   OR
--   mysql -u root -p -e "source /path/to/create_database.sql"
--
-- Note: This script will DROP existing tables! Use with caution.
-- =============================================================================

-- Create database if not exists
CREATE DATABASE IF NOT EXISTS codelens CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE codelens;

-- =============================================================================
-- Drop existing tables (in reverse dependency order)
-- =============================================================================
SET FOREIGN_KEY_CHECKS = 0;

DROP TABLE IF EXISTS review_file_diffs;
DROP TABLE IF EXISTS webhook_deliveries;
DROP TABLE IF EXISTS webhook_configs;
DROP TABLE IF EXISTS notification_preferences;
DROP TABLE IF EXISTS notifications;
DROP TABLE IF EXISTS review_rules;
DROP TABLE IF EXISTS membership_requests;
DROP TABLE IF EXISTS llm_usage;
DROP TABLE IF EXISTS review_comments;
DROP TABLE IF EXISTS review_issues;
DROP TABLE IF EXISTS reviews;
DROP TABLE IF EXISTS repositories;
DROP TABLE IF EXISTS users;
DROP TABLE IF EXISTS organizations;
DROP TABLE IF EXISTS flyway_schema_history;

SET FOREIGN_KEY_CHECKS = 1;

-- =============================================================================
-- Organizations
-- =============================================================================
CREATE TABLE organizations (
    id BINARY(16) PRIMARY KEY,
    name VARCHAR(255) NOT NULL UNIQUE,
    slug VARCHAR(255) UNIQUE,
    description TEXT,
    logo_url VARCHAR(500),
    default_llm_provider VARCHAR(50),
    llm_config_json TEXT,
    auto_review_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    post_comments_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    post_inline_comments_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    security_scan_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    static_analysis_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    auto_approve_members BOOLEAN NOT NULL DEFAULT FALSE,
    github_token TEXT,
    gitlab_token TEXT,
    gitlab_url VARCHAR(500),
    monthly_budget DOUBLE,
    current_month_spend DOUBLE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- =============================================================================
-- Users
-- =============================================================================
CREATE TABLE users (
    id BINARY(16) PRIMARY KEY,
    email VARCHAR(255) NOT NULL UNIQUE,
    name VARCHAR(255) NOT NULL,
    avatar_url VARCHAR(500),
    provider VARCHAR(50) NOT NULL,
    provider_id VARCHAR(255) NOT NULL,
    role ENUM('ADMIN', 'MEMBER', 'VIEWER') NOT NULL DEFAULT 'MEMBER',
    organization_id BINARY(16),
    github_username VARCHAR(100),
    gitlab_username VARCHAR(100),
    default_llm_provider VARCHAR(50),
    last_login_at TIMESTAMP NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_users_org FOREIGN KEY (organization_id) REFERENCES organizations(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_users_email ON users(email);
CREATE INDEX idx_users_provider ON users(provider, provider_id);

-- =============================================================================
-- Repositories
-- =============================================================================
CREATE TABLE repositories (
    id BINARY(16) PRIMARY KEY,
    full_name VARCHAR(255) NOT NULL,
    name VARCHAR(255) NOT NULL,
    owner VARCHAR(255) NOT NULL,
    provider ENUM('GITHUB', 'GITLAB') NOT NULL,
    provider_repo_id VARCHAR(255),
    organization_id BINARY(16),
    auto_review_enabled BOOLEAN DEFAULT TRUE,
    language VARCHAR(50),
    description TEXT,
    default_branch VARCHAR(100),
    is_private BOOLEAN,
    webhook_id VARCHAR(255),
    webhook_secret VARCHAR(255),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_repos_org FOREIGN KEY (organization_id) REFERENCES organizations(id) ON DELETE CASCADE,
    UNIQUE KEY uk_fullname_provider (full_name, provider)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_repos_org ON repositories(organization_id);

-- =============================================================================
-- Reviews (includes cancellation and diff storage fields)
-- =============================================================================
CREATE TABLE reviews (
    id BINARY(16) PRIMARY KEY,
    pr_number INT NOT NULL,
    pr_title VARCHAR(500) NOT NULL,
    pr_description TEXT,
    pr_url VARCHAR(500),
    commit_url VARCHAR(500),
    base_branch VARCHAR(255),
    head_branch VARCHAR(255),
    head_commit_sha VARCHAR(64),
    pr_author VARCHAR(255),
    repository_name VARCHAR(255),
    status ENUM('PENDING', 'IN_PROGRESS', 'COMPLETED', 'FAILED', 'CANCELLED') NOT NULL DEFAULT 'PENDING',
    repository_id BINARY(16),
    user_id BINARY(16),
    summary TEXT,
    ai_review_content TEXT,
    files_changed INT,
    lines_added INT,
    lines_deleted INT,
    issues_found INT,
    critical_issues INT,
    high_issues INT,
    medium_issues INT,
    low_issues INT,
    llm_provider VARCHAR(50),
    input_tokens INT,
    output_tokens INT,
    estimated_cost DOUBLE,
    processing_time_ms BIGINT,
    error_message TEXT,
    total_files_to_review INT,
    files_reviewed_count INT,
    current_file VARCHAR(255),
    include_optimization BOOLEAN DEFAULT FALSE,
    optimization_completed BOOLEAN DEFAULT FALSE,
    optimization_summary TEXT,
    optimization_in_progress BOOLEAN DEFAULT FALSE,
    optimization_total_files INT,
    optimization_files_analyzed INT,
    optimization_current_file VARCHAR(255),
    optimization_started_at TIMESTAMP NULL,
    -- Cancellation fields (V3)
    cancellation_reason TEXT,
    cancelled_by_id BINARY(16),
    cancelled_at TIMESTAMP NULL,
    -- Diff storage (V5)
    raw_diff LONGTEXT,
    -- Optimistic locking
    version BIGINT DEFAULT 0,
    -- Timestamps
    started_at TIMESTAMP NULL,
    completed_at TIMESTAMP NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_reviews_repo FOREIGN KEY (repository_id) REFERENCES repositories(id) ON DELETE SET NULL,
    CONSTRAINT fk_reviews_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE SET NULL,
    CONSTRAINT fk_reviews_cancelled_by FOREIGN KEY (cancelled_by_id) REFERENCES users(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_reviews_status ON reviews(status);
CREATE INDEX idx_reviews_user ON reviews(user_id);
CREATE INDEX idx_reviews_repo ON reviews(repository_id);
CREATE INDEX idx_reviews_created ON reviews(created_at DESC);

-- =============================================================================
-- Review Issues
-- =============================================================================
CREATE TABLE review_issues (
    id BINARY(16) PRIMARY KEY,
    review_id BINARY(16) NOT NULL,
    analyzer VARCHAR(50) NOT NULL,
    severity ENUM('CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'INFO') NOT NULL,
    category ENUM('SECURITY', 'BUG', 'SMELL', 'STYLE', 'CVE', 'PERFORMANCE', 'LOGIC', 'OPTIMIZATION') NOT NULL,
    rule VARCHAR(255) NOT NULL,
    message TEXT NOT NULL,
    file_path VARCHAR(500),
    start_line INT,
    end_line INT,
    source ENUM('AI', 'STATIC', 'CVE'),
    cve_id VARCHAR(50),
    cvss_score DOUBLE,
    affected_package VARCHAR(255),
    fixed_version VARCHAR(100),
    ai_explanation TEXT,
    suggested_fix TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_issues_review FOREIGN KEY (review_id) REFERENCES reviews(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_issues_review ON review_issues(review_id);
CREATE INDEX idx_issues_severity ON review_issues(severity);

-- =============================================================================
-- Review Comments
-- =============================================================================
CREATE TABLE review_comments (
    id BINARY(16) PRIMARY KEY,
    review_id BINARY(16) NOT NULL,
    file_path VARCHAR(500) NOT NULL,
    start_line INT,
    end_line INT,
    commit_sha VARCHAR(64),
    body TEXT NOT NULL,
    category VARCHAR(50),
    suggestion TEXT,
    type ENUM('ISSUE', 'SUGGESTION', 'PRAISE', 'QUESTION', 'SECURITY') NOT NULL DEFAULT 'SUGGESTION',
    severity ENUM('CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'INFO'),
    posted_to_pr BOOLEAN DEFAULT FALSE,
    external_comment_id VARCHAR(255),
    was_helpful BOOLEAN,
    was_ignored BOOLEAN,
    ignore_reason TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_comments_review FOREIGN KEY (review_id) REFERENCES reviews(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_comments_review ON review_comments(review_id);

-- =============================================================================
-- Review File Diffs (V5)
-- =============================================================================
CREATE TABLE review_file_diffs (
    id BINARY(16) PRIMARY KEY,
    review_id BINARY(16) NOT NULL,
    file_path VARCHAR(500) NOT NULL,
    old_path VARCHAR(500),
    status ENUM('ADDED', 'MODIFIED', 'DELETED', 'RENAMED') NOT NULL,
    additions INT DEFAULT 0,
    deletions INT DEFAULT 0,
    patch LONGTEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_file_diffs_review FOREIGN KEY (review_id) REFERENCES reviews(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_file_diffs_review ON review_file_diffs(review_id);

-- =============================================================================
-- LLM Usage Tracking
-- =============================================================================
CREATE TABLE llm_usage (
    id BINARY(16) PRIMARY KEY,
    review_id BINARY(16),
    organization_id BINARY(16),
    provider VARCHAR(50) NOT NULL,
    model VARCHAR(100),
    task_type VARCHAR(50),
    input_tokens INT,
    output_tokens INT,
    estimated_cost DOUBLE,
    latency_ms BIGINT,
    success BOOLEAN,
    error_message TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_llm_review FOREIGN KEY (review_id) REFERENCES reviews(id) ON DELETE SET NULL,
    CONSTRAINT fk_llm_org FOREIGN KEY (organization_id) REFERENCES organizations(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_llm_org ON llm_usage(organization_id);
CREATE INDEX idx_llm_created ON llm_usage(created_at);

-- =============================================================================
-- Membership Requests
-- =============================================================================
CREATE TABLE membership_requests (
    id BINARY(16) PRIMARY KEY,
    user_id BINARY(16) NOT NULL,
    organization_id BINARY(16) NOT NULL,
    status ENUM('PENDING', 'APPROVED', 'REJECTED') DEFAULT 'PENDING',
    reviewed_by BINARY(16),
    requested_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    reviewed_at TIMESTAMP NULL,
    CONSTRAINT fk_membership_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_membership_org FOREIGN KEY (organization_id) REFERENCES organizations(id) ON DELETE CASCADE,
    CONSTRAINT fk_membership_reviewer FOREIGN KEY (reviewed_by) REFERENCES users(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_membership_org ON membership_requests(organization_id, status);

-- =============================================================================
-- Custom Review Rules (V2)
-- =============================================================================
CREATE TABLE review_rules (
    id BINARY(16) PRIMARY KEY,
    organization_id BINARY(16),
    name VARCHAR(255) NOT NULL,
    description TEXT,
    severity ENUM('CRITICAL', 'HIGH', 'MEDIUM', 'LOW') NOT NULL DEFAULT 'MEDIUM',
    category VARCHAR(50) NOT NULL DEFAULT 'CUSTOM',
    pattern VARCHAR(1000) NOT NULL,
    suggestion TEXT,
    enabled BOOLEAN DEFAULT TRUE,
    languages JSON,
    is_custom BOOLEAN DEFAULT TRUE,
    created_by_id BINARY(16),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_rules_org FOREIGN KEY (organization_id) REFERENCES organizations(id) ON DELETE CASCADE,
    CONSTRAINT fk_rules_creator FOREIGN KEY (created_by_id) REFERENCES users(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_rules_org ON review_rules(organization_id, enabled);

-- =============================================================================
-- Notifications (V4)
-- =============================================================================
CREATE TABLE notifications (
    id BINARY(16) PRIMARY KEY,
    user_id BINARY(16) NOT NULL,
    type ENUM('REVIEW_COMPLETED', 'REVIEW_FAILED', 'CRITICAL_ISSUES_FOUND', 'MEMBERSHIP_APPROVED', 'MEMBERSHIP_REJECTED', 'WEBHOOK_DELIVERY_FAILED') NOT NULL,
    title VARCHAR(255) NOT NULL,
    message TEXT NOT NULL,
    reference_type VARCHAR(50),
    reference_id BINARY(16),
    is_read BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    read_at TIMESTAMP NULL,
    CONSTRAINT fk_notifications_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_notifications_user_unread ON notifications(user_id, is_read, created_at DESC);

-- =============================================================================
-- Notification Preferences (V4)
-- =============================================================================
CREATE TABLE notification_preferences (
    id BINARY(16) PRIMARY KEY,
    user_id BINARY(16) NOT NULL UNIQUE,
    email_enabled BOOLEAN DEFAULT TRUE,
    in_app_enabled BOOLEAN DEFAULT TRUE,
    review_completed BOOLEAN DEFAULT TRUE,
    review_failed BOOLEAN DEFAULT TRUE,
    critical_issues BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_notif_prefs_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- =============================================================================
-- Webhook Configurations (V4)
-- =============================================================================
CREATE TABLE webhook_configs (
    id BINARY(16) PRIMARY KEY,
    organization_id BINARY(16) NOT NULL,
    name VARCHAR(255) NOT NULL,
    url VARCHAR(1000) NOT NULL,
    secret VARCHAR(255),
    events JSON NOT NULL,
    enabled BOOLEAN DEFAULT TRUE,
    failure_count INT DEFAULT 0,
    disabled_at TIMESTAMP NULL,
    retry_at TIMESTAMP NULL,
    retry_count INT DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    last_delivery_at TIMESTAMP NULL,
    CONSTRAINT fk_webhooks_org FOREIGN KEY (organization_id) REFERENCES organizations(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_webhooks_org ON webhook_configs(organization_id, enabled);

-- =============================================================================
-- Webhook Deliveries (V4)
-- =============================================================================
CREATE TABLE webhook_deliveries (
    id BINARY(16) PRIMARY KEY,
    webhook_config_id BINARY(16) NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    payload TEXT NOT NULL,
    status_code INT,
    response_body TEXT,
    duration_ms INT,
    success BOOLEAN,
    error_message TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_deliveries_webhook FOREIGN KEY (webhook_config_id) REFERENCES webhook_configs(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_deliveries_config ON webhook_deliveries(webhook_config_id, created_at DESC);

-- =============================================================================
-- Flyway Schema History (mark all migrations as applied)
-- =============================================================================
CREATE TABLE flyway_schema_history (
    installed_rank INT NOT NULL PRIMARY KEY,
    version VARCHAR(50),
    description VARCHAR(200) NOT NULL,
    type VARCHAR(20) NOT NULL,
    script VARCHAR(1000) NOT NULL,
    checksum INT,
    installed_by VARCHAR(100) NOT NULL,
    installed_on TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    execution_time INT NOT NULL,
    success TINYINT(1) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Mark migration as applied (with checksum matching migration file)
INSERT INTO flyway_schema_history (installed_rank, version, description, type, script, checksum, installed_by, execution_time, success) VALUES
(1, '1', 'initial schema', 'SQL', 'V1__initial_schema.sql', 1232133041, 'setup_script', 0, 1);

-- =============================================================================
-- Done!
-- =============================================================================
SELECT 'CodeLens database created successfully!' AS status;
SELECT COUNT(*) AS table_count FROM information_schema.tables WHERE table_schema = 'codelens';
