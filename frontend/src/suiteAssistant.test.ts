import {describe, expect, it} from "vitest";
import {isLocalDemoTarget, suiteAssistantPlan} from "./suiteAssistant";

describe("parameter assistant conservative templates", () => {
  it("uses the selected entry without guessing endpoints or authentication", () => {
    const plan = suiteAssistantPlan("ENTRY");
    expect(plan.pages).toBe("");
    expect(plan.performancePath).toBe("");
    expect(plan.apiCases).toEqual([]);
    expect(plan.flowSteps).toEqual([]);
    expect(plan.vus).toBe("1");
    expect(plan.ratePerSecond).toBe(2);
    expect(plan.stageDurationSeconds).toBe(20);
    expect(plan.p95Ms).toBe(1000);
    expect(plan.errorRatePercent).toBe(1);
    expect(JSON.stringify(plan)).not.toMatch(/credentialId|secret|token|cookie/i);
  });

  it("only adds the known read-only demo endpoint without active scanning", () => {
    const plan = suiteAssistantPlan("LOCAL_DEMO");
    expect(plan.performancePath).toBe("/api/slow");
    expect(plan.apiCases).toEqual([{name:"演示站健康接口",kind:"ASSERTION",method:"GET",
      path:"/api/health",expectedStatuses:"200",activeScan:false}]);
    expect(plan.flowSteps).toEqual([]);
    expect(plan.vus).toBe("1,2");
    expect(plan.ratePerSecond).toBe(2);
  });

  it("requires the exact demo origin and root scope", () => {
    expect(isLocalDemoTarget("http://127.0.0.1:8091/")).toBe(true);
    expect(isLocalDemoTarget("http://127.0.0.1:8091")).toBe(true);
    for (const url of ["https://127.0.0.1:8091/", "http://127.0.0.1:8091/app/",
      "http://127.0.0.1:80910/", "http://127.0.0.1:8091.evil.test/",
      "http://user:password@127.0.0.1:8091/", "http://127.0.0.1:8091/?secret=1", "invalid"])
      expect(isLocalDemoTarget(url)).toBe(false);
  });
});
