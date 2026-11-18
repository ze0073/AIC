package com.shijie.accessibility.model;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("finding_reviews")
public class FindingReviewEntity {
    public String id;
    public String findingId;
    public String action;
    public String comment;
    public Boolean reused;
    public String sourcePolicyId;
    public LocalDateTime createdAt;
}
