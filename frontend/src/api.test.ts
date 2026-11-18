import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { downloadFile, request, resetCsrf } from "./api";

beforeEach(()=>{ resetCsrf(); vi.useFakeTimers(); });
afterEach(()=>{ vi.useRealTimers(); vi.unstubAllGlobals(); resetCsrf(); });

it("ends a timed request even when session establishment never responds and allows retry",async()=>{
  const fetchMock=vi.fn().mockImplementation(()=>new Promise(()=>{}));
  vi.stubGlobal("fetch",fetchMock);
  const pending=request("/issues/i/ai-explanation",{ method:"POST",timeoutMs:20 });
  const rejected=expect(pending).rejects.toMatchObject({ code:"REQUEST_TIMEOUT" });
  await vi.advanceTimersByTimeAsync(20);
  await rejected;
  fetchMock.mockImplementation((url:string)=>Promise.resolve(Response.json(url.endsWith("/csrf")?{headerName:"X-CSRF-TOKEN",token:"token"}:{mode:"RULE"})));
  expect(await request("/issues/i/ai-explanation?useAi=false",{ method:"POST",timeoutMs:20 })).toEqual({mode:"RULE"});
});

it("bounds the whole request including reading a stalled response body",async()=>{
  const fetchMock=vi.fn().mockResolvedValue({ ok:true,status:200,json:()=>new Promise(()=>{}) });
  vi.stubGlobal("fetch",fetchMock);
  const pending=request("/example",{ timeoutMs:20 });
  const rejected=expect(pending).rejects.toMatchObject({ code:"REQUEST_TIMEOUT" });
  await vi.advanceTimersByTimeAsync(20);
  await rejected;
  expect(fetchMock.mock.calls[0][1].signal.aborted).toBe(true);
});

it("cancels an outstanding request when the caller closes the view",async()=>{
  vi.stubGlobal("fetch",vi.fn().mockImplementation(()=>new Promise(()=>{})));
  const controller=new AbortController();
  const pending=request("/example",{ timeoutMs:20,signal:controller.signal });
  const rejected=expect(pending).rejects.toMatchObject({ code:"REQUEST_CANCELLED" });
  controller.abort();
  await rejected;
});

it("downloads the authenticated source as a blob without navigating away",async()=>{
  const fetchMock=vi.fn().mockResolvedValue({ok:true,status:200,blob:async()=>new Blob(['zip-bytes'])});
  vi.stubGlobal('fetch',fetchMock);
  const create=vi.fn().mockReturnValue('blob:repair'),revoke=vi.fn();
  const originalCreate=URL.createObjectURL,originalRevoke=URL.revokeObjectURL;
  URL.createObjectURL=create;URL.revokeObjectURL=revoke;
  const click=vi.spyOn(HTMLAnchorElement.prototype,'click').mockImplementation(function(this:HTMLAnchorElement){expect(this.href).toBe('blob:repair');expect(this.download).toBe('repair.zip')});
  try {
    await downloadFile('/issue-repairs/r/download','repair.zip');
    expect(fetchMock.mock.calls[0][1].credentials).toBe('same-origin');
    expect(click).toHaveBeenCalledOnce();
    await vi.advanceTimersByTimeAsync(1000);expect(revoke).toHaveBeenCalledWith('blob:repair');
  } finally {click.mockRestore();URL.createObjectURL=originalCreate;URL.revokeObjectURL=originalRevoke;}
});

it("shows download errors and bounds a stalled binary response",async()=>{
  const fetchMock=vi.fn().mockResolvedValueOnce({ok:false,status:403,json:async()=>({code:'FORBIDDEN',message:'无权访问源码'})}).mockResolvedValueOnce({ok:true,status:200,blob:()=>new Promise(()=>{})});
  vi.stubGlobal('fetch',fetchMock);
  await expect(downloadFile('/issue-repairs/r/download','repair.zip')).rejects.toMatchObject({code:'FORBIDDEN'});
  const pending=downloadFile('/issue-repairs/r/download','repair.zip');
  const rejected=expect(pending).rejects.toMatchObject({code:'DOWNLOAD_TIMEOUT'});
  await vi.advanceTimersByTimeAsync(60_000);await rejected;
});
