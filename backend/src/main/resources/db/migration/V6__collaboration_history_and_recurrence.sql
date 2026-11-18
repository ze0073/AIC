ALTER TABLE run_issues
    ADD COLUMN retest_run_id CHAR(26),
    ADD COLUMN recurrence_of_issue_id CHAR(26),
    ADD CONSTRAINT fk_run_issues_retest_run FOREIGN KEY (retest_run_id) REFERENCES test_runs(id) ON DELETE SET NULL,
    ADD CONSTRAINT fk_run_issues_recurrence FOREIGN KEY (recurrence_of_issue_id) REFERENCES run_issues(id) ON DELETE SET NULL;

CREATE TABLE issue_history (
    id CHAR(26) PRIMARY KEY,
    issue_id CHAR(26) NOT NULL,
    user_id CHAR(26),
    action VARCHAR(40) NOT NULL,
    from_status VARCHAR(30),
    to_status VARCHAR(30),
    details JSON,
    created_at DATETIME(3) NOT NULL,
    INDEX idx_issue_history_issue (issue_id, created_at),
    CONSTRAINT fk_issue_history_issue FOREIGN KEY (issue_id) REFERENCES run_issues(id) ON DELETE CASCADE,
    CONSTRAINT fk_issue_history_user FOREIGN KEY (user_id) REFERENCES app_users(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
