import "@testing-library/jest-dom/vitest";
import { fireEvent, render, screen, waitFor, cleanup } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeAll, expect, it, vi } from "vitest";
import { IssueExplanationModal, type IssueExplanation } from "./IssueExplanation";
import { ApiError, request } from "./api";

vi.mock("./api",async()=>{ const actual=await vi.importActual<typeof import("./api")>("./api"); return {...actual,request:vi.fn()}; });
beforeAll(()=>{
  vi.stubGlobal("ResizeObserver",class { observe(){} unobserve(){} disconnect(){} });
  vi.stubGlobal("matchMedia",vi.fn().mockImplementation(()=>({ matches:false,addListener:vi.fn(),removeListener:vi.fn(),addEventListener:vi.fn(),removeEventListener:vi.fn() })));
});
afterEach(()=>{cleanup();vi.mocked(request).mockReset();});
const issue={ id:"i",title:"普通用户读取其他报名记录",source:"ZAP",severity:"HIGH",runId:"run" };
const result:IssueExplanation={ mode:"AI",phenomenon:"响应包含了不属于当前账号的记录。",possibleCauses:["可能缺少数据归属校验。"],evidenceBasis:["两个不同身份返回了相同记录。"],fixSuggestions:["为查询增加当前身份的数据范围限制。"],verificationSteps:["使用两个独立账号再次执行用例。"] };

it("opens immediately and can switch from a pending analysis to rule evidence while ignoring the old response",async()=>{
  let resolveOld!:(value:IssueExplanation)=>void;
  vi.mocked(request).mockImplementationOnce(()=>new Promise(resolve=>{resolveOld=resolve as typeof resolveOld;})).mockResolvedValueOnce({...result,mode:"RULE"});
  render(<MemoryRouter><IssueExplanationModal issue={issue} onClose={vi.fn()}/></MemoryRouter>);
  expect(screen.getByRole("dialog")).toBeInTheDocument();
  expect(screen.getByRole("status")).toHaveTextContent("正在整理原因与证据");
  fireEvent.click(screen.getByRole("button",{name:"先看规则解释"}));
  expect(await screen.findByText("基于检查规则")).toBeInTheDocument();
  expect(vi.mocked(request).mock.calls[0][1]?.signal?.aborted).toBe(true);
  expect(vi.mocked(request).mock.calls[1][0]).toBe("/issues/i/ai-explanation?useAi=false");
  expect(screen.getByRole("link",{name:/查看原始证据/})).toHaveAttribute("href","/test-runs/run");
  resolveOld({...result,phenomenon:"过期响应不应替换规则结果"});
  await waitFor(()=>expect(screen.queryByText("过期响应不应替换规则结果")).not.toBeInTheDocument());
  for(const item of [...result.possibleCauses,...result.evidenceBasis,...result.fixSuggestions,...result.verificationSteps]) expect(screen.getByText(item)).toBeInTheDocument();
});

it("shows a useful timeout message and returns to a readable result after retry",async()=>{
  vi.mocked(request).mockRejectedValueOnce(new ApiError("REQUEST_TIMEOUT","超时")).mockResolvedValueOnce(result);
  render(<MemoryRouter><IssueExplanationModal issue={issue} onClose={vi.fn()}/></MemoryRouter>);
  expect(await screen.findByText(/本次分析等待时间较长/)).toBeInTheDocument();
  fireEvent.click(screen.getByRole("button",{name:/重试分析/}));
  expect(await screen.findByText(result.phenomenon)).toBeInTheDocument();
  expect(screen.queryByRole("status")).not.toBeInTheDocument();
  expect(screen.getByRole("navigation",{name:"问题分析流程"})).toBeInTheDocument();
});

it("cancels pending work when the dialog closes",async()=>{
  vi.mocked(request).mockImplementation(()=>new Promise(()=>{}));
  const close=vi.fn();
  const view=render(<MemoryRouter><IssueExplanationModal issue={issue} onClose={close}/></MemoryRouter>);
  fireEvent.click(screen.getByRole("button",{name:"取消等待"}));
  expect(close).toHaveBeenCalledOnce();
  view.rerender(<MemoryRouter><IssueExplanationModal onClose={close}/></MemoryRouter>);
  expect(vi.mocked(request).mock.calls[0][1]?.signal?.aborted).toBe(true);
});
