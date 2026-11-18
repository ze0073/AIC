import { idempotencyKey, request } from "./api";

export type RunReportSnapshot = {
  id: string;
  schemaVersion: string;
  contentHash: string;
  createdAt: string;
  jsonReady: boolean;
  pdfReady: boolean;
};

export function listRunReportSnapshots(runId: string) {
  return request<RunReportSnapshot[]>(`/test-runs/${runId}/report-snapshots`);
}

export async function createRunReportSnapshot(runId: string) {
  const snapshot=await request<RunReportSnapshot>(`/test-runs/${runId}/report-snapshots`, {
    method: "POST",
    headers: { "Idempotency-Key": idempotencyKey() },
  });
  window.dispatchEvent(new CustomEvent('guide-business-result',{detail:{runId,snapshotId:snapshot.id}}))
  return snapshot
}

export async function latestOrCreateRunReportSnapshot(runId: string, preferredId?: string) {
  const snapshots = await listRunReportSnapshots(runId);
  return snapshots.find((snapshot) => snapshot.id === preferredId && snapshot.schemaVersion === "0.6.1")
    ?? snapshots.find((snapshot) => snapshot.schemaVersion === "0.6.1")
    ?? createRunReportSnapshot(runId);
}
