import '@testing-library/jest-dom/vitest'
import {cleanup,fireEvent,render,screen,waitFor} from '@testing-library/react'
import {App as AntApp,ConfigProvider} from 'antd'
import {QueryClient,QueryClientProvider} from '@tanstack/react-query'
import {MemoryRouter,Route,Routes} from 'react-router-dom'
import {afterEach,beforeAll,describe,expect,it,vi} from 'vitest'
import {QualityCenterPage,TestRunPage} from './quality'

beforeAll(()=>{
  vi.stubGlobal('ResizeObserver',class{observe(){}unobserve(){}disconnect(){}})
  vi.stubGlobal('matchMedia',vi.fn().mockImplementation(()=>({matches:false,addListener:vi.fn(),removeListener:vi.fn(),addEventListener:vi.fn(),removeEventListener:vi.fn()})))
})
afterEach(cleanup)

describe('quality center run history',()=>{
  it('restores status and module filters from the URL',async()=>{
    const client=new QueryClient({defaultOptions:{queries:{retry:false,staleTime:Infinity}}})
    client.setQueryData(['project','p'],{id:'p',name:'演示项目'})
    client.setQueryData(['targets','p'],[])
    client.setQueryData(['test-suites','p'],[])
    client.setQueryData(['credentials','p'],[])
    client.setQueryData(['test-runs','p'],[
      {id:'load',status:'COMPLETED',createdAt:'2026-09-25T01:00:00',modules:[{id:'m1',moduleType:'PERFORMANCE'}]},
      {id:'page',status:'COMPLETED',createdAt:'2026-09-25T00:00:00',modules:[{id:'m2',moduleType:'FRONTEND'}]},
      {id:'failed',status:'FAILED',createdAt:'2026-09-24T23:00:00',modules:[{id:'m3',moduleType:'PERFORMANCE'}]},
    ])
    render(<QueryClientProvider client={client}><ConfigProvider><AntApp><MemoryRouter initialEntries={['/projects/p/quality?runStatus=COMPLETED&runModule=PERFORMANCE']}>
      <Routes><Route path="/projects/:projectId/quality" element={<QualityCenterPage/>}/></Routes>
    </MemoryRouter></AntApp></ConfigProvider></QueryClientProvider>)
    expect(await screen.findByText('运行记录 (1/3)')).toBeInTheDocument()
    expect(screen.getAllByRole('button',{name:'查看证据'})).toHaveLength(1)
    expect(screen.getByRole('combobox',{name:'按状态筛选运行'})).toBeInTheDocument()
    expect(screen.getByRole('combobox',{name:'按模块筛选运行'})).toBeInTheDocument()
  })
})

it('gives beginners a guided daily check and keeps professional controls optional',async()=>{
  const client=new QueryClient({defaultOptions:{queries:{retry:false,staleTime:Infinity}}})
  client.setQueryData(['project','p'],{id:'p',name:'演示项目'})
  client.setQueryData(['targets','p'],[{id:'t',name:'报名测试站',baseUrl:'http://127.0.0.1:8091',allowedAddresses:['127.0.0.1'],pathPrefixes:['/'],allowActiveScan:false}])
  client.setQueryData(['test-suites','p'],[{id:'s',name:'日常体检',currentVersion:1,currentVersionId:'sv'}])
  client.setQueryData(['credentials','p'],[])
  client.setQueryData(['test-runs','p'],[])
  client.setQueryData(['agent-tasks','p'],[])
  render(<QueryClientProvider client={client}><ConfigProvider><AntApp><MemoryRouter initialEntries={['/projects/p/quality']}>
    <Routes><Route path="/projects/:projectId/quality" element={<QualityCenterPage/>}/></Routes>
  </MemoryRouter></AntApp></ConfigProvider></QueryClientProvider>)
  expect(await screen.findByRole('heading',{name:'已经准备好，可以开始检查'})).toBeInTheDocument()
  const professional=screen.getByText('专业工具与更多设置').closest('details')
  expect(professional).not.toHaveAttribute('open')
  const recommendedPreset=document.querySelector('.run-preset-grid button[aria-pressed="true"]')
  expect(recommendedPreset).toHaveTextContent('日常体检')
  expect(recommendedPreset).toHaveTextContent('页面体验')
  expect(recommendedPreset).toHaveTextContent('只读安全')
  fireEvent.click(screen.getByRole('radio',{name:'专业模式'}))
  await waitFor(()=>expect(professional).toHaveAttribute('open'))
})

