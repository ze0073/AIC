package com.shijie.accessibility.model;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("fix_patches")
public class FixPatchEntity {
    public String id;
    public String findingId;
    public String baseVersionId;
    public String newVersionId;
    public String targetFile;
    public String beforeSha;
    public String patchedContent;
    public String diffText;
    public String patchType;
    public String candidateText;
    public Boolean decorative;
    public String sourceEvidenceId;
    public String sourceProvider;
    public String sourceModel;
    public LocalDateTime reviewedAt;
    public String status;
    public String verification;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
}
