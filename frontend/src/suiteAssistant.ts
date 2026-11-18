export type SuiteAssistantKind = "ENTRY" | "LOCAL_DEMO";

export function isLocalDemoTarget(baseUrl: string): boolean {
  try {
    const url = new URL(baseUrl);
    return url.origin === "http://127.0.0.1:8091" && url.pathname === "/"
      && !url.username && !url.password && !url.search && !url.hash;
  } catch {
    return false;
  }
}

// These are conservative starting templates, not model output or measured baselines.
export function suiteAssistantPlan(kind: SuiteAssistantKind) {
  const demo = kind === "LOCAL_DEMO";
  return {
    name: demo ? "本机演示低负载检查" : "首次低负载检查",
    description: "参数配置助手的规则模板；阈值是起始参考，尚未按实际基线校准。",
    pages: "",
    performancePath: demo ? "/api/slow" : "",
    vus: demo ? "1,2" : "1",
    stageDurationSeconds: 20,
    ratePerSecond: 2,
    p95Ms: 1000,
    errorRatePercent: 1,
    apiCases: demo ? [{name: "演示站健康接口", kind: "ASSERTION", method: "GET",
      path: "/api/health", expectedStatuses: "200", activeScan: false}] : [],
    flowSteps: [],
  };
}
