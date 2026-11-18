ALTER TABLE agent_tasks
    ADD COLUMN mode VARCHAR(10) NOT NULL DEFAULT 'QWEN' AFTER goal,
    ADD COLUMN baseline_task_id CHAR(26) NULL AFTER mode,
    ADD INDEX idx_agent_baseline_lookup (project_id, target_id, suite_version_id, status),
    ADD CONSTRAINT fk_agent_tasks_baseline FOREIGN KEY (baseline_task_id) REFERENCES agent_tasks(id) ON DELETE SET NULL;
