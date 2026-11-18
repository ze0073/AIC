ALTER TABLE app_users ADD COLUMN platform_role VARCHAR(20) NOT NULL DEFAULT 'USER',
    ADD COLUMN project_limit INT NOT NULL DEFAULT 3,
    ADD COLUMN environment_limit INT NOT NULL DEFAULT 2,
    ADD COLUMN build_limit INT NOT NULL DEFAULT 1;
ALTER TABLE issue_repairs ADD COLUMN hosted_deployment_error VARCHAR(2000);

CREATE TABLE hosted_sites (
    id CHAR(26) PRIMARY KEY,
    project_id CHAR(26) NOT NULL,
    owner_id CHAR(26) NOT NULL,
    name VARCHAR(120) NOT NULL,
    purpose VARCHAR(20) NOT NULL,
    source_version_id CHAR(26),
    source_kind VARCHAR(20),
    repository_url VARCHAR(1000),
    repository_branch VARCHAR(160),
    repository_commit VARCHAR(64),
    encrypted_repository_secret MEDIUMTEXT,
    runtime_config JSON,
    source_job_id CHAR(26),
    test_policy JSON,
    encrypted_environment MEDIUMTEXT,
    current_deployment_id CHAR(26),
    previous_deployment_id CHAR(26),
    suite_version_id CHAR(26),
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    UNIQUE KEY uk_hosted_project (project_id),
    CONSTRAINT fk_hosted_project FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE RESTRICT,
    CONSTRAINT fk_hosted_owner FOREIGN KEY (owner_id) REFERENCES app_users(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE hosted_deployments (
    id CHAR(26) PRIMARY KEY,
    site_id CHAR(26) NOT NULL,
    source_version_id CHAR(26) NOT NULL,
    base_deployment_id CHAR(26),
    expected_live_deployment_id CHAR(26),
    repair_id CHAR(26),
    release_candidate_id CHAR(26),
    environment_kind VARCHAR(20) NOT NULL DEFAULT 'TEST',
    status VARCHAR(30) NOT NULL,
    runtime_config JSON NOT NULL,
    test_policy JSON,
    encrypted_environment MEDIUMTEXT,
    source_manifest CHAR(64) NOT NULL,
    repository_commit VARCHAR(64),
    image_digest VARCHAR(100),
    frontend_image_digest VARCHAR(100),
    encrypted_access_token MEDIUMTEXT,
    upstream_port INT,
    published BOOLEAN NOT NULL DEFAULT FALSE,
    container_name VARCHAR(100),
    database_resource_id CHAR(26),
    target_id CHAR(26),
    run_id CHAR(26),
    suite_version_id CHAR(26),
    auto_test BOOLEAN NOT NULL DEFAULT TRUE,
    cancel_requested BOOLEAN NOT NULL DEFAULT FALSE,
    error_message VARCHAR(2000),
    expires_at DATETIME(3),
    created_by CHAR(26) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    UNIQUE KEY uk_deployment_target (target_id),
    INDEX idx_deployment_queue (status,created_at),
    CONSTRAINT fk_deployment_site FOREIGN KEY (site_id) REFERENCES hosted_sites(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE hosted_source_jobs (
    id CHAR(26) PRIMARY KEY,
    site_id CHAR(26) NOT NULL,
    repository_url VARCHAR(1000) NOT NULL,
    branch_name VARCHAR(160) NOT NULL,
    encrypted_secret MEDIUMTEXT,
    status VARCHAR(30) NOT NULL,
    error_message VARCHAR(2000),
    created_by CHAR(26) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    CONSTRAINT fk_source_job_site FOREIGN KEY (site_id) REFERENCES hosted_sites(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE hosted_database_resources (
    id CHAR(26) PRIMARY KEY,
    site_id CHAR(26) NOT NULL,
    purpose VARCHAR(20) NOT NULL,
    engine VARCHAR(20) NOT NULL,
    container_name VARCHAR(100) NOT NULL,
    network_name VARCHAR(100) NOT NULL,
    volume_name VARCHAR(100) NOT NULL,
    encrypted_secret MEDIUMTEXT NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    CONSTRAINT fk_hosted_database_site FOREIGN KEY (site_id) REFERENCES hosted_sites(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE hosted_deployment_events (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    deployment_id CHAR(26) NOT NULL,
    stage VARCHAR(30) NOT NULL,
    message TEXT NOT NULL,
    created_at DATETIME(3) NOT NULL,
    INDEX idx_deployment_events (deployment_id,id),
    CONSTRAINT fk_deployment_events FOREIGN KEY (deployment_id) REFERENCES hosted_deployments(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
