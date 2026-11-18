CREATE TABLE projects (
    id CHAR(26) PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    description VARCHAR(500),
    type VARCHAR(40) NOT NULL DEFAULT 'STATIC_WEB',
    current_version_id CHAR(26),
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    INDEX idx_projects_updated_at (updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE project_versions (
    id CHAR(26) PRIMARY KEY,
    project_id CHAR(26) NOT NULL,
    base_version_id CHAR(26),
    version_no INT NOT NULL,
    source_type VARCHAR(30) NOT NULL,
    storage_path VARCHAR(700) NOT NULL,
    entry_page VARCHAR(500) NOT NULL,
    manifest_hash CHAR(64) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    UNIQUE KEY uk_project_version (project_id, version_no),
    INDEX idx_project_versions_project (project_id, created_at),
    CONSTRAINT fk_versions_project FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE projects ADD CONSTRAINT fk_projects_current_version
    FOREIGN KEY (current_version_id) REFERENCES project_versions(id) ON DELETE SET NULL;

CREATE TABLE scan_tasks (
    id CHAR(26) PRIMARY KEY,
    project_id CHAR(26) NOT NULL,
    version_id CHAR(26) NOT NULL,
    status VARCHAR(30) NOT NULL,
    stage VARCHAR(40) NOT NULL,
    progress INT NOT NULL DEFAULT 0,
    found_count INT NOT NULL DEFAULT 0,
    high_count INT NOT NULL DEFAULT 0,
    medium_count INT NOT NULL DEFAULT 0,
    low_count INT NOT NULL DEFAULT 0,
    risk_score INT,
    scan_config JSON NOT NULL,
    rule_version VARCHAR(30) NOT NULL,
    error_message VARCHAR(1000),
    created_at DATETIME(3) NOT NULL,
    started_at DATETIME(3),
    finished_at DATETIME(3),
    INDEX idx_scan_version_created (version_id, created_at),
    CONSTRAINT fk_scans_project FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE,
    CONSTRAINT fk_scans_version FOREIGN KEY (version_id) REFERENCES project_versions(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE scan_logs (
    id CHAR(26) PRIMARY KEY,
    scan_task_id CHAR(26) NOT NULL,
    level VARCHAR(20) NOT NULL,
    stage VARCHAR(40) NOT NULL,
    message VARCHAR(1000) NOT NULL,
    details JSON,
    created_at DATETIME(3) NOT NULL,
    INDEX idx_scan_logs_task (scan_task_id, created_at),
    CONSTRAINT fk_logs_scan FOREIGN KEY (scan_task_id) REFERENCES scan_tasks(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE stored_artifacts (
    id CHAR(26) PRIMARY KEY,
    project_id CHAR(26) NOT NULL,
    version_id CHAR(26),
    scan_task_id CHAR(26),
    kind VARCHAR(30) NOT NULL,
    relative_path VARCHAR(700) NOT NULL,
    sha256 CHAR(64) NOT NULL,
    size_bytes BIGINT NOT NULL,
    created_at DATETIME(3) NOT NULL,
    INDEX idx_artifact_scan (scan_task_id, kind),
    CONSTRAINT fk_artifact_project FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE,
    CONSTRAINT fk_artifact_version FOREIGN KEY (version_id) REFERENCES project_versions(id) ON DELETE CASCADE,
    CONSTRAINT fk_artifact_scan FOREIGN KEY (scan_task_id) REFERENCES scan_tasks(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE findings (
    id CHAR(26) PRIMARY KEY,
    scan_task_id CHAR(26) NOT NULL,
    rule_id VARCHAR(20) NOT NULL,
    source VARCHAR(20) NOT NULL,
    severity VARCHAR(20) NOT NULL,
    status VARCHAR(30) NOT NULL,
    title VARCHAR(300) NOT NULL,
    description TEXT NOT NULL,
    page_path VARCHAR(500) NOT NULL,
    selector VARCHAR(1000) NOT NULL,
    fix_mode VARCHAR(30) NOT NULL,
    fingerprint CHAR(64) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    UNIQUE KEY uk_finding_fingerprint (scan_task_id, fingerprint),
    INDEX idx_findings_filter (scan_task_id, severity, status),
    INDEX idx_findings_rule_page (scan_task_id, rule_id, page_path),
    CONSTRAINT fk_findings_scan FOREIGN KEY (scan_task_id) REFERENCES scan_tasks(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE finding_evidences (
    id CHAR(26) PRIMARY KEY,
    finding_id CHAR(26) NOT NULL,
    kind VARCHAR(30) NOT NULL,
    summary VARCHAR(500) NOT NULL,
    payload JSON NOT NULL,
    artifact_id CHAR(26),
    created_at DATETIME(3) NOT NULL,
    INDEX idx_evidence_finding (finding_id, kind),
    CONSTRAINT fk_evidence_finding FOREIGN KEY (finding_id) REFERENCES findings(id) ON DELETE CASCADE,
    CONSTRAINT fk_evidence_artifact FOREIGN KEY (artifact_id) REFERENCES stored_artifacts(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE fix_patches (
    id CHAR(26) PRIMARY KEY,
    finding_id CHAR(26) NOT NULL,
    base_version_id CHAR(26) NOT NULL,
    new_version_id CHAR(26),
    target_file VARCHAR(500) NOT NULL,
    before_sha CHAR(64) NOT NULL,
    patched_content LONGTEXT NOT NULL,
    diff_text LONGTEXT NOT NULL,
    status VARCHAR(30) NOT NULL,
    verification JSON,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    INDEX idx_patch_finding (finding_id, base_version_id, status),
    CONSTRAINT fk_patch_finding FOREIGN KEY (finding_id) REFERENCES findings(id) ON DELETE CASCADE,
    CONSTRAINT fk_patch_base_version FOREIGN KEY (base_version_id) REFERENCES project_versions(id) ON DELETE CASCADE,
    CONSTRAINT fk_patch_new_version FOREIGN KEY (new_version_id) REFERENCES project_versions(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE finding_reviews (
    id CHAR(26) PRIMARY KEY,
    finding_id CHAR(26) NOT NULL,
    action VARCHAR(30) NOT NULL,
    comment VARCHAR(1000),
    created_at DATETIME(3) NOT NULL,
    INDEX idx_review_finding (finding_id, created_at),
    CONSTRAINT fk_reviews_finding FOREIGN KEY (finding_id) REFERENCES findings(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE report_exports (
    id CHAR(26) PRIMARY KEY,
    scan_task_id CHAR(26) NOT NULL,
    format VARCHAR(10) NOT NULL,
    artifact_id CHAR(26),
    status VARCHAR(30) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    INDEX idx_report_scan (scan_task_id, format),
    CONSTRAINT fk_reports_scan FOREIGN KEY (scan_task_id) REFERENCES scan_tasks(id) ON DELETE CASCADE,
    CONSTRAINT fk_reports_artifact FOREIGN KEY (artifact_id) REFERENCES stored_artifacts(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE idempotency_records (
    idempotency_key VARCHAR(120) NOT NULL,
    operation VARCHAR(100) NOT NULL,
    resource_id CHAR(26) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (idempotency_key, operation)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
