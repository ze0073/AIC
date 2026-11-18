import type { PerformanceAssessment } from "./types";

type EvidenceSeverity = "danger" | "warning";

export type EvidenceFlag = {
  path: string;
  key: string;
  value: unknown;
  severity: EvidenceSeverity;
  note: string;
};

const dangerousStatuses = new Set(["FAILED", "ERROR", "VULNERABLE", "UNSAFE", "DENIED", "TIMEOUT"]);
const warningStatuses = new Set(["PARTIAL", "INCONCLUSIVE", "SKIPPED", "CANCELLED", "PENDING_REVIEW"]);

function numberValue(value: unknown) {
  return typeof value === "number" && Number.isFinite(value) ? value : undefined;
}

function annotationFor(path: string, key: string, value: unknown, assessment?: PerformanceAssessment): Omit<EvidenceFlag, "path" | "key" | "value"> | undefined {
  const lowerPath = path.toLowerCase();
  const lowerKey = key.toLowerCase();
  const numeric = numberValue(value);
  const text = typeof value === "string" ? value.trim() : "";
  const upper = text.toUpperCase();

  if ((lowerPath.endsWith(".errorrate") || lowerPath.endsWith(".http_req_failed.value")) && numeric !== undefined) {
    const limit = assessment?.errorRateLimit ?? 0.01;
    if (numeric > limit) return { severity: "danger", note: `错误率 ${(numeric * 100).toFixed(2)}% 超过 ${(limit * 100).toFixed(2)}% 阈值` };
  }
  if ((lowerPath.endsWith(".p95ms") || lowerPath.endsWith(".p(95)")) && numeric !== undefined && assessment?.p95LimitMs !== undefined && numeric > assessment.p95LimitMs) {
    return { severity: "danger", note: `P95 ${numeric.toFixed(1)} ms 超过 ${assessment.p95LimitMs.toFixed(1)} ms 阈值` };
  }
  if (lowerKey === "exitcode" && numeric !== undefined && numeric !== 0) {
    return { severity: "danger", note: `检测工具以非零状态码 ${numeric} 结束` };
  }
  if (lowerKey === "status" && numeric !== undefined && numeric >= 400) {
    return { severity: numeric >= 500 ? "danger" : "warning", note: `HTTP ${numeric} 表示请求未按成功响应完成` };
  }
  if ((lowerKey === "failed" || lowerKey === "hasfailed" || lowerKey === "thresholdsfailed") && value === true) {
    return { severity: "danger", note: "此字段明确记录了失败状态" };
  }
  if (lowerKey === "attack" && text) {
    return { severity: "warning", note: "检测引擎记录了测试载荷，需要结合响应证据复核" };
  }
  if (dangerousStatuses.has(upper)) {
    return { severity: "danger", note: `状态为 ${text}，该步骤存在失败或风险` };
  }
  if (warningStatuses.has(upper)) {
    return { severity: "warning", note: `状态为 ${text}，结果不完整或仍需复核` };
  }
  if (text && /(failed|failure|error|exception|timeout|vulnerab|unsafe|denied|失败|异常|超时|漏洞|未通过|高错误率)/i.test(text)) {
    return { severity: "danger", note: "内容包含失败或风险信号" };
  }
  if (text && /(partial|inconclusive|skipped|cancelled|warning|risk|candidate|缺失|未完成|候选|风险|取消)/i.test(text)) {
    return { severity: "warning", note: "内容提示结果不完整、存在候选或需要复核" };
  }
  return undefined;
}

export function inspectEvidence(value: unknown, assessment?: PerformanceAssessment) {
  const flags: EvidenceFlag[] = [];
  const visit = (current: unknown, path: string) => {
    if (flags.length >= 30) return;
    if (Array.isArray(current)) {
      current.forEach((item, index) => visit(item, `${path}[${index}]`));
      return;
    }
    if (current && typeof current === "object") {
      Object.entries(current as Record<string, unknown>).forEach(([key, item]) => {
        const itemPath = path === "$" ? `$.${key}` : `${path}.${key}`;
        if (item && typeof item === "object") visit(item, itemPath);
        else {
          const annotation = annotationFor(itemPath, key, item, assessment);
          if (annotation) flags.push({ path: itemPath, key, value: item, ...annotation });
        }
      });
    }
  };
  visit(value, "$");
  return flags;
}
