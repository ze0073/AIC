import '@testing-library/jest-dom/vitest'
import {cleanup,render,screen} from '@testing-library/react'
import {QueryClient,QueryClientProvider} from '@tanstack/react-query'
import {App as AntApp,ConfigProvider} from 'antd'
import {MemoryRouter} from 'react-router-dom'
import {afterEach,beforeEach,expect,it,vi} from 'vitest'
import {SmartTaskWorkspace} from './SmartInsights'

beforeEach(()=>{
  vi.stubGlobal('ResizeObserver',class{observe(){}unobserve(){}disconnect(){}})
  vi.stubGlobal('matchMedia',vi.fn().mockImplementation(()=>({matches:false,addListener:vi.fn(),removeListener:vi.fn(),addEventListener:vi.fn(),removeEventListener:vi.fn()})))
})
afterEach(()=>{cleanup();vi.unstubAllGlobals()})

it('turns a completed task into an evidence-linked action list and historical trend',async()=>{
  const client=new QueryClient({defaultOptions:{queries:{retry:false,staleTime:Infinity}}})
  client.setQueryData(['agent-task','task'],{id:'task',projectId:'project',goal:'日常体检',status:'COMPLETED',plan:{modules:['FRONTEND','SECURITY']},
    summary:{message:'检查完成'},steps:[
      {action:'RUN_MODULE',argument:{module:'FRONTEND'},result:{status:'COMPLETED'},status:'COMPLETED',runId:'run-front'},
      {action:'RUN_MODULE',argument:{module:'SECURITY'},result:{status:'COMPLETED'},status:'COMPLETED',runId:'run-security'},
    ]})
  client.setQueryData(['agent-insights','task'],{schemaVersion:'0.8.0',taskId:'task',projectId:'project',status:'COMPLETED',
    headline:'发现 1 个需要优先处理的问题',summary:'已根据真实证据整理下一步。',confidence:'HIGH',
    counts:{issues:1,high:1,medium:0,low:0,pendingReview:1,incompleteModules:0},
    actionItems:[{id:'issue',kind:'ISSUE',priority:'P0',title:'入口按钮无法识别',reason:'按钮没有可访问名称',module:'FRONTEND',
      evidenceNote:'高可信自动证据，仍需结合业务确认结论',recommendedOwner:'前端负责人',nextStep:'打开页面证据定位元素后复测。',
      prevention:'把对应规则加入提交前检查。',detailPath:'/projects/project/collaboration?issueId=issue'}],
    trend:{points:[
      {taskId:'before',status:'COMPLETED',goal:'上次',finishedAt:'2026-09-29T10:00:00',issueCount:3,high:2,medium:1,low:0,completedModules:['FRONTEND'],p95Ms:900},
      {taskId:'task',status:'COMPLETED',goal:'本次',finishedAt:'2026-09-30T10:00:00',issueCount:1,high:1,medium:0,low:0,completedModules:['FRONTEND','SECURITY'],p95Ms:500},
    ],assessment:{overall:'IMPROVED',issues:'IMPROVED',performance:'IMPROVED',experience:'NO_DATA',message:'核心指标改善。'}},
    limitations:['自动候选仍需项目成员审阅。'],evidenceLinks:[{runId:'run-front',module:'FRONTEND',status:'COMPLETED',label:'页面体验证据',detailPath:'/test-runs/run-front'}],generatedAt:'2026-09-30T10:01:00'})
  render(<QueryClientProvider client={client}><ConfigProvider><AntApp><MemoryRouter><SmartTaskWorkspace taskId="task" showProgress={false}/></MemoryRouter></AntApp></ConfigProvider></QueryClientProvider>)

  expect(await screen.findByRole('heading',{name:'发现 1 个需要优先处理的问题'})).toBeInTheDocument()
  expect(screen.getByText('入口按钮无法识别')).toBeInTheDocument()
  expect(screen.getByText('把对应规则加入提交前检查。')).toBeInTheDocument()
  expect(screen.getByRole('img',{name:'待处理事项最近 2 次变化'})).toBeInTheDocument()
  expect(screen.getByRole('link',{name:/查看对应证据/})).toHaveAttribute('href','/projects/project/collaboration?issueId=issue')
  expect(screen.getByRole('button',{name:/页面体验证据/}).closest('a')).toHaveAttribute('href','/test-runs/run-front')
})
