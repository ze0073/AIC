package com.shijie.accessibility.model;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("project_versions")
public class ProjectVersionEntity {
    public String id;
    public String projectId;
    public String baseVersionId;
    public Integer versionNo;
    public String sourceType;
    public String storagePath;
    public String entryPage;
    public String manifestHash;
    public LocalDateTime createdAt;
}

