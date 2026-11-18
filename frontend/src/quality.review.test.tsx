import "@testing-library/jest-dom/vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { App as AntApp, ConfigProvider } from "antd";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import { CollaborationPage } from "./quality";

beforeAll(() => {
  vi.stubGlobal("ResizeObserver", class { observe() {} unobserve() {} disconnect() {} });
  vi.stubGlobal("matchMedia", vi.fn().mockImplementation(() => ({ matches:false, addListener:vi.fn(), removeListener:vi.fn(), addEventListener:vi.fn(), removeEventListener:vi.fn() })));
});
afterEach(cleanup);

describe("run issue review link", () => {
  it("opens the requested issue directly in the review dialog", async () => {
    const client = new QueryClient({ defaultOptions:{ queries:{ retry:false, staleTime:Infinity } } });
    client.setQueryData(["work-issues", "p"], [{ id:"issue-1", title:"待复核问题", reviewStatus:"PENDING_REVIEW", taskStatus:"OPEN", source:"ZAP", runId:"run-1" }]);
    client.setQueryData(["knowledge-cases", "p", ""], []);
    client.setQueryData(["regression-plans", "p"], []);
    client.setQueryData(["targets", "p"], []);
    client.setQueryData(["test-suites", "p"], []);
    client.setQueryData(["members", "p"], []);
    client.setQueryData(["auth-me"], { id:"user-1" });
    client.setQueryData(["test-runs", "p"], []);
    client.setQueryData(["notifications", "p"], []);
    client.setQueryData(["issue-detail", "issue-1"], { history:[] });
    render(<QueryClientProvider client={client}><ConfigProvider><AntApp><MemoryRouter initialEntries={["/projects/p/collaboration?issueId=issue-1"]}>
      <Routes><Route path="/projects/:projectId/collaboration" element={<CollaborationPage />} /></Routes>
    </MemoryRouter></AntApp></ConfigProvider></QueryClientProvider>);
    expect(await screen.findByText("可选审阅 · 待复核问题")).toBeInTheDocument();
    expect(screen.getByRole("textbox", { name:"审阅理由（将记录操作者和历史）" })).toBeInTheDocument();
  });
});
