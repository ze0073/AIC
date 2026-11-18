CREATE TABLE project_assistant_sessions (
    id CHAR(26) PRIMARY KEY,
    project_id CHAR(26) NOT NULL,
    created_by CHAR(26),
    mode VARCHAR(10) NOT NULL,
    ai_consent BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    title VARCHAR(160) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    INDEX idx_assistant_sessions_project (project_id, updated_at),
    CONSTRAINT fk_assistant_sessions_project FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE,
    CONSTRAINT fk_assistant_sessions_creator FOREIGN KEY (created_by) REFERENCES app_users(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE project_assistant_messages (
    id CHAR(26) PRIMARY KEY,
    session_id CHAR(26) NOT NULL,
    seq_no INT NOT NULL,
    role VARCHAR(20) NOT NULL,
    content TEXT NOT NULL,
    metadata_json JSON NOT NULL,
    created_at DATETIME(3) NOT NULL,
    UNIQUE KEY uk_assistant_message_seq (session_id, seq_no),
    CONSTRAINT fk_assistant_messages_session FOREIGN KEY (session_id) REFERENCES project_assistant_sessions(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE project_assistant_actions (
    id CHAR(26) PRIMARY KEY,
    session_id CHAR(26) NOT NULL,
    source_message_id CHAR(26),
    action_type VARCHAR(40) NOT NULL,
    required_role VARCHAR(20) NOT NULL,
    status VARCHAR(30) NOT NULL,
    arguments_json JSON NOT NULL,
    preview_json JSON NOT NULL,
    action_hash CHAR(64) NOT NULL,
    expires_at DATETIME(3) NOT NULL,
    proposed_by CHAR(26),
    confirmed_by CHAR(26),
    resource_type VARCHAR(40),
    resource_id CHAR(26),
    receipt_json JSON,
    error_message VARCHAR(1000),
    created_at DATETIME(3) NOT NULL,
    confirmed_at DATETIME(3),
    finished_at DATETIME(3),
    INDEX idx_assistant_actions_session (session_id, created_at),
    INDEX idx_assistant_actions_status (status, expires_at),
    CONSTRAINT fk_assistant_actions_session FOREIGN KEY (session_id) REFERENCES project_assistant_sessions(id) ON DELETE CASCADE,
    CONSTRAINT fk_assistant_actions_message FOREIGN KEY (source_message_id) REFERENCES project_assistant_messages(id) ON DELETE SET NULL,
    CONSTRAINT fk_assistant_actions_proposer FOREIGN KEY (proposed_by) REFERENCES app_users(id) ON DELETE SET NULL,
    CONSTRAINT fk_assistant_actions_confirmer FOREIGN KEY (confirmed_by) REFERENCES app_users(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
