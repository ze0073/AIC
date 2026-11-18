import '@testing-library/jest-dom/vitest'
import {cleanup,fireEvent,render,screen,waitFor} from '@testing-library/react'
import {App as AntApp,ConfigProvider} from 'antd'
import {QueryClient,QueryClientProvider} from '@tanstack/react-query'
import {MemoryRouter,Route,Routes} from 'react-router-dom'
import {afterEach,beforeEach,expect,it,vi} from 'vitest'
import {AgentStartPage,AgentTaskPage} from './agent'

beforeEach(()=>{
  vi.stubGlobal('ResizeObserver',class{observe(){}unobserve(){}disconnect(){}})
  vi.stubGlobal('matchMedia',vi.fn().mockImplementation(()=>({matches:false,addListener:vi.fn(),removeListener:vi.fn(),addEventListener:vi.fn(),removeEventListener:vi.fn()})))
})
afterEach(()=>{cleanup();vi.unstubAllGlobals()})

it('explains empty target and suite lists in a static-only project',async()=>{
  const client=new QueryClient({defaultOptions:{queries:{retry:false,staleTime:Infinity}}})
  client.setQueryData(['project','p'],{id:'p',name:'静态演示项目'})
  client.setQueryData(['targets','p'],[])
  client.setQueryData(['test-suites','p'],[])
  render(<QueryClientProvider client={client}><ConfigProvider><AntApp><MemoryRouter initialEntries={['/projects/p/agent']}>
    <Routes><Route path="/projects/:projectId/agent" element={<AgentStartPage/>}/></Routes>
  </MemoryRouter></AntApp></ConfigProvider></QueryClientProvider>)
  expect(await screen.findByText('当前项目还不能创建智能检测计划')).toBeInTheDocument()
  expect(screen.getByText(/当前项目：静态演示项目/)).toBeInTheDocument()
  expect(screen.getByRole('link',{name:'打开测试中心'})).toHaveAttribute('href','/projects/p/quality')
  expect(screen.getByRole('link',{name:'切换项目'})).toHaveAttribute('href','/agent')
  expect(screen.getByRole('link',{name:'不会填参数？打开参数配置助手'})).toHaveAttribute('href','/projects/p/quality?suiteAssistant=1')
  expect(screen.getByText(/规则模式不会请求模型/)).toBeInTheDocument()
  expect(screen.queryByRole('checkbox',{name:/同意发送必要的脱敏摘要/})).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button',{name:'页面质量'}))
  expect(screen.getByPlaceholderText('例如：检查页面错误和性能，并保留自动候选证据')).toHaveValue('检查页面可用性和加载指标，保留实际证据')
  expect(screen.getByRole('button',{name:'生成计划'})).toBeDisabled()
})

it('shows the exact approved scope before starting a saved agent task',async()=>{
  const plan={modules:['FRONTEND','SECURITY','PERFORMANCE'],includeFlow:false,flowSteps:[],pages:['/'],targetName:'校园演示',targetUrl:'http://127.0.0.1:8091',
    modelInput:{goal:'检查页面和性能',targetName:'校园演示',pageCount:1,flowActions:[],readOnlyApiCaseCount:0,performanceConfigured:true},
    performance:{vus:[1,5],stageDurationSeconds:20,ratePerSecond:10,p95Ms:1000,errorRate:0.01},
    readOnlyApiCases:[],excludedApiCaseCount:2,reason:'先检查页面，再测性能',model:'qwen3-vl-plus'}
  const draft={id:'a',projectId:'p',targetId:'t',suiteVersionId:'v',goal:'检查页面和性能',status:'AWAITING_START',planHash:'hash',plan,summary:{},cancelRequested:false,steps:[]}
  const fetchMock=vi.fn(async(input:RequestInfo|URL,_init?:RequestInit)=>{
    const url=String(input)
    if(url.endsWith('/auth/csrf'))return Response.json({headerName:'X-CSRF-TOKEN',token:'token'})
    if(url.endsWith('/agent-tasks/a/start'))return Response.json({...draft,status:'RUNNING'})
    throw new Error(`Unexpected request: ${url}`)
  })
  vi.stubGlobal('fetch',fetchMock)
  const client=new QueryClient({defaultOptions:{queries:{retry:false,staleTime:Infinity}}})
  client.setQueryData(['agent-task','a'],draft)
  render(<QueryClientProvider client={client}><ConfigProvider><AntApp><MemoryRouter initialEntries={['/agent-tasks/a']}>
    <Routes><Route path="/agent-tasks/:taskId" element={<AgentTaskPage/>}/></Routes>
  </MemoryRouter></AntApp></ConfigProvider></QueryClientProvider>)
  expect(await screen.findByText('前端质量 → 后端安全 → 性能负载')).toBeInTheDocument()
  expect(screen.getByText(/排除 2 项写入或上传用例/)).toBeInTheDocument()
  expect(fetchMock).not.toHaveBeenCalled()
  fireEvent.click(screen.getByRole('button',{name:'确认并开始'}))
  await waitFor(()=>expect(fetchMock).toHaveBeenCalledWith('/api/agent-tasks/a/start',expect.objectContaining({method:'POST'})))
  const request=fetchMock.mock.calls.find(call=>String(call[0]).endsWith('/agent-tasks/a/start'))
  expect(JSON.parse(String(request?.[1]?.body))).toEqual({planHash:'hash'})
})

