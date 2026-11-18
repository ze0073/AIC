CREATE TABLE test_run_report_snapshots (
    id CHAR(26) PRIMARY KEY,
    run_id CHAR(26) NOT NULL,
    schema_version VARCHAR(20) NOT NULL,
    idempotency_key VARCHAR(120) NOT NULL,
    content_hash CHAR(64) NOT NULL,
    payload LONGTEXT NOT NULL,
    json_path VARCHAR(1000),
    pdf_path VARCHAR(1000),
    created_at DATETIME(3) NOT NULL,
    UNIQUE KEY uk_test_run_snapshot_request (run_id,idempotency_key),
    CONSTRAINT fk_test_run_snapshots_run FOREIGN KEY (run_id) REFERENCES test_runs(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
