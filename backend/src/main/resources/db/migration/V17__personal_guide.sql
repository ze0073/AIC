CREATE TABLE guide_preferences (
    user_id CHAR(26) PRIMARY KEY,
    welcome_seen BOOLEAN NOT NULL DEFAULT FALSE,
    character_visible BOOLEAN NOT NULL DEFAULT TRUE,
    voice_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at DATETIME(3) NOT NULL,
    CONSTRAINT fk_guide_preferences_user FOREIGN KEY (user_id) REFERENCES app_users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE guide_sessions (
    id CHAR(26) PRIMARY KEY,
    owner_id CHAR(26) NOT NULL,
    goal VARCHAR(20) NOT NULL,
    assistance VARCHAR(10) NOT NULL DEFAULT 'GUIDED',
    mode VARCHAR(10) NOT NULL DEFAULT 'RULE',
    ai_consent BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    context_json JSON NOT NULL,
    draft_json JSON NOT NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    INDEX idx_guide_sessions_owner (owner_id, updated_at),
    CONSTRAINT fk_guide_sessions_owner FOREIGN KEY (owner_id) REFERENCES app_users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE guide_messages (
    id CHAR(26) PRIMARY KEY,
    session_id CHAR(26) NOT NULL,
    role VARCHAR(20) NOT NULL,
    content TEXT NOT NULL,
    created_at DATETIME(3) NOT NULL,
    INDEX idx_guide_messages_session (session_id, created_at, id),
    CONSTRAINT fk_guide_messages_session FOREIGN KEY (session_id) REFERENCES guide_sessions(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE guide_actions (
    id CHAR(26) PRIMARY KEY,
    session_id CHAR(26) NOT NULL,
    action_type VARCHAR(40) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PROPOSED',
    arguments_json JSON NOT NULL,
    preview_json JSON NOT NULL,
    action_hash CHAR(64) NOT NULL,
    guard_hash CHAR(64) NOT NULL,
    receipt_json JSON,
    error_message VARCHAR(1000),
    expires_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    finished_at DATETIME(3),
    INDEX idx_guide_actions_session (session_id, created_at, id),
    CONSTRAINT fk_guide_actions_session FOREIGN KEY (session_id) REFERENCES guide_sessions(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
