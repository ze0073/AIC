import '@testing-library/jest-dom/vitest'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest'
import { ComparisonEvidence, QueryProblem, RepairNarrative, conclusion } from './workflow'
import type { Comparison } from './workflow'
beforeAll(()=>{
  vi.stubGlobal('ResizeObserver',class{observe(){}unobserve(){}disconnect(){}})
  vi.stubGlobal('matchMedia',vi.fn().mockImplementation(()=>({matches:false,addListener:vi.fn(),removeListener:vi.fn(),addEventListener:vi.fn(),removeEventListener:vi.fn()})))
})
afterEach(cleanup)
describe('workflow evidence',()=>{
  const data={before:{id:'a'},after:{id:'b'},pairs:[],evidences:{},traces:[{id:'t',scanTaskId:'a',pagePath:'index.html',artifactId:'image'}],patches:[]} as unknown as Comparison
  it('provides focusable labelled panes, scaling, sync and missing evidence text',()=>{
    render(<ComparisonEvidence data={data} page="index.html"/> )
    expect(screen.getByRole('region',{name:'原扫描截图'})).toHaveAttribute('tabindex','0')
    expect(screen.getByRole('region',{name:'复测截图'})).toHaveAttribute('tabindex','0')
    expect(screen.getByLabelText('证据缩放')).toBeInTheDocument()
    expect(screen.getByLabelText('同步滚动')).toBeChecked()
    fireEvent.click(screen.getByLabelText('同步滚动'))
    expect(screen.getByLabelText('同步滚动')).not.toBeChecked()
    expect(screen.getByText('此页面截图证据缺失')).toBeInTheDocument()
  })
  it('shows an explicit fallback for a missing screenshot file',()=>{
    render(<ComparisonEvidence data={data} page="index.html"/> )
    fireEvent.error(screen.getByRole('img',{name:'原扫描 index.html 完整页面证据'}))
    expect(screen.getAllByText('此页面截图证据缺失')).toHaveLength(2)
  })
  it('offers retry and keeps conclusion categories separate',()=>{
    const retry=vi.fn();render(<QueryProblem error={new Error('断线')} retry={retry}/>)
    fireEvent.click(screen.getByRole('button',{name:/重\s*试/}));expect(retry).toHaveBeenCalledOnce()
    expect(conclusion('DISMISSED')).toBe('已驳回');expect(conclusion('NEEDS_REVIEW')).toBe('自动候选');expect(conclusion('CONFIRMED')).toBe('已记录风险')
  })
  it('explains the saved patch and gives rule-specific recurrence prevention',()=>{
    const comparison={
      before:{id:'before'},after:{id:'after'},comparable:true,reasons:[],notRedetected:1,remaining:0,introduced:0,reviewChanged:0,
      verifiedFindingIds:['finding'],evidences:{},traces:[],sourceDom:{},
      pairs:[{kind:'NOT_REDETECTED',reviewChanged:false,evidenceChanged:false,before:{id:'finding',ruleId:'R001',title:'图片缺少替代文本',description:'屏幕阅读器无法获知图片内容',pagePath:'index.html',selector:'#hero'}}],
      patches:[{id:'patch',findingId:'finding',baseVersionId:'v1',newVersionId:'v2',targetFile:'index.html',diffText:'--- a/index.html\n+++ b/index.html\n-<img>\n+<img alt="校园风景">',patchType:'TEMPLATE',decorative:false,status:'APPLIED',verification:'通过'}],
    } as unknown as Comparison
    render(<RepairNarrative data={comparison}/>)
    expect(screen.getByText('这次修复发生了什么')).toBeInTheDocument()
    expect(screen.getByText(/新增 1 行、移除 1 行/)).toBeInTheDocument()
    expect(screen.getByText(/图片替代文本设为组件必填属性/)).toBeInTheDocument()
    expect(screen.getByText(/1 项原问题未再检出/)).toBeInTheDocument()
  })
})