it('shows the run outcome instead of a stale completed step after cancellation',async()=>{
  const client=new QueryClient({defaultOptions:{queries:{retry:false,staleTime:Infinity}}})
  client.setQueryData(['test-run','r'],{modules:[{moduleType:'PERFORMANCE',performanceAssessment:{status:'INCONCLUSIVE',reason:'检测未完整执行',latencyAvailable:false}}]})
  client.setQueryData(['agent-task','cancelled'],{
    id:'cancelled',projectId:'p',targetId:'t',suiteVersionId:'v',goal:'检查性能',status:'PARTIAL',planHash:'hash',
    plan:{modules:['PERFORMANCE'],includeFlow:false,flowSteps:[],pages:[],targetName:'校园演示',targetUrl:'http://127.0.0.1:8091',
      modelInput:{},performance:{},readOnlyApiCases:[],excludedApiCaseCount:0,reason:'性能检查',model:'qwen3-vl-plus'},
    summary:{message:'部分完成',attemptedModules:['PERFORMANCE'],completedModules:[],incompleteModules:['PERFORMANCE'],unexecutedModules:[]},
    cancelRequested:false,steps:[{stepNo:1,action:'RUN_MODULE',reason:'开始检查',argument:{module:'PERFORMANCE'},
      result:{status:'CANCELLED',runId:'r'},status:'COMPLETED',runId:'r'}],
  })
  render(<QueryClientProvider client={client}><ConfigProvider><AntApp><MemoryRouter initialEntries={['/agent-tasks/cancelled']}>
    <Routes><Route path="/agent-tasks/:taskId" element={<AgentTaskPage/>}/></Routes>
  </MemoryRouter></AntApp></ConfigProvider></QueryClientProvider>)
  expect(await screen.findByText('已取消')).toBeInTheDocument()
  expect(screen.queryByText('已完成')).not.toBeInTheDocument()
})

it('shows failed performance acceptance on a completed historical agent task',async()=>{
  const client=new QueryClient({defaultOptions:{queries:{retry:false,staleTime:Infinity}}})
  client.setQueryData(['test-run','historical-run'],{modules:[{moduleType:'PERFORMANCE',performanceAssessment:{status:'FAILED',reason:'所有请求均失败，未取得有效响应耗时',latencyAvailable:false}}]})
  client.setQueryData(['agent-task','historical'],{
    id:'historical',projectId:'p',targetId:'t',suiteVersionId:'v',goal:'检查性能',status:'COMPLETED',planHash:'hash',
    plan:{modules:['PERFORMANCE'],includeFlow:false,flowSteps:[],pages:[],targetName:'演示',targetUrl:'http://127.0.0.1:8091',modelInput:{},performance:{},readOnlyApiCases:[],excludedApiCaseCount:0,reason:'检查',model:'RULE'},
    summary:{message:'已完成计划中的检测',attemptedModules:['PERFORMANCE'],completedModules:['PERFORMANCE'],incompleteModules:[],unexecutedModules:[]},
    cancelRequested:false,steps:[{stepNo:1,action:'RUN_MODULE',reason:'运行性能负载',argument:{module:'PERFORMANCE'},result:{status:'COMPLETED'},status:'COMPLETED',runId:'historical-run'}],
  })
  render(<QueryClientProvider client={client}><ConfigProvider><AntApp><MemoryRouter initialEntries={['/agent-tasks/historical']}>
    <Routes><Route path="/agent-tasks/:taskId" element={<AgentTaskPage/>}/></Routes>
  </MemoryRouter></AntApp></ConfigProvider></QueryClientProvider>)
  expect(await screen.findByText('性能验收未通过')).toBeInTheDocument()
  expect(screen.getByText('所有请求均失败，未取得有效响应耗时')).toBeInTheDocument()
})
