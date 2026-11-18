ALTER TABLE app_users ADD COLUMN auth_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE scan_tasks ADD COLUMN cancel_requested BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN created_by CHAR(26) NULL;
ALTER TABLE projects ADD COLUMN deletion_status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE';
CREATE TABLE runtime_queue_locks (id VARCHAR(20) PRIMARY KEY) ENGINE=InnoDB;
INSERT INTO runtime_queue_locks(id) VALUES('PLATFORM'),('HOSTING_WORKER'),('AGENT');
CREATE TABLE notification_reads (
    notification_id CHAR(26) NOT NULL,
    user_id CHAR(26) NOT NULL,
    read_at DATETIME(3) NOT NULL,
    PRIMARY KEY(notification_id,user_id),
    FOREIGN KEY(notification_id) REFERENCES notifications(id) ON DELETE CASCADE,
    FOREIGN KEY(user_id) REFERENCES app_users(id) ON DELETE CASCADE
) ENGINE=InnoDB;
INSERT INTO notification_reads(notification_id,user_id,read_at)
    SELECT id,user_id,read_at FROM notifications WHERE user_id IS NOT NULL AND read_at IS NOT NULL;
CREATE TABLE scoped_idempotency_records (
    actor VARCHAR(100) NOT NULL,
    operation VARCHAR(100) NOT NULL,
    idempotency_key VARCHAR(120) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    resource_id CHAR(26) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY(actor,operation,idempotency_key)
) ENGINE=InnoDB;
CREATE TABLE project_cleanup_jobs (
    id CHAR(26) PRIMARY KEY,
    project_id CHAR(26) NOT NULL,
    requested_by CHAR(26) NOT NULL,
    status VARCHAR(20) NOT NULL,
    error_message VARCHAR(500) NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    INDEX idx_cleanup_pending(status,created_at),
    UNIQUE KEY uk_cleanup_project(project_id)
) ENGINE=InnoDB;

ALTER TABLE regression_plans ADD COLUMN created_by CHAR(26) NULL;
