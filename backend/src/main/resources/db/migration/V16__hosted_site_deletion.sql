ALTER TABLE hosted_sites
    ADD COLUMN deletion_status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN deletion_error VARCHAR(1000),
    ADD INDEX idx_hosted_deletion (deletion_status, updated_at);
