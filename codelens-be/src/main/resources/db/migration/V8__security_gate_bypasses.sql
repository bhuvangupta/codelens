-- Audit trail for security gate bypasses.
--
-- One row each time an allowlisted user overrides the codelens/deps, codelens/sast and
-- codelens/secrets checks for a PR head commit. Rows are never updated or deleted by
-- the application: a new push needs a new bypass, which is a new row.

CREATE TABLE IF NOT EXISTS security_gate_bypasses (
    id BINARY(16) PRIMARY KEY,
    provider VARCHAR(20) NOT NULL,
    repo_full_name VARCHAR(255) NOT NULL,
    pr_number INT NOT NULL,
    head_sha VARCHAR(64) NOT NULL,
    actor_email VARCHAR(255) NOT NULL,
    actor_github_login VARCHAR(255),
    via VARCHAR(20) NOT NULL,
    reason TEXT NOT NULL,
    findings_snapshot TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_gate_bypass_commit ON security_gate_bypasses(repo_full_name, head_sha);
