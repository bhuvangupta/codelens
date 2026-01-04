#!/bin/bash
# Delete a PR review from the database
# Usage: ./delete-review.sh <review-id>

if [ -z "$1" ]; then
    echo "Usage: ./delete-review.sh <review-id>"
    echo "Example: ./delete-review.sh 550e8400-e29b-41d4-a716-446655440000"
    exit 1
fi

REVIEW_ID=$1
DB_USER=${DB_USER:-root}
DB_PASS=${DB_PASS:-}
DB_NAME=${DB_NAME:-codelens}
DB_HOST=${DB_HOST:-localhost}

if [ -z "$DB_PASS" ]; then
    echo "Warning: DB_PASS not set. Set it via environment variable."
fi

echo "Deleting review: $REVIEW_ID"

mysql -h "$DB_HOST" -u "$DB_USER" ${DB_PASS:+-p"$DB_PASS"} "$DB_NAME" <<EOF
-- UUID is stored as BINARY(16), need to convert
SET @review_id = UUID_TO_BIN('$REVIEW_ID');

-- Show what will be deleted
SELECT 'Review to delete:' as info;
SELECT BIN_TO_UUID(id) as id, pr_url, pr_number, status, created_at FROM reviews WHERE id = @review_id;

SELECT 'Related records:' as info;
SELECT
    (SELECT COUNT(*) FROM review_comments WHERE review_id = @review_id) as comments,
    (SELECT COUNT(*) FROM review_issues WHERE review_id = @review_id) as issues,
    (SELECT COUNT(*) FROM llm_usage WHERE review_id = @review_id) as llm_usage;

-- Delete related records first
DELETE FROM review_comments WHERE review_id = @review_id;
DELETE FROM review_issues WHERE review_id = @review_id;
DELETE FROM llm_usage WHERE review_id = @review_id;

-- Delete the review
DELETE FROM reviews WHERE id = @review_id;

SELECT 'Done!' as result;
EOF

echo "Review $REVIEW_ID deleted."
