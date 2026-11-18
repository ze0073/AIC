CREATE TABLE hosting_worker_state (
    id VARCHAR(30) PRIMARY KEY,
    heartbeat_at DATETIME(6) NOT NULL
);
ALTER TABLE hosted_deployments ADD COLUMN database_schema_hash VARCHAR(64);
