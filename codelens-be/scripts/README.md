# CodeLens Database Scripts

## Scripts

### `create_database.sql`
Complete SQL script to create the CodeLens database schema from scratch.

**Usage:**
```bash
mysql -u root -p < scripts/create_database.sql
```

**Warning:** This script will DROP all existing tables!

### `setup_database.sh`
Shell wrapper for `create_database.sql` with connection options.

**Usage:**
```bash
# Interactive (prompts for password)
./scripts/setup_database.sh

# With options
./scripts/setup_database.sh -h localhost -P 3306 -u root -p mypassword

# With environment variables
DB_HOST=localhost DB_USER=root DB_PASSWORD=mypassword ./scripts/setup_database.sh
```

## Schema Overview

The database includes the following tables:

| Table | Description |
|-------|-------------|
| `organizations` | Organization settings and credentials |
| `users` | User accounts with OAuth provider info |
| `repositories` | GitHub/GitLab repositories |
| `reviews` | Code review records |
| `review_issues` | Issues found during reviews |
| `review_comments` | Inline comments for reviews |
| `review_file_diffs` | Stored file diffs for diff viewer |
| `review_rules` | Custom analysis rules per organization |
| `llm_usage` | LLM token and cost tracking |
| `membership_requests` | Organization membership requests |
| `notifications` | In-app notifications |
| `notification_preferences` | User notification settings |
| `webhook_configs` | Webhook endpoints per organization |
| `webhook_deliveries` | Webhook delivery logs |

## Flyway Migrations

For incremental updates, use Flyway migrations in:
```
src/main/resources/db/migration/
```

Current migrations:
- `V1__baseline.sql` - Initial schema
- `V2__custom_rules.sql` - Custom review rules
- `V3__review_cancellation.sql` - Review cancellation support
- `V4__notifications.sql` - Notifications and webhooks
- `V5__diff_storage.sql` - Diff viewer storage
