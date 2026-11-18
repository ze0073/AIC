package com.shijie.accessibility.model;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("scan_tasks")
public class ScanTaskEntity {
    public String id;
    public String projectId;
    public String versionId;
    public String status;
    public String stage;
    public Integer progress;
    public Integer foundCount;
    public Integer highCount;
    public Integer mediumCount;
    public Integer lowCount;
    public Integer reviewCount;
    public Integer riskScore;
    public String scanConfig;
    public String ruleVersion;
    public Boolean cancelRequested;
    public String createdBy;
    public String errorMessage;
    public LocalDateTime createdAt;
    public LocalDateTime startedAt;
    public LocalDateTime finishedAt;
}
