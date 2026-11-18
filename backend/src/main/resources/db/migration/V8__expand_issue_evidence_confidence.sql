-- REPRODUCIBLE_ASSERTION exceeds the V5 VARCHAR(20) limit and prevented
-- real API/upload findings from being persisted.
ALTER TABLE run_issues MODIFY COLUMN evidence_confidence VARCHAR(32) NOT NULL;
