package com.shijie.accessibility.model;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("findings")
public class FindingEntity {
    public String id;
    public String scanTaskId;
    public String ruleId;
    public String source;
    public String severity;
    public String status;
    public String title;
    public String description;
    public String pagePath;
    public String selector;
    public String fixMode;
    public String fingerprint;
    public String evidenceFingerprint;
    public LocalDateTime createdAt;
}
