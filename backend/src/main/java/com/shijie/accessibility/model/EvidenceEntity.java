package com.shijie.accessibility.model;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("finding_evidences")
public class EvidenceEntity {
    public String id;
    public String findingId;
    public String kind;
    public String summary;
    public String payload;
    public String artifactId;
    public LocalDateTime createdAt;
}

