package com.shijie.accessibility.model;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("project_review_policies")
public class ReviewPolicyEntity {
    public String id;
    public String projectId;
    public String matchKey;
    public String ruleId;
    public String pagePath;
    public String selector;
    public String evidenceFingerprint;
    public String decision;
    public String comment;
    public String sourceReviewId;
    public Boolean active;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
}
