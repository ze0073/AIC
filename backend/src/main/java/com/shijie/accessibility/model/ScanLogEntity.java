package com.shijie.accessibility.model;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("scan_logs")
public class ScanLogEntity {
    public String id;
    public String scanTaskId;
    public String level;
    public String stage;
    public String message;
    public String details;
    public LocalDateTime createdAt;
}

