package com.shijie.accessibility.model;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("page_analysis_traces")
public class AnalysisTraceEntity {
    public String id;
    public String scanTaskId;
    public String pagePath;
    public String traceType;
    public String status;
    public String payload;
    public String artifactId;
    public LocalDateTime createdAt;
}
