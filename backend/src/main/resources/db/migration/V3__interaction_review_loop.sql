ALTER TABLE scan_tasks
    ADD COLUMN review_count INT NOT NULL DEFAULT 0 AFTER low_count;

ALTER TABLE findings
    ADD COLUMN evidence_fingerprint CHAR(64) NULL AFTER fingerprint,
    ADD INDEX idx_findings_review (scan_task_id, status, source),
    ADD INDEX idx_findings_evidence_fingerprint (evidence_fingerprint);

CREATE TABLE page_analysis_traces (
    id CHAR(26) PRIMARY KEY,
    scan_task_id CHAR(26) NOT NULL,
    page_path VARCHAR(500) NOT NULL,
    trace_type VARCHAR(30) NOT NULL,
    status VARCHAR(30) NOT NULL,
    payload JSON NOT NULL,
    artifact_id CHAR(26),
    created_at DATETIME(3) NOT NULL,
    UNIQUE KEY uk_trace_scan_page_type (scan_task_id, page_path, trace_type),
    INDEX idx_trace_scan_type (scan_task_id, trace_type),
    CONSTRAINT fk_trace_scan FOREIGN KEY (scan_task_id) REFERENCES scan_tasks(id) ON DELETE CASCADE,
    CONSTRAINT fk_trace_artifact FOREIGN KEY (artifact_id) REFERENCES stored_artifacts(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE project_review_policies (
    id CHAR(26) PRIMARY KEY,
    project_id CHAR(26) NOT NULL,
    match_key CHAR(64) NOT NULL,
    rule_id VARCHAR(20) NOT NULL,
    page_path VARCHAR(500) NOT NULL,
    selector VARCHAR(1000) NOT NULL,
    evidence_fingerprint CHAR(64) NOT NULL,
    decision VARCHAR(30) NOT NULL,
    comment VARCHAR(1000),
    source_review_id CHAR(26) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    UNIQUE KEY uk_review_policy_match (project_id, match_key),
    INDEX idx_review_policy_project_rule (project_id, rule_id, active),
    CONSTRAINT fk_review_policy_project FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE finding_reviews
    ADD COLUMN reused BOOLEAN NOT NULL DEFAULT FALSE AFTER comment,
    ADD COLUMN source_policy_id CHAR(26) NULL AFTER reused,
    ADD INDEX idx_review_policy_source (source_policy_id),
    ADD CONSTRAINT fk_review_source_policy FOREIGN KEY (source_policy_id)
        REFERENCES project_review_policies(id) ON DELETE SET NULL;
