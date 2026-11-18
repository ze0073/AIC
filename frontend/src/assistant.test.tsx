import '@testing-library/jest-dom/vitest'
import {cleanup,fireEvent,render,screen,waitFor} from '@testing-library/react'
import {App as AntApp,ConfigProvider} from 'antd'
import {QueryClient,QueryClientProvider} from '@tanstack/react-query'
import {MemoryRouter,Route,Routes} from 'react-router-dom'
import {afterEach,beforeEach,expect,it,vi} from 'vitest'
import {AssistantSessionPage,buildPrepareRequest} from './assistant'
import {resetCsrf} from './api'

beforeEach(()=>{
  resetCsrf()
  vi.stubGlobal('ResizeObserver',class{observe(){}unobserve(){}disconnect(){}})
  vi.stubGlobal('matchMedia',vi.fn().mockImplementation(()=>({matches:false,addListener:vi.fn(),removeListener:vi.fn(),addEventListener:vi.fn(),removeEventListener:vi.fn()})))
})
afterEach(()=>{cleanup();vi.unstubAllGlobals();resetCsrf()})

it('builds an explicit structured prepare request instead of guessing registered resources',()=>{
  expect(buildPrepareRequest({goal:'检查页面质量',targetId:'target',suiteVersionId:'suite',baselineTaskId:'baseline'})).toEqual({
    content:'请为“检查页面质量”生成一份可确认的检测计划。',intent:'PREPARE_AGENT_TASK',targetId:'target',suiteVersionId:'suite',baselineTaskId:'baseline',goal:'检查页面质量',
  })
})

it('prefills the registered website, plan, baseline and plain-language goal from the safe recommendation',async()=>{
  const client=new QueryClient({defaultOptions:{queries:{retry:false,staleTime:Infinity}}})
  client.setQueryData(['assistant-session','session'],{id:'session',projectId:'project',mode:'RULE',status:'ACTIVE',messages:[],actions:[],suggestions:[]})
  client.setQueryData(['project','project'],{id:'project',name:'报名项目'})
  client.setQueryData(['targets','project'],[{id:'target',name:'报名站',baseUrl:'http://127.0.0.1:8091'}])
  client.setQueryData(['test-suites','project'],[{id:'suite',name:'日常体检',currentVersion:2,currentVersionId:'suite-version'}])
  client.setQueryData(['agent-baselines','project','target','suite-version'],[{id:'baseline',goal:'昨日体检',status:'COMPLETED'}])
  client.setQueryData(['assistant-recommendation','project'],{schemaVersion:'0.8.0',ready:true,warnings:[],
    defaultGoal:'完成日常体检，并告诉我先处理什么。',target:{id:'target',name:'报名站',baseUrl:'http://127.0.0.1:8091',reason:'唯一网站'},
    suite:{id:'suite',versionId:'suite-version',name:'日常体检',version:2,reason:'唯一方案'},baseline:{id:'baseline',goal:'昨日体检',status:'COMPLETED',reason:'最近完整任务'}})
  render(<QueryClientProvider client={client}><ConfigProvider><AntApp><MemoryRouter initialEntries={['/assistant-sessions/session']}>
    <Routes><Route path="/assistant-sessions/:sessionId" element={<AssistantSessionPage/>}/></Routes>
  </MemoryRouter></AntApp></ConfigProvider></QueryClientProvider>)

  expect(await screen.findByText('已为你准备推荐配置')).toBeInTheDocument()
  await waitFor(()=>expect(screen.getByLabelText('助手检测目标')).toHaveValue('完成日常体检，并告诉我先处理什么。'))
  expect(screen.getByText('报名站 · http://127.0.0.1:8091')).toBeInTheDocument()
  expect(screen.getByText('日常体检 · V2')).toBeInTheDocument()
  expect(screen.getByText('昨日体检 · COMPLETED')).toBeInTheDocument()
  expect(screen.getByRole('button',{name:/生成可确认的检查计划/})).toBeEnabled()
})

it('keeps chat confirmation separate from the action confirmation endpoint',async()=>{
  const action={id:'action',sessionId:'session',actionType:'START_AGENT_TASK',status:'PROPOSED',actionHash:'hash',resourceId:'task',
    preview:{goal:'检查页面质量',plan:{mode:'RULE',targetName:'演示目标',suiteVersion:1,modules:['FRONTEND'],reason:'按页面关键词选择'}}}
  const session={id:'session',projectId:'project',mode:'RULE',status:'ACTIVE',messages:[{id:'hello',role:'ASSISTANT',content:'你好'}],actions:[action],suggestions:[]}
  const client=new QueryClient({defaultOptions:{queries:{retry:false,staleTime:Infinity}}})
  client.setQueryData(['assistant-session','session'],session)
  client.setQueryData(['project','project'],{id:'project',name:'测试项目'})
  client.setQueryData(['targets','project'],[])
  client.setQueryData(['test-suites','project'],[])
  const fetchMock=vi.fn(async(input:RequestInfo|URL,init?:RequestInit)=>{
    const url=String(input)
    if(url.endsWith('/auth/csrf'))return Response.json({headerName:'X-CSRF-TOKEN',token:'token'})
    if(url.endsWith('/assistant-sessions/session/messages'))return Response.json({userMessage:{id:'user'},assistantMessage:{id:'reply',content:'聊天不会执行'},action:null})
    if(url.endsWith('/assistant-actions/action/confirm'))return Response.json({...action,status:'SUCCEEDED',receipt:{taskId:'task',status:'RUNNING'}})
    if(url.endsWith('/assistant-sessions/session'))return Response.json(session)
    throw new Error(`Unexpected request: ${url} ${init?.method||'GET'}`)
  })
  vi.stubGlobal('fetch',fetchMock)
  render(<QueryClientProvider client={client}><ConfigProvider><AntApp><MemoryRouter initialEntries={['/assistant-sessions/session']}>
    <Routes><Route path="/assistant-sessions/:sessionId" element={<AssistantSessionPage/>}/></Routes>
  </MemoryRouter></AntApp></ConfigProvider></QueryClientProvider>)

  fireEvent.change(await screen.findByLabelText('给项目助手发送消息'),{target:{value:'确认执行'}})
  fireEvent.click(screen.getByRole('button',{name:/发送消息/}))
  await waitFor(()=>expect(fetchMock).toHaveBeenCalledWith('/api/assistant-sessions/session/messages',expect.objectContaining({method:'POST'})))
  expect(fetchMock.mock.calls.some(call=>String(call[0]).includes('/confirm'))).toBe(false)

  fireEvent.click(screen.getByRole('button',{name:/确认执行/}))
  await waitFor(()=>expect(fetchMock).toHaveBeenCalledWith('/api/assistant-actions/action/confirm',expect.objectContaining({method:'POST'})))
  expect(JSON.parse(String(fetchMock.mock.calls.find(call=>String(call[0]).endsWith('/confirm'))?.[1]?.body))).toEqual({actionHash:'hash'})
})
