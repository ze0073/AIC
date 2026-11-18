import '@testing-library/jest-dom/vitest'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { ConfigProvider } from 'antd'
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest'
import { AiCandidateEditor, InteractionEvidence, RiskScoreNotice } from './App'
import type { AnalysisTrace } from './types'

beforeAll(() => {
  vi.stubGlobal('ResizeObserver', class {
    observe() {}
    unobserve() {}
    disconnect() {}
  })
  vi.stubGlobal('matchMedia', vi.fn().mockImplementation((query:string) => ({
    matches:false, media:query, onchange:null,
    addListener:vi.fn(), removeListener:vi.fn(), addEventListener:vi.fn(), removeEventListener:vi.fn(), dispatchEvent:vi.fn(),
  })))
})

afterEach(cleanup)

describe('RiskScoreNotice', () => {
  it('never presents the internal score as a WCAG conformance score', () => {
    render(<ConfigProvider><RiskScoreNotice /></ConfigProvider>)
    expect(screen.getByText('内部风险分，不是 WCAG 合规评分')).toBeInTheDocument()
  })
})

describe('AiCandidateEditor', () => {
  it('allows a reviewer to edit and submit an image alt candidate', () => {
    const submit = vi.fn()
    render(<ConfigProvider><AiCandidateEditor ruleId="R001" initialText="旧候选" onGenerate={submit} /></ConfigProvider>)

    fireEvent.change(screen.getByLabelText('图片替代文本'), { target: { value: '校园图书馆入口' } })
    fireEvent.click(screen.getByRole('button', { name: '生成并审阅代码 Diff' }))

    expect(submit).toHaveBeenCalledWith('校园图书馆入口', false)
  })

  it('requires an explicit decorative-image choice before creating an empty alt', () => {
    const submit = vi.fn()
    render(<ConfigProvider><AiCandidateEditor ruleId="R001" initialText="" onGenerate={submit} /></ConfigProvider>)

    expect(screen.getByRole('button', { name: '生成并审阅代码 Diff' })).toBeDisabled()
    fireEvent.click(screen.getByRole('checkbox', { name: '这是装饰性图片，使用空 alt' }))
    fireEvent.click(screen.getByRole('button', { name: '生成并审阅代码 Diff' }))

    expect(submit).toHaveBeenCalledWith('', true)
  })
})

describe('InteractionEvidence', () => {
  it('shows numbered forward and reverse focus paths with text alternatives', () => {
    const trace:AnalysisTrace={id:'trace-1',scanTaskId:'scan-1',pagePath:'index.html',traceType:'KEYBOARD_FOCUS',status:'COMPLETE',artifactId:'artifact-1',createdAt:'2026-09-18',payload:{maxSteps:30,cycleDetected:false,truncated:false,noFocusTarget:false,forwardSteps:[{index:1,selector:'#search',accessibleName:'搜索',focusIndicator:'VISIBLE',boundingBox:{x:20,y:30,width:100,height:32}}],reverseSteps:[{index:1,selector:'#submit',accessibleName:'提交',focusIndicator:'POSSIBLY_HIDDEN',boundingBox:{x:20,y:90,width:100,height:32}}]}}
    render(<ConfigProvider><InteractionEvidence trace={trace}/></ConfigProvider>)
    expect(screen.getByText('搜索')).toBeInTheDocument()
    expect(screen.getByLabelText('序号 1：搜索')).toBeInTheDocument()
    fireEvent.click(screen.getByText('反向 Shift+Tab'))
    expect(screen.getByText('提交')).toBeInTheDocument()
    expect(screen.getByText('疑似不可见')).toBeInTheDocument()
  })

  it('switches between DOM and visual sequences and explains conflicts without color alone', () => {
    const trace:AnalysisTrace={id:'trace-2',scanTaskId:'scan-1',pagePath:'index.html',traceType:'READING_ORDER',status:'RISK',artifactId:'artifact-1',createdAt:'2026-09-18',payload:{candidateCount:4,inversionCount:2,inversionRatio:.2,explicitOrder:[],domSequence:[{selector:'#a',name:'DOM 第一',domIndex:0,visualIndex:1,boundingBox:{x:100,y:20,width:80,height:30}}],visualSequence:[{selector:'#b',name:'视觉第一',domIndex:1,visualIndex:0,boundingBox:{x:10,y:20,width:80,height:30}}],inversions:[{domBefore:'#a',visuallyBefore:'#b'}]}}
    render(<ConfigProvider><InteractionEvidence trace={trace}/></ConfigProvider>)
    expect(screen.getByText('DOM 第一')).toBeInTheDocument()
    fireEvent.click(screen.getByText('视觉序列'))
    expect(screen.getByText('视觉第一')).toBeInTheDocument()
    expect(screen.getByText('在视觉位置上先于')).toBeInTheDocument()
    expect(screen.getByText('（至少 16px 的上下错位或同行逆序）')).toBeInTheDocument()
  })
})
