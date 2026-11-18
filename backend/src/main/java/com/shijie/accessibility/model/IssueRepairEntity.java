package com.shijie.accessibility.model;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("issue_repairs")
public class IssueRepairEntity {
    public String id;
    public String issueId;
    public String baseVersionId;
    public String newVersionId;
    public String targetFile;
    public String beforeSha;
    public String patchedContent;
    public String diffText;
    public String mode;
    public String status;
    public String explanation;
    public String staticScanId;
    public String retestRunId;
    public String hostedDeploymentError;
    public String createdBy;
    public LocalDateTime createdAt;
    public LocalDateTime appliedAt;
    public LocalDateTime updatedAt;
}
