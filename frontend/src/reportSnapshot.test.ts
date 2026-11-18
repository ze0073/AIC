import { beforeEach, describe, expect, it, vi } from "vitest";
import { request } from "./api";
import { latestOrCreateRunReportSnapshot } from "./reportSnapshot";

vi.mock("./api", () => ({
  request: vi.fn(),
  idempotencyKey: () => "new-key",
}));

const mockedRequest = vi.mocked(request);
const snapshot = (id: string, schemaVersion = "0.6.1") => ({
  id, schemaVersion, contentHash: `hash-${id}`, createdAt: "2026-09-25T12:00:00",
  jsonReady: false, pdfReady: false,
});

describe("run report export snapshot", () => {
  beforeEach(() => mockedRequest.mockReset());

  it("keeps the selected snapshot even if another member creates a newer one", async () => {
    mockedRequest.mockResolvedValueOnce([snapshot("first"), snapshot("older")]);
    mockedRequest.mockResolvedValueOnce([snapshot("other-member"), snapshot("first"), snapshot("older")]);
    expect((await latestOrCreateRunReportSnapshot("run-1")).id).toBe("first");
    expect((await latestOrCreateRunReportSnapshot("run-1", "first")).id).toBe("first");
    expect(mockedRequest).toHaveBeenCalledTimes(2);
    expect(mockedRequest).toHaveBeenCalledWith("/test-runs/run-1/report-snapshots");
  });

  it("creates a V0.6.1 snapshot if only legacy reports exist", async () => {
    mockedRequest.mockResolvedValueOnce([snapshot("legacy", "0.6.0")]);
    mockedRequest.mockResolvedValueOnce(snapshot("new"));
    expect((await latestOrCreateRunReportSnapshot("run-1")).id).toBe("new");
    expect(mockedRequest).toHaveBeenNthCalledWith(2, "/test-runs/run-1/report-snapshots", {
      method: "POST", headers: { "Idempotency-Key": "new-key" },
    });
  });
});
