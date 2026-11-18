package com.shijie.accessibility.service;

import com.shijie.accessibility.common.ApiException;
import org.springframework.scheduling.support.CronExpression;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

final class RegressionSchedule {
    private RegressionSchedule() { }

    static LocalDateTime next(String expression) {
        try {
            ZonedDateTime next=CronExpression.parse(expression).next(ZonedDateTime.now(ZoneId.of("Asia/Shanghai")));
            if(next==null)throw new IllegalArgumentException("没有下一次执行时间");
            return next.toLocalDateTime();
        } catch(Exception ex) { throw ApiException.badRequest("INVALID_REGRESSION_CRON","回归计划执行时间格式无效"); }
    }
}
