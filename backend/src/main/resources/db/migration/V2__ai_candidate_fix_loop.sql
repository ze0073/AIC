ALTER TABLE fix_patches
    ADD COLUMN patch_type VARCHAR(30) NOT NULL DEFAULT 'TEMPLATE' AFTER diff_text,
    ADD COLUMN candidate_text VARCHAR(500) NULL AFTER patch_type,
    ADD COLUMN decorative BOOLEAN NOT NULL DEFAULT FALSE AFTER candidate_text,
    ADD COLUMN source_evidence_id CHAR(26) NULL AFTER decorative,
    ADD COLUMN source_provider VARCHAR(50) NULL AFTER source_evidence_id,
    ADD COLUMN source_model VARCHAR(120) NULL AFTER source_provider,
    ADD COLUMN reviewed_at DATETIME(3) NULL AFTER source_model,
    ADD INDEX idx_patch_source_evidence (source_evidence_id),
    ADD CONSTRAINT fk_patch_source_evidence FOREIGN KEY (source_evidence_id)
        REFERENCES finding_evidences(id) ON DELETE SET NULL;
