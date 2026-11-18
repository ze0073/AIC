CREATE TABLE report_snapshots (
    id CHAR(26) PRIMARY KEY,
    scan_task_id CHAR(26) NOT NULL,
    idempotency_key VARCHAR(120) NOT NULL,
    content_hash CHAR(64) NOT NULL,
    payload LONGTEXT NOT NULL,
    created_at DATETIME(3) NOT NULL,
    pdf_artifact_id CHAR(26),
    json_artifact_id CHAR(26),
    UNIQUE KEY uk_snapshot_request (scan_task_id, idempotency_key),
    CONSTRAINT fk_snapshot_scan FOREIGN KEY (scan_task_id) REFERENCES scan_tasks(id) ON DELETE CASCADE,
    CONSTRAINT fk_snapshot_pdf FOREIGN KEY (pdf_artifact_id) REFERENCES stored_artifacts(id) ON DELETE SET NULL,
    CONSTRAINT fk_snapshot_json FOREIGN KEY (json_artifact_id) REFERENCES stored_artifacts(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
