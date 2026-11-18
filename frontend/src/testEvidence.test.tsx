import '@testing-library/jest-dom/vitest'
import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import { inspectEvidence } from './evidenceInspection'
import { AnnotatedEvidence, PerformanceTrendChart } from './testEvidence'

afterEach(cleanup)

describe('advanced evidence annotations',()=>{
  const assessment={status:'FAILED',reason:'超阈值',latencyAvailable:true,allRequestsFailed:false,thresholdsFailed:true,requestCount:10,errorRate:.2,p95Ms:1200,p95LimitMs:1000,errorRateLimit:.01} as const

  it('uses the run thresholds to flag the exact failing fields',()=>{
    const evidence={status:'FAILED',summary:{metrics:{http_req_failed:{value:.2},http_req_duration:{values:{'p(95)':1200}}}}}
    const flags=inspectEvidence(evidence,assessment)
    expect(flags.map(flag=>flag.path)).toEqual(expect.arrayContaining([
      '$.status','$.summary.metrics.http_req_failed.value','$.summary.metrics.http_req_duration.values.p(95)',
    ]))
    expect(flags.find(flag=>flag.path.endsWith('http_req_failed.value'))?.note).toContain('超过 1.00% 阈值')
    expect(flags.find(flag=>flag.path.endsWith('p(95)'))?.note).toContain('超过 1000.0 ms 阈值')
  })

  it('renders highlighted JSON lines with human-readable notes',()=>{
    render(<AnnotatedEvidence performanceAssessment={assessment} raw={JSON.stringify({status:'FAILED',errorRate:.2,p95Ms:1200})}/>)
    expect(screen.getByText('3 处需关注')).toBeInTheDocument()
    expect(screen.getByLabelText('证据问题注释')).toBeInTheDocument()
    expect(screen.getByLabelText('带注释的原始 JSON 证据').querySelectorAll('.evidence-line--danger')).toHaveLength(3)
  })

  it('keeps a labelled chart alongside the exact performance table data',()=>{
    render(<PerformanceTrendChart assessment={assessment} points={[
      {windowStartSecond:0,stage:1,p50Ms:300,p95Ms:900,p99Ms:1100,errorRate:0},
      {windowStartSecond:10,stage:2,p50Ms:500,p95Ms:1200,p99Ms:1400,errorRate:.2},
    ]}/>)
    expect(screen.getByRole('img',{name:'性能 P50、P95、P99 和错误率时间序列图'})).toBeInTheDocument()
    expect(screen.getByText('性能时间序列图谱')).toBeInTheDocument()
  })
})
