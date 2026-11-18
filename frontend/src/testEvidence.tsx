import { Alert, Tag, Typography } from "antd";
import { inspectEvidence, type EvidenceFlag } from "./evidenceInspection";
import type { PerformanceAssessment } from "./types";

function numberValue(value: unknown) {
  return typeof value === "number" && Number.isFinite(value) ? value : undefined;
}

function shortValue(value: unknown) {
  const text = typeof value === "string" ? value : JSON.stringify(value);
  return text.length > 180 ? `${text.slice(0, 180)}…` : text;
}

function parseEvidence(raw: string) {
  try {
    const value = JSON.parse(raw) as unknown;
    return { value, formatted: JSON.stringify(value, null, 2) };
  } catch {
    return { value: raw, formatted: raw };
  }
}

function lineFlag(line: string, flags: EvidenceFlag[]) {
  const keyMatch = line.match(/^\s*"([^"]+)"\s*:/);
  if (!keyMatch) return undefined;
  return flags.find((flag) => {
    if (flag.key !== keyMatch[1]) return false;
    const literal = JSON.stringify(flag.value);
    return literal === undefined || line.includes(literal);
  });
}

export function AnnotatedEvidence({ raw, performanceAssessment }: { raw: string; performanceAssessment?: PerformanceAssessment }) {
  const parsed = parseEvidence(raw);
  const flags = typeof parsed.value === "string" ? [] : inspectEvidence(parsed.value, performanceAssessment);
  const lines = parsed.formatted.split("\n");
  return <div className="evidence-inspector">
    <div className="evidence-inspector-head">
      <div>
        <Typography.Text strong>证据标注</Typography.Text>
        <Typography.Text type="secondary"> 系统按执行状态与本次阈值标出需要关注的字段</Typography.Text>
      </div>
      <Tag color={flags.some(flag => flag.severity === "danger") ? "red" : flags.length ? "orange" : "green"}>
        {flags.length ? `${flags.length} 处需关注` : "未发现明确异常字段"}
      </Tag>
    </div>
    {flags.length > 0 && <div className="evidence-annotations" aria-label="证据问题注释">
      {flags.map((flag, index) => <div className={`evidence-annotation evidence-annotation--${flag.severity}`} key={`${flag.path}-${index}`}>
        <code>{flag.path}</code>
        <span className="evidence-annotation-value">{shortValue(flag.value)}</span>
        <span>{flag.note}</span>
      </div>)}
    </div>}
    {flags.length === 0 && <Alert type="success" showIcon title="当前规则没有识别出明确异常字段" description="仍可展开核对完整原始快照；未标出不代表不存在业务问题。" />}
    <pre className="json-view evidence-json" aria-label="带注释的原始 JSON 证据">{lines.map((line, index) => {
      const flag = lineFlag(line, flags);
      return <span className={flag ? `evidence-line evidence-line--${flag.severity}` : "evidence-line"} title={flag?.note} key={index}>
        <span className="evidence-line-number" aria-hidden="true">{String(index + 1).padStart(2, "0")}</span>
        <span>{line || " "}</span>
        {flag && <span className="evidence-inline-note">← {flag.note}</span>}
        {index < lines.length - 1 ? "\n" : ""}
      </span>;
    })}</pre>
  </div>;
}

const chartColors = ["#167f77", "#2f6fa3", "#d38835"];

