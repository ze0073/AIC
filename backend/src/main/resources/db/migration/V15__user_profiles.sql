CREATE TABLE user_profiles (
    user_id CHAR(26) PRIMARY KEY,
    email VARCHAR(254) NOT NULL DEFAULT '',
    phone VARCHAR(32) NOT NULL DEFAULT '',
    bio VARCHAR(500) NOT NULL DEFAULT '',
    avatar_png MEDIUMBLOB,
    avatar_version CHAR(36),
    CONSTRAINT fk_user_profiles_user FOREIGN KEY (user_id) REFERENCES app_users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
