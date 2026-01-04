-- CodeLens Initial Schema
-- Combined schema for fresh installations

-- Organizations
CREATE TABLE IF NOT EXISTS organizations (
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
);

-- Users
CREATE TABLE IF NOT EXISTS users (
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
);

CREATE INDEX IF NOT EXISTS idx_users_email ON users(email);
CREATE INDEX IF NOT EXISTS idx_users_provider ON users(provider, provider_id);

-- Repositories
CREATE TABLE IF NOT EXISTS repositories (
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
);

CREATE INDEX IF NOT EXISTS idx_repos_org ON repositories(organization_id);

-- Reviews
CREATE TABLE IF NOT EXISTS reviews (
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
    -- Cancellation fields
    cancellation_reason TEXT,
    cancelled_by_id BINARY(16),
    cancelled_at TIMESTAMP NULL,
    -- Diff storage
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
);

CREATE INDEX IF NOT EXISTS idx_reviews_status ON reviews(status);
CREATE INDEX IF NOT EXISTS idx_reviews_user ON reviews(user_id);
CREATE INDEX IF NOT EXISTS idx_reviews_repo ON reviews(repository_id);
CREATE INDEX IF NOT EXISTS idx_reviews_created ON reviews(created_at DESC);

-- Review Issues
CREATE TABLE IF NOT EXISTS review_issues (
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
);

CREATE INDEX IF NOT EXISTS idx_issues_review ON review_issues(review_id);
CREATE INDEX IF NOT EXISTS idx_issues_severity ON review_issues(severity);

-- Review Comments
CREATE TABLE IF NOT EXISTS review_comments (
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
);

CREATE INDEX IF NOT EXISTS idx_comments_review ON review_comments(review_id);

-- Review File Diffs
CREATE TABLE IF NOT EXISTS review_file_diffs (
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
);

CREATE INDEX IF NOT EXISTS idx_file_diffs_review ON review_file_diffs(review_id);

-- LLM Usage Tracking
CREATE TABLE IF NOT EXISTS llm_usage (
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
);

CREATE INDEX IF NOT EXISTS idx_llm_org ON llm_usage(organization_id);
CREATE INDEX IF NOT EXISTS idx_llm_created ON llm_usage(created_at);

-- Membership Requests
CREATE TABLE IF NOT EXISTS membership_requests (
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
);

CREATE INDEX IF NOT EXISTS idx_membership_org ON membership_requests(organization_id, status);

-- Custom Review Rules
CREATE TABLE IF NOT EXISTS review_rules (
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
);

CREATE INDEX IF NOT EXISTS idx_rules_org ON review_rules(organization_id, enabled);

-- Notifications
CREATE TABLE IF NOT EXISTS notifications (
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
);

CREATE INDEX IF NOT EXISTS idx_notifications_user_unread ON notifications(user_id, is_read, created_at DESC);

-- Notification Preferences
CREATE TABLE IF NOT EXISTS notification_preferences (
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
);

-- Webhook Configurations
CREATE TABLE IF NOT EXISTS webhook_configs (
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
);

CREATE INDEX IF NOT EXISTS idx_webhooks_org ON webhook_configs(organization_id, enabled);

-- Webhook Deliveries
CREATE TABLE IF NOT EXISTS webhook_deliveries (
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
);

CREATE INDEX IF NOT EXISTS idx_deliveries_config ON webhook_deliveries(webhook_config_id, created_at DESC);
