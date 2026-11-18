import '@testing-library/jest-dom/vitest'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { App as AntApp } from 'antd'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { IssueRepairPage } from './IssueRepair'
import { downloadFile, request } from './api'

vi.mock('./api',()=>({request:vi.fn(),downloadFile:vi.fn().mockResolvedValue(undefined),idempotencyKey:()=> 'test-key'}))
beforeEach(()=>{
  vi.stubGlobal('ResizeObserver',class{observe(){}unobserve(){}disconnect(){}})
  vi.stubGlobal('matchMedia',vi.fn().mockImplementation(()=>({matches:false,addListener:vi.fn(),removeListener:vi.fn(),addEventListener:vi.fn(),removeEventListener:vi.fn()})))
})
afterEach(()=>{cleanup();vi.mocked(request).mockReset();vi.unstubAllGlobals()})
const workspace={issue:{id:'issue',projectId:'project',runId:'run',title:'页面语言缺失',source:'RULE'},versions:[],repairs:[],aiEnabled:true}
const pending={id:'repair',issueId:'issue',baseVersionId:'version',targetFile:'index.html',status:'PENDING',mode:'TEMPLATE',content:'<html lang="zh-CN">',diffText:'@@ -1 +1 @@\n-<html>\n+<html lang="zh-CN">',explanation:{summary:'补充页面语言',reason:'帮助识别语言',benefit:'改善发音',prevention:'发布前检查'}}
function setup(repair= pending){
  vi.mocked(request).mockImplementation(async(path)=>{
    if(path.includes('repair-workspace'))return workspace
    if(path.endsWith('/apply'))return {...repair,newVersionId:'fixed',status:'APPLIED'}
    if(path.includes('repair-files'))return []
    if(path==='/issue-repairs/repair')return repair
    throw new Error(`Unexpected request ${path}`)
  })
  render(<QueryClientProvider client={new QueryClient({defaultOptions:{queries:{retry:false}}})}><AntApp><MemoryRouter initialEntries={['/issues/issue/repair?repairId=repair']}><Routes><Route path="/issues/:issueId/repair" element={<IssueRepairPage/>}/></Routes></MemoryRouter></AntApp></QueryClientProvider>)
}

it('requires reviewing changes before applying real code and then offers the fixed source package',async()=>{
  setup()
  const apply=await screen.findByRole('button',{name:/确认应用修复/})
  expect(apply).toBeDisabled()
  for(const text of ['改了什么','为什么这样改','带来哪些好处','下次怎么避免'])expect(screen.getByText(text)).toBeInTheDocument()
  expect(screen.getByLabelText('修复前后代码差异').querySelector('.diff-added')).toHaveTextContent('+<html lang="zh-CN">')
  fireEvent.click(screen.getByRole('checkbox',{name:/我已检查差异/}))
  fireEvent.click(apply)
  const download=await screen.findByRole('button',{name:/下载修复后的源码/})
  fireEvent.click(download)
  await waitFor(()=>expect(downloadFile).toHaveBeenCalledWith('/issue-repairs/repair/download?inline=true','repaired-source-repair.zip'))
  expect(screen.getByText('已应用，待复测')).toBeInTheDocument()
  await waitFor(()=>expect(vi.mocked(request).mock.calls.find(([path])=>path.endsWith('/apply'))?.[1]?.body).toBe('{"reviewAccepted":true}'))
  expect(screen.getByRole('button',{name:/启动在线复测/})).toBeDisabled()
})

it('does not allow a partial retest to mark a repair verified',async()=>{
  setup({...pending,status:'APPLIED',newVersionId:'fixed',retest:{id:'retest',status:'PARTIAL',issues:[],modules:[]}} as typeof pending)
  expect(await screen.findByText('复测没有完整完成，暂不能确认修复')).toBeInTheDocument()
  expect(screen.getByRole('button',{name:/校验并记录修复结果/})).toBeDisabled()
  expect(vi.mocked(request).mock.calls.some(([path])=>path.endsWith('/verify'))).toBe(false)
})

it('rejects a saved patch for a different issue',async()=>{
  setup({...pending,issueId:'another'})
  expect(await screen.findByText('该补丁不属于当前问题')).toBeInTheDocument()
  expect(screen.queryByRole('button',{name:/确认应用修复/})).not.toBeInTheDocument()
})
