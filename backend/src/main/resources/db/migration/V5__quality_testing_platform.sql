-- V0.6.0 keeps the static-scan tables immutable and adds a separate online execution model.
CREATE TABLE app_users (
    id CHAR(26) PRIMARY KEY,
    username VARCHAR(80) NOT NULL,
    display_name VARCHAR(120) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    UNIQUE KEY uk_app_users_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE project_members (
    project_id CHAR(26) NOT NULL,
    user_id CHAR(26) NOT NULL,
    role VARCHAR(20) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (project_id, user_id),
    INDEX idx_project_members_user (user_id, project_id),
    CONSTRAINT fk_project_members_project FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE,
    CONSTRAINT fk_project_members_user FOREIGN KEY (user_id) REFERENCES app_users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE online_targets (
    id CHAR(26) PRIMARY KEY,
    project_id CHAR(26) NOT NULL,
    name VARCHAR(120) NOT NULL,
    base_url VARCHAR(1000) NOT NULL,
    allowed_origin VARCHAR(500) NOT NULL,
    allowed_addresses JSON NOT NULL,
    path_prefixes JSON NOT NULL,
    allow_active_scan BOOLEAN NOT NULL DEFAULT FALSE,
    allow_write_tests BOOLEAN NOT NULL DEFAULT FALSE,
    cleanup_instructions VARCHAR(2000),
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    INDEX idx_online_targets_project (project_id, created_at),
    CONSTRAINT fk_online_targets_project FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE encrypted_credentials (
    id CHAR(26) PRIMARY KEY,
    project_id CHAR(26) NOT NULL,
    target_id CHAR(26) NOT NULL,
    name VARCHAR(120) NOT NULL,
    principal_type VARCHAR(20) NOT NULL,
    auth_type VARCHAR(20) NOT NULL,
    encrypted_secret MEDIUMTEXT NOT NULL,
    secret_fingerprint CHAR(64) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    UNIQUE KEY uk_credential_target_name (target_id, name),
    CONSTRAINT fk_credentials_project FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE,
    CONSTRAINT fk_credentials_target FOREIGN KEY (target_id) REFERENCES online_targets(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE test_suites (
    id CHAR(26) PRIMARY KEY,
    project_id CHAR(26) NOT NULL,
    name VARCHAR(160) NOT NULL,
    description VARCHAR(1000),
    current_version INT NOT NULL DEFAULT 1,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    INDEX idx_test_suites_project (project_id, updated_at),
    CONSTRAINT fk_test_suites_project FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE test_suite_versions (
    id CHAR(26) PRIMARY KEY,
    suite_id CHAR(26) NOT NULL,
    version_no INT NOT NULL,
    definition JSON NOT NULL,
    created_by CHAR(26),
    created_at DATETIME(3) NOT NULL,
    UNIQUE KEY uk_test_suite_versions (suite_id, version_no),
    CONSTRAINT fk_test_suite_versions_suite FOREIGN KEY (suite_id) REFERENCES test_suites(id) ON DELETE CASCADE,
    CONSTRAINT fk_test_suite_versions_creator FOREIGN KEY (created_by) REFERENCES app_users(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE test_runs (
    id CHAR(26) PRIMARY KEY,
    project_id CHAR(26) NOT NULL,
    target_id CHAR(26) NOT NULL,
    suite_version_id CHAR(26),
    status VARCHAR(30) NOT NULL,
    cancel_requested BOOLEAN NOT NULL DEFAULT FALSE,
    config_snapshot JSON NOT NULL,
    tool_snapshot JSON NOT NULL,
    created_by CHAR(26),
    error_message VARCHAR(1000),
    created_at DATETIME(3) NOT NULL,
    started_at DATETIME(3),
    finished_at DATETIME(3),
    INDEX idx_test_runs_project (project_id, created_at),
    INDEX idx_test_runs_target (target_id, created_at),
    CONSTRAINT fk_test_runs_project FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE,
    CONSTRAINT fk_test_runs_target FOREIGN KEY (target_id) REFERENCES online_targets(id) ON DELETE RESTRICT,
    CONSTRAINT fk_test_runs_suite_version FOREIGN KEY (suite_version_id) REFERENCES test_suite_versions(id) ON DELETE SET NULL,
    CONSTRAINT fk_test_runs_creator FOREIGN KEY (created_by) REFERENCES app_users(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE test_run_modules (
    id CHAR(26) PRIMARY KEY,
    run_id CHAR(26) NOT NULL,
    module_type VARCHAR(30) NOT NULL,
    status VARCHAR(30) NOT NULL,
    summary VARCHAR(1000),
    result_payload JSON,
    started_at DATETIME(3),
    finished_at DATETIME(3),
    UNIQUE KEY uk_test_run_module (run_id, module_type),
    CONSTRAINT fk_test_run_modules_run FOREIGN KEY (run_id) REFERENCES test_runs(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE run_issues (
    id CHAR(26) PRIMARY KEY,
    project_id CHAR(26) NOT NULL,
    run_id CHAR(26) NOT NULL,
    module_id CHAR(26),
    issue_key CHAR(64) NOT NULL,
    source VARCHAR(30) NOT NULL,
    severity VARCHAR(20) NOT NULL,
    evidence_confidence VARCHAR(20) NOT NULL,
    review_status VARCHAR(30) NOT NULL DEFAULT 'PENDING_REVIEW',
    task_status VARCHAR(30) NOT NULL DEFAULT 'OPEN',
    title VARCHAR(300) NOT NULL,
    description TEXT NOT NULL,
    evidence JSON NOT NULL,
    assignee_id CHAR(26),
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    INDEX idx_run_issues_project (project_id, task_status, updated_at),
    INDEX idx_run_issues_run (run_id, severity),
    CONSTRAINT fk_run_issues_project FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE,
    CONSTRAINT fk_run_issues_run FOREIGN KEY (run_id) REFERENCES test_runs(id) ON DELETE CASCADE,
    CONSTRAINT fk_run_issues_module FOREIGN KEY (module_id) REFERENCES test_run_modules(id) ON DELETE SET NULL,
    CONSTRAINT fk_run_issues_assignee FOREIGN KEY (assignee_id) REFERENCES app_users(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE issue_comments (
    id CHAR(26) PRIMARY KEY,
    issue_id CHAR(26) NOT NULL,
    user_id CHAR(26) NOT NULL,
    body VARCHAR(3000) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    CONSTRAINT fk_issue_comments_issue FOREIGN KEY (issue_id) REFERENCES run_issues(id) ON DELETE CASCADE,
    CONSTRAINT fk_issue_comments_user FOREIGN KEY (user_id) REFERENCES app_users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE knowledge_cases (
    id CHAR(26) PRIMARY KEY,
    project_id CHAR(26) NOT NULL,
    issue_id CHAR(26),
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    title VARCHAR(300) NOT NULL,
    category VARCHAR(80),
    tags JSON,
    content JSON NOT NULL,
    created_by CHAR(26),
    published_at DATETIME(3),
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    INDEX idx_knowledge_cases_project (project_id, status, updated_at),
    CONSTRAINT fk_knowledge_cases_project FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE,
    CONSTRAINT fk_knowledge_cases_issue FOREIGN KEY (issue_id) REFERENCES run_issues(id) ON DELETE SET NULL,
    CONSTRAINT fk_knowledge_cases_creator FOREIGN KEY (created_by) REFERENCES app_users(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE regression_plans (
    id CHAR(26) PRIMARY KEY,
    project_id CHAR(26) NOT NULL,
    target_id CHAR(26) NOT NULL,
    suite_version_id CHAR(26) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    cron_expression VARCHAR(100) NOT NULL DEFAULT '0 0 2 * * *',
    active_scan_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    upload_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    load_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    next_run_at DATETIME(3),
    last_run_at DATETIME(3),
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    CONSTRAINT fk_regression_plans_project FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE,
    CONSTRAINT fk_regression_plans_target FOREIGN KEY (target_id) REFERENCES online_targets(id) ON DELETE CASCADE,
    CONSTRAINT fk_regression_plans_suite_version FOREIGN KEY (suite_version_id) REFERENCES test_suite_versions(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE notifications (
    id CHAR(26) PRIMARY KEY,
    project_id CHAR(26) NOT NULL,
    user_id CHAR(26),
    type VARCHAR(40) NOT NULL,
    title VARCHAR(300) NOT NULL,
    payload JSON NOT NULL,
    read_at DATETIME(3),
    created_at DATETIME(3) NOT NULL,
    INDEX idx_notifications_user (user_id, read_at, created_at),
    CONSTRAINT fk_notifications_project FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE,
    CONSTRAINT fk_notifications_user FOREIGN KEY (user_id) REFERENCES app_users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
