-- CodeLens Database Initialization
-- This script runs on first container startup

-- Ensure proper character set
ALTER DATABASE codelens CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- Grant privileges
GRANT ALL PRIVILEGES ON codelens.* TO 'codelens'@'%';
FLUSH PRIVILEGES;

-- Create indexes for performance (tables created by JPA/Hibernate)
-- These will be created after the application starts and creates tables
