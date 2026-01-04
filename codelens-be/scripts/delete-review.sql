-- Delete a PR review and all related data
-- Usage: Replace 'YOUR_REVIEW_ID' with the actual UUID
-- Run: mysql -u username -p codelens < delete-review.sql

-- UUID is stored as BINARY(16), need to convert
SET @review_id = UUID_TO_BIN('YOUR_REVIEW_ID');

-- Show what will be deleted
SELECT 'Review to delete:' as info;
SELECT BIN_TO_UUID(id) as id, pr_url, pr_number, status, created_at FROM reviews WHERE id = @review_id;

SELECT 'Related records:' as info;
SELECT
    (SELECT COUNT(*) FROM review_comments WHERE review_id = @review_id) as comments,
    (SELECT COUNT(*) FROM review_issues WHERE review_id = @review_id) as issues,
    (SELECT COUNT(*) FROM llm_usage WHERE review_id = @review_id) as llm_usage;

-- Delete related records first (foreign key constraints)
DELETE FROM review_comments WHERE review_id = @review_id;
DELETE FROM review_issues WHERE review_id = @review_id;
DELETE FROM llm_usage WHERE review_id = @review_id;

-- Delete the review
DELETE FROM reviews WHERE id = @review_id;

SELECT 'Done! Review deleted.' as result;