it('fills the local demo address without submitting target authorization',async()=>{
  const client=new QueryClient({defaultOptions:{queries:{retry:false,staleTime:Infinity}}})
  client.setQueryData(['project','p'],{id:'p',name:'演示项目'})
  client.setQueryData(['targets','p'],[])
  client.setQueryData(['test-suites','p'],[])
  client.setQueryData(['credentials','p'],[])
  client.setQueryData(['test-runs','p'],[])
  client.setQueryData(['agent-tasks','p'],[])
  render(<QueryClientProvider client={client}><ConfigProvider><AntApp><MemoryRouter initialEntries={['/projects/p/quality']}>
    <Routes><Route path="/projects/:projectId/quality" element={<QualityCenterPage/>}/></Routes>
  </MemoryRouter></AntApp></ConfigProvider></QueryClientProvider>)
  fireEvent.click(await screen.findByRole('button',{name:/登记在线目标/}))
  fireEvent.click(screen.getByRole('button',{name:'本机演示站'}))
  expect(screen.getByPlaceholderText('https://test.example.com/app/')).toHaveValue('http://127.0.0.1:8091/')
  expect(screen.getByRole('button',{name:'检查目标范围'})).toBeEnabled()
},10000)

it('fills suite name and entry page from recommendations',async()=>{
  const client=new QueryClient({defaultOptions:{queries:{retry:false,staleTime:Infinity}}})
  client.setQueryData(['project','p'],{id:'p',name:'演示项目'})
  client.setQueryData(['targets','p'],[])
  client.setQueryData(['test-suites','p'],[])
  client.setQueryData(['credentials','p'],[])
  client.setQueryData(['test-runs','p'],[])
  client.setQueryData(['agent-tasks','p'],[])
  render(<QueryClientProvider client={client}><ConfigProvider><AntApp><MemoryRouter initialEntries={['/projects/p/quality']}>
    <Routes><Route path="/projects/:projectId/quality" element={<QualityCenterPage/>}/></Routes>
  </MemoryRouter></AntApp></ConfigProvider></QueryClientProvider>)
  fireEvent.click(await screen.findByRole('button',{name:/创建测试套件/}))
  fireEvent.click(screen.getByRole('button',{name:'基础质量验收'}))
  fireEvent.click(screen.getByRole('button',{name:'仅检查入口页'}))
  expect(screen.getByPlaceholderText('基础质量验收')).toHaveValue('基础质量验收')
  expect(screen.getByPlaceholderText('留空时使用目标入口页')).toHaveValue('')
},15000)