export function FrontendMetricChart({ measurements }: { measurements: Record<string, unknown>[] }) {
  const rows = measurements.slice(0, 10).map((item, index) => ({
    label: String(item.pageUrl || `样本 ${index + 1}`).replace(/^https?:\/\//, "").slice(0, 20),
    values: [numberValue(item.ttfbMs), numberValue(item.navigationMs), numberValue(item.lcpMs)],
  })).filter(row => row.values.some(value => value !== undefined));
  if (!rows.length) return null;
  const width = 760, height = 285, left = 58, right = 24, top = 36, bottom = 58;
  const values = rows.flatMap(row => row.values).filter((value): value is number => value !== undefined);
  const max = Math.max(1, ...values) * 1.12;
  const plotWidth = width - left - right, plotHeight = height - top - bottom;
  const groupWidth = plotWidth / rows.length, barWidth = Math.max(3, Math.min(16, groupWidth / 4));
  return <figure className="test-data-chart">
    <figcaption><strong>页面耗时图谱</strong><span>同一纵轴，单位 ms；最多展示前 10 个样本</span></figcaption>
    <div className="chart-scroll"><svg viewBox={`0 0 ${width} ${height}`} role="img" aria-label="页面 TTFB、导航耗时和 LCP 柱状图">
      {[0, .25, .5, .75, 1].map(part => {
        const y = top + plotHeight * (1 - part);
        return <g key={part}><line x1={left} x2={width - right} y1={y} y2={y} className="chart-grid"/><text x={left - 8} y={y + 4} textAnchor="end" className="chart-axis-label">{Math.round(max * part)}</text></g>;
      })}
      {rows.map((row, rowIndex) => {
        const center = left + groupWidth * rowIndex + groupWidth / 2;
        return <g key={`${row.label}-${rowIndex}`}>{row.values.map((value, valueIndex) => {
          if (value === undefined) return null;
          const barHeight = value / max * plotHeight;
          const x = center + (valueIndex - 1) * (barWidth + 2) - barWidth / 2;
          return <rect key={valueIndex} x={x} y={top + plotHeight - barHeight} width={barWidth} height={barHeight} rx="2" fill={chartColors[valueIndex]}><title>{`${row.label} · ${["TTFB", "导航耗时", "LCP"][valueIndex]} ${value.toFixed(1)} ms`}</title></rect>;
        })}<text x={center} y={height - 33} textAnchor="middle" className="chart-axis-label">{rowIndex + 1}</text></g>;
      })}
      <line x1={left} x2={width - right} y1={top + plotHeight} y2={top + plotHeight} className="chart-axis"/>
      <text x={left} y={height - 10} className="chart-footnote">样本编号对应下方数据表</text>
    </svg></div>
    <div className="chart-legend">{["TTFB", "导航耗时", "LCP"].map((name, index) => <span key={name}><i style={{ background: chartColors[index] }}/>{name}</span>)}</div>
  </figure>;
}

type SecurityCategory = "passed" | "failed" | "review";
const failedSecurityStatuses = new Set(["FAILED", "ERROR", "VULNERABLE", "UNSAFE", "DENIED", "TIMEOUT"]);

function securityCategory(status: unknown): SecurityCategory {
  const value = String(status || "UNKNOWN").toUpperCase();
  if (["PASSED", "PASS", "SUCCESS", "OK", "COMPLETED", "SECURE"].includes(value)) return "passed";
  if (failedSecurityStatuses.has(value) || ["FAIL", "FOUND", "EXPOSED"].includes(value)) return "failed";
  return "review";
}

export function SecurityResultChart({ cases }: { cases: Record<string, unknown>[] }) {
  if (!cases.length) return null;
  const counts = cases.reduce<Record<SecurityCategory, number>>((result, item) => {
    result[securityCategory(item.status)] += 1;
    return result;
  }, { passed: 0, failed: 0, review: 0 });
  const width = 760, total = cases.length;
  const parts = [
    { key: "passed", label: "通过", value: counts.passed, color: "#3f9b70" },
    { key: "review", label: "需复核", value: counts.review, color: "#d5a13f" },
    { key: "failed", label: "异常", value: counts.failed, color: "#d45b54" },
  ];
  let offset = 0;
  return <figure className="test-data-chart test-data-chart--compact">
    <figcaption><strong>安全用例结果图谱</strong><span>{total} 项只读检查的结果分布</span></figcaption>
    <svg viewBox={`0 0 ${width} 86`} role="img" aria-label={`安全用例结果：通过 ${counts.passed}，需复核 ${counts.review}，异常 ${counts.failed}`}>
      {parts.map(part => {
        const partWidth = part.value / total * (width - 20);
        const x = 10 + offset;
        offset += partWidth;
        return partWidth ? <rect key={part.key} x={x} y="18" width={partWidth} height="34" fill={part.color}><title>{`${part.label} ${part.value} 项`}</title></rect> : null;
      })}
    </svg>
    <div className="chart-legend">{parts.map(part => <span key={part.key}><i style={{ background: part.color }}/>{part.label} {part.value}</span>)}</div>
  </figure>;
}

export function PerformanceTrendChart({ points, assessment }: { points: Record<string, unknown>[]; assessment?: PerformanceAssessment }) {
  const rows = points.map((point, index) => ({
    index,
    second: numberValue(point.windowStartSecond) ?? index,
    stage: String(point.stage ?? "—"),
    p50: numberValue(point.p50Ms),
    p95: numberValue(point.p95Ms),
    p99: numberValue(point.p99Ms),
    errorRate: numberValue(point.errorRate),
  }));
  if (!rows.length) return null;
  const width = 760, height = 330, left = 58, right = 26, top = 34, latencyBottom = 220, errorTop = 252, errorHeight = 42;
  const validLatency = rows.flatMap(row => [row.p50, row.p95, row.p99]).filter((value): value is number => value !== undefined);
  const maxLatency = Math.max(1, assessment?.p95LimitMs || 0, ...validLatency) * 1.12;
  const errorLimit = assessment?.errorRateLimit ?? .01;
  const errorMax = Math.max(errorLimit * 1.25, ...rows.map(row => row.errorRate || 0), .01) * 1.12;
  const x = (index: number) => left + (rows.length === 1 ? (width - left - right) / 2 : index / (rows.length - 1) * (width - left - right));
  const y = (value: number) => top + (1 - value / maxLatency) * (latencyBottom - top);
  const linePoints = (key: "p50" | "p95" | "p99") => rows.filter(row => row[key] !== undefined && row.errorRate !== 1).map(row => `${x(row.index)},${y(row[key]!)}`).join(" ");
  const latencyLines = [
    { key: "p50" as const, label: "P50", color: chartColors[0] },
    { key: "p95" as const, label: "P95", color: chartColors[1] },
    { key: "p99" as const, label: "P99", color: chartColors[2] },
  ];
  return <figure className="test-data-chart">
    <figcaption><strong>性能时间序列图谱</strong><span>上方为响应耗时，下方为每个窗口错误率</span></figcaption>
    <div className="chart-scroll"><svg viewBox={`0 0 ${width} ${height}`} role="img" aria-label="性能 P50、P95、P99 和错误率时间序列图">
      {[0, .25, .5, .75, 1].map(part => {
        const chartY = top + (latencyBottom - top) * (1 - part);
        return <g key={part}><line x1={left} x2={width - right} y1={chartY} y2={chartY} className="chart-grid"/><text x={left - 8} y={chartY + 4} textAnchor="end" className="chart-axis-label">{Math.round(maxLatency * part)}</text></g>;
      })}
      {assessment?.p95LimitMs !== undefined && <g><line x1={left} x2={width - right} y1={y(assessment.p95LimitMs)} y2={y(assessment.p95LimitMs)} className="chart-threshold"/><text x={width - right} y={y(assessment.p95LimitMs) - 5} textAnchor="end" className="chart-threshold-label">P95 阈值 {assessment.p95LimitMs} ms</text></g>}
      {latencyLines.map(line => <polyline key={line.key} points={linePoints(line.key)} fill="none" stroke={line.color} strokeWidth="3" strokeLinejoin="round" strokeLinecap="round"/>)}
      <line x1={left} x2={width - right} y1={errorTop + errorHeight * (1 - errorLimit / errorMax)} y2={errorTop + errorHeight * (1 - errorLimit / errorMax)} className="chart-error-threshold"/>
      <text x={width - right - 8} y={errorTop + errorHeight * (1 - errorLimit / errorMax) - 4} textAnchor="end" className="chart-threshold-label">错误率阈值 {(errorLimit * 100).toFixed(1)}%</text>
      {rows.map(row => <g key={row.index}>{latencyLines.map(line => row[line.key] !== undefined && row.errorRate !== 1 ? <circle key={line.key} cx={x(row.index)} cy={y(row[line.key]!)} r="3.5" fill={line.color}><title>{`第 ${row.second} 秒 · 阶段 ${row.stage} · ${line.label} ${row[line.key]!.toFixed(1)} ms`}</title></circle> : null)}
        <rect x={x(row.index) - Math.max(5, Math.min(18, 260 / rows.length)) / 2} y={errorTop + errorHeight * (1 - Math.min(1, (row.errorRate || 0) / errorMax))} width={Math.max(5, Math.min(18, 260 / rows.length))} height={errorHeight * Math.min(1, (row.errorRate || 0) / errorMax)} fill={(row.errorRate || 0) > errorLimit ? "#d45b54" : "#d5a13f"}><title>{`第 ${row.second} 秒 · 错误率 ${((row.errorRate || 0) * 100).toFixed(2)}%`}</title></rect>
        <text x={x(row.index)} y={height - 17} textAnchor="middle" className="chart-axis-label">{row.second}</text></g>)}
      <text x={12} y={top + 8} className="chart-axis-title">ms</text><text x={12} y={errorTop + 12} className="chart-axis-title">错误率</text>
    </svg></div>
    <div className="chart-legend">{latencyLines.map(line => <span key={line.key}><i style={{ background: line.color }}/>{line.label}</span>)}<span><i style={{ background: "#d45b54" }}/>错误率超阈值</span></div>
  </figure>;
}
