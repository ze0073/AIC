package com.shijie.accessibility.common;

import java.util.Map;

/** Derived interpretation only: never changes stored measurements or historical snapshots. */
public record PerformanceAssessment(String status, String reason, boolean latencyAvailable,
                                    boolean allRequestsFailed, boolean thresholdsFailed,
                                    Double requestCount, Double errorRate, Double p95Ms,
                                    double p95LimitMs, double errorRateLimit) {
    public static PerformanceAssessment evaluate(Map<String,Object> summary, Map<String,Object> config, String executionStatus) {
        Double count=number(values(summary,"http_reqs").get("count"));
        Map<?,?> failures=values(summary,"http_req_failed");
        Double error=number(failures.containsKey("rate")?failures.get("rate"):failures.get("value"));
        Map<?,?> duration=values(summary,"http_req_duration");
        Double median=number(duration.get("med")),p95=number(duration.get("p(95)")),p99=number(duration.get("p(99)"));
        double p95Limit=limit(config.get("p95Ms"),1000),errorLimit=limit(config.get("errorRate"),.01);
        boolean requests=count!=null&&count>0;
        boolean validError=error!=null&&error>=0&&error<=1;
        boolean allFailed=requests&&validError&&error==1;
        boolean latency=requests&&validError&&!allFailed&&median!=null&&median>=0&&p95!=null&&p95>=0&&p99!=null&&p99>=0;
        boolean exceeded=thresholdsFailed(summary)||(requests&&validError&&error>=errorLimit)||(latency&&p95>p95Limit);
        String status,reason;
        if(!"COMPLETED".equals(executionStatus)){status="INCONCLUSIVE";reason="检测未完整执行；已取得的指标保留，但不能判定性能通过";}
        else if(allFailed){status="FAILED";reason="所有请求均失败，未取得有效响应耗时；不能判定性能通过";}
        else if(!latency){status="INCONCLUSIVE";reason="有效请求数、错误率或耗时指标缺失，不能判定性能通过";}
        else if(exceeded){status="FAILED";reason="性能验收阈值未通过；执行结束不等于测试通过";}
        else{status="PASSED";reason="本次负载配置下的 P95 与错误率阈值通过，不代表其他负载或全面性能保证";}
        return new PerformanceAssessment(status,reason,latency,allFailed,exceeded,count,error,latency?p95:null,p95Limit,errorLimit);
    }

    // Legacy --summary-export stores `hasFailed`; handleSummary stores {ok: ...}.
    public static boolean thresholdsFailed(Map<String,Object> summary) {
        if(!(summary.get("metrics") instanceof Map<?,?> metrics))return false;
        for(Object raw:metrics.values())if(raw instanceof Map<?,?> metric&&metric.get("thresholds") instanceof Map<?,?> thresholds)
            for(Object threshold:thresholds.values())
                if(Boolean.TRUE.equals(threshold)||(threshold instanceof Map<?,?> result&&Boolean.FALSE.equals(result.get("ok"))))return true;
        return false;
    }
    public static Map<?,?> values(Map<String,Object> summary,String name) {
        if(!(summary.get("metrics") instanceof Map<?,?> metrics)||!(metrics.get(name) instanceof Map<?,?> metric))return Map.of();
        return metric.get("values") instanceof Map<?,?> nested?nested:metric;
    }
    private static Double number(Object value){return value instanceof Number n&&Double.isFinite(n.doubleValue())?n.doubleValue():null;}
    private static double limit(Object value,double fallback){Double number=number(value);return number!=null&&number>0?number:fallback;}
}