it('opens the assistant from the agent link and fills conservative parameters without requests',async()=>{
  const client=new QueryClient({defaultOptions:{queries:{retry:false,staleTime:Infinity}}})
  client.setQueryData(['project','p'],{id:'p',name:'演示项目'})
  for(const key of ['targets','test-suites','credentials','test-runs','agent-tasks'])
    client.setQueryData([key,'p'],[])
  const fetchMock=vi.spyOn(globalThis,'fetch').mockRejectedValue(new Error('No request allowed'))
  try{
    render(<QueryClientProvider client={client}><ConfigProvider><AntApp><MemoryRouter initialEntries={['/projects/p/quality?suiteAssistant=1&runStatus=FAILED']}>
      <Routes><Route path="/projects/:projectId/quality" element={<QualityCenterPage/>}/></Routes>
    </MemoryRouter></AntApp></ConfigProvider></QueryClientProvider>)
    expect(await screen.findByText('规则模板 · 未调用模型')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button',{name:'填入推荐参数'}))
    expect(screen.getByPlaceholderText('基础质量验收')).toHaveValue('首次低负载检查')
    expect(screen.getByPlaceholderText('留空时使用目标入口页')).toHaveValue('')
    expect(screen.getByPlaceholderText('例如 /api/slow；留空时测试入口页')).toHaveValue('')
    expect(screen.getByPlaceholderText('1,5,10')).toHaveValue('1')
    expect(screen.getByRole('spinbutton',{name:'速率上限（次/秒）'})).toHaveValue('2')
    expect(screen.getByText('未配置权限用例，不能据此认定权限安全',{exact:false})).toBeInTheDocument()
    expect(fetchMock).not.toHaveBeenCalled()
  }finally{fetchMock.mockRestore()}
},20000)

it('requires explicit confirmation before replacing unsaved configuration and preserves the name',async()=>{
  const client=new QueryClient({defaultOptions:{queries:{retry:false,staleTime:Infinity}}})
  client.setQueryData(['project','p'],{id:'p',name:'演示项目'})
  for(const key of ['targets','test-suites','credentials','test-runs','agent-tasks'])
    client.setQueryData([key,'p'],[])
  render(<QueryClientProvider client={client}><ConfigProvider><AntApp><MemoryRouter initialEntries={['/projects/p/quality']}>
    <Routes><Route path="/projects/:projectId/quality" element={<QualityCenterPage/>}/></Routes>
  </MemoryRouter></AntApp></ConfigProvider></QueryClientProvider>)
  fireEvent.click(await screen.findByRole('button',{name:/帮我配置测试参数/}))
  fireEvent.change(screen.getByPlaceholderText('基础质量验收'),{target:{value:'我的套件'}})
  fireEvent.change(screen.getByPlaceholderText('1,5,10'),{target:{value:'3'}})
  fireEvent.click(screen.getByRole('button',{name:'填入推荐参数'}))
  expect((await screen.findAllByText('替换当前未保存的测试参数？')).length).toBeGreaterThan(0)
  expect(screen.getByPlaceholderText('1,5,10')).toHaveValue('3')
  fireEvent.click(screen.getByRole('button',{name:'替换参数'}))
  await waitFor(()=>expect(screen.getByPlaceholderText('1,5,10')).toHaveValue('1'))
  expect(screen.getByPlaceholderText('基础质量验收')).toHaveValue('我的套件')
},20000)

it('does not show zero performance metrics as measured values when cancellation left no samples',async()=>{
  const client=new QueryClient({defaultOptions:{queries:{retry:false,staleTime:Infinity}}})
  client.setQueryData(['test-run','cancelled'],{
    id:'cancelled',projectId:'p',targetId:'t',suiteVersionId:'v',status:'CANCELLED',cancelRequested:true,
    configSnapshot:'{}',toolSnapshot:'{}',createdAt:'2026-09-25T00:00:00',issues:[],
    modules:[{id:'m',moduleType:'PERFORMANCE',status:'CANCELLED',summary:'运行已取消',
      resultPayload:JSON.stringify({summary:{metrics:{http_req_duration:{values:{med:0,'p(95)':0,'p(99)':0}},
        http_reqs:{count:0,rate:0},http_req_failed:{value:0}},timeSeries:[]}})}],
  })
  client.setQueryData(['test-runs','p'],[])
  client.setQueryData(['test-run-report-snapshots','cancelled'],[])
  render(<QueryClientProvider client={client}><ConfigProvider><AntApp><MemoryRouter initialEntries={['/test-runs/cancelled']}>
    <Routes><Route path="/test-runs/:runId" element={<TestRunPage/>}/></Routes>
  </MemoryRouter></AntApp></ConfigProvider></QueryClientProvider>)
  expect(await screen.findByText('没有可用时间序列；本模块不能视为性能通过')).toBeInTheDocument()
  expect(screen.queryByText('0.00')).not.toBeInTheDocument()
})

it('marks historical all-failed requests as failed without showing fake zero latency',async()=>{
  const client=new QueryClient({defaultOptions:{queries:{retry:false,staleTime:Infinity}}})
  client.setQueryData(['test-run','all-failed'],{
    id:'all-failed',projectId:'p',targetId:'t',suiteVersionId:'v',status:'COMPLETED',cancelRequested:false,
    configSnapshot:'{}',toolSnapshot:'{}',createdAt:'2026-09-25T00:00:00',issues:[],
    modules:[{id:'m',moduleType:'PERFORMANCE',status:'COMPLETED',summary:'负载执行已结束',
      performanceAssessment:{status:'FAILED',reason:'所有请求均失败，未取得有效响应耗时；不能判定性能通过',latencyAvailable:false,allRequestsFailed:true,thresholdsFailed:true,requestCount:5,errorRate:1,p95LimitMs:1000,errorRateLimit:.01},
      resultPayload:JSON.stringify({summary:{metrics:{http_req_duration:{values:{med:0,'p(95)':0,'p(99)':0}},http_reqs:{count:5,rate:1.2},http_req_failed:{value:1}}}})}],
  })
  client.setQueryData(['test-runs','p'],[])
  client.setQueryData(['test-run-report-snapshots','all-failed'],[])
  render(<QueryClientProvider client={client}><ConfigProvider><AntApp><MemoryRouter initialEntries={['/test-runs/all-failed']}>
    <Routes><Route path="/test-runs/:runId" element={<TestRunPage/>}/></Routes>
  </MemoryRouter></AntApp></ConfigProvider></QueryClientProvider>)
  expect(await screen.findByText('性能验收未通过')).toBeInTheDocument()
  expect(screen.getByText(/执行已结束，性能未通过/)).toBeInTheDocument()
  expect(screen.getByText('执行结束 · 未通过')).toBeInTheDocument()
  expect(screen.getByText('请求尝试速率')).toBeInTheDocument()
  expect(screen.getByText('错误率')).toBeInTheDocument()
  expect(screen.getByText('没有保存的问题记录；性能失败或未完成仍可能存在，请以上方模块判定为准。')).toBeInTheDocument()
  expect(screen.queryByText('0.00')).not.toBeInTheDocument()
})

it('keeps sampled windows visible without inventing aggregate metrics',async()=>{
  const client=new QueryClient({defaultOptions:{queries:{retry:false,staleTime:Infinity}}})
  client.setQueryData(['test-run','partial'],{
    id:'partial',projectId:'p',targetId:'t',suiteVersionId:'v',status:'PARTIAL',cancelRequested:false,
    configSnapshot:'{}',toolSnapshot:'{}',createdAt:'2026-09-25T00:00:00',issues:[],
    modules:[{id:'m',moduleType:'PERFORMANCE',status:'PARTIAL',summary:'未取得聚合指标',
      resultPayload:JSON.stringify({summary:{metrics:{http_req_duration:{values:{med:0,'p(95)':0,'p(99)':0}},
        http_reqs:{count:0,rate:0},http_req_failed:{value:0}},
        timeSeries:[{windowStartSecond:0,stage:1,p50Ms:12,p95Ms:20,p99Ms:24,throughputPerSecond:2,errorRate:0}]}})}],
  })
  client.setQueryData(['test-runs','p'],[])
  client.setQueryData(['test-run-report-snapshots','partial'],[])
  render(<QueryClientProvider client={client}><ConfigProvider><AntApp><MemoryRouter initialEntries={['/test-runs/partial']}>
    <Routes><Route path="/test-runs/:runId" element={<TestRunPage/>}/></Routes>
  </MemoryRouter></AntApp></ConfigProvider></QueryClientProvider>)
  expect(await screen.findByText('聚合指标未取得；下方仅展示已采集的时间序列')).toBeInTheDocument()
  expect(screen.getByText('20.0')).toBeInTheDocument()
  expect(screen.queryByText('0.00')).not.toBeInTheDocument()
})
