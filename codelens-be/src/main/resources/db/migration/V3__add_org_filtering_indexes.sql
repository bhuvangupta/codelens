-- Migration: Add indexes to optimize organization-scoped queries
-- These indexes support the multi-tenant org filtering added for domain-based OAuth
-- DROP INDEX IF EXISTS is used to make this migration idempotent for databases
-- that already have these indexes (e.g., created previously by Hibernate ddl-auto).

-- ============================================================
-- CRITICAL: User organization lookups
-- ============================================================
-- Used by: findByOrganizationId(), organization member listing
DROP INDEX IF EXISTS idx_users_organization_id ON users;
CREATE INDEX idx_users_organization_id ON users(organization_id);

-- ============================================================
-- CRITICAL: Review queries with user/org filtering
-- ============================================================
-- Used by: findRecentReviewsByOrganization, user dashboard queries
DROP INDEX IF EXISTS idx_reviews_user_created ON reviews;
CREATE INDEX idx_reviews_user_created ON reviews(user_id, created_at);

-- Used by: countByOrganizationAndStatus, dashboard status counts
DROP INDEX IF EXISTS idx_reviews_status_created ON reviews;
CREATE INDEX idx_reviews_status_created ON reviews(status, created_at);

-- Used by: findByRepositoryOrderByCreatedAtDesc, repository-specific views
DROP INDEX IF EXISTS idx_reviews_repository_created ON reviews;
CREATE INDEX idx_reviews_repository_created ON reviews(repository_id, created_at);

-- Used by: countByDayAfterByOrganization, trend analytics
DROP INDEX IF EXISTS idx_reviews_created_date ON reviews;
CREATE INDEX idx_reviews_created_date ON reviews(created_at);

-- ============================================================
-- CRITICAL: Review issues queries
-- ============================================================
-- Used by: findByReviewAndSeverity, issue detail pages
DROP INDEX IF EXISTS idx_issues_review_severity ON review_issues;
CREATE INDEX idx_issues_review_severity ON review_issues(review_id, severity);

-- Used by: findByReviewAndCategory, category-based filtering
DROP INDEX IF EXISTS idx_issues_review_category ON review_issues;
CREATE INDEX idx_issues_review_category ON review_issues(review_id, category);

-- Used by: countByDayAfterByOrganization, trend analytics
DROP INDEX IF EXISTS idx_issues_created ON review_issues;
CREATE INDEX idx_issues_created ON review_issues(created_at);

-- ============================================================
-- HIGH: LLM usage analytics
-- ============================================================
-- Used by: sumEstimatedCostByOrganizationAndCreatedAtAfter, cost analytics
DROP INDEX IF EXISTS idx_llm_org_created ON llm_usage;
CREATE INDEX idx_llm_org_created ON llm_usage(organization_id, created_at);

-- ============================================================
-- MEDIUM: Additional composite indexes for common query patterns
-- ============================================================
-- Used by: findDistinctRepositoryNamesByOrganization
DROP INDEX IF EXISTS idx_reviews_user_repo_name ON reviews;
CREATE INDEX idx_reviews_user_repo_name ON reviews(user_id, repository_name(100));

-- Used by: findTopCategoriesByCountByOrganization (native query joins)
DROP INDEX IF EXISTS idx_issues_created_category ON review_issues;
CREATE INDEX idx_issues_created_category ON review_issues(created_at, category);
