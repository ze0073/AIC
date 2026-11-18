package com.shijie.accessibility.model;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("projects")
public class ProjectEntity {
    public String id;
    public String name;
    public String description;
    public String type;
    public String currentVersionId;
    public String deletionStatus;
    @com.baomidou.mybatisplus.annotation.TableField(exist=false) public String projectRole;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
}

