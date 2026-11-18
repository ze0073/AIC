package com.shijie.accessibility.model;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("stored_artifacts")
public class ArtifactEntity {
    public String id;
    public String projectId;
    public String versionId;
    public String scanTaskId;
    public String kind;
    public String relativePath;
    public String sha256;
    public Long sizeBytes;
    public LocalDateTime createdAt;
}

