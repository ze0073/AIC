import '@testing-library/jest-dom/vitest'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import * as api from '../api'
import { DemoPage } from './DemoPage'
import { ComparisonPage } from './ComparisonPage'

beforeEach(()=>{
  vi.stubGlobal('ResizeObserver',class{observe(){}unobserve(){}disconnect(){}})
  vi.stubGlobal('matchMedia',vi.fn().mockImplementation(()=>({matches:false,addListener:vi.fn(),removeListener:vi.fn(),addEventListener:vi.fn(),removeEventListener:vi.fn()})))
})
afterEach(()=>{cleanup();vi.restoreAllMocks()})
const state={project:{id:'p',name:'校园演示'},version:{id:'original',versionNo:1},scan:{id:'saved',status:'COMPLETED',progress:100},steps:['导入','扫描','查看证据','可选审阅','修复复测','成果导出'].map((title,i)=>({key:String(i),title,status:'DONE'})),reviewFindingId:'finding',patches:[],snapshots:[]}
function Probe(){return <output aria-label="地址">{useLocation().search}</output>}
function mount(path:string){
  const client=new QueryClient({defaultOptions:{queries:{retry:false},mutations:{retry:false}}})
  return render(<QueryClientProvider client={client}><MemoryRouter initialEntries={[path]}><Probe/><Routes><Route path="/projects/:projectId/demo" element={<DemoPage/>}/></Routes></MemoryRouter></QueryClientProvider>)
}
describe('demo URL state',()=>{
  it('comparison return links preserve the pinned original demo, including after refresh',async()=>{
    vi.spyOn(api,'request').mockResolvedValue([])
    const origin='/projects/p/demo?versionId=original&scanId=saved&step=4'
    const client=new QueryClient({defaultOptions:{queries:{retry:false}}})
    render(<QueryClientProvider client={client}><MemoryRouter initialEntries={[`/projects/p/comparison?beforeScanId=saved&demo=${encodeURIComponent(origin)}`]}><Routes><Route path="/projects/:projectId/comparison" element={<ComparisonPage/>}/></Routes></MemoryRouter></QueryClientProvider>)
    expect(screen.getByRole('link',{name:'返回演示流程'})).toHaveAttribute('href',origin)
  })
  it('restores the selected original version and optional review step without consulting the current pointer',async()=>{
    const request=vi.spyOn(api,'request').mockResolvedValue(state)
    mount('/projects/p/demo?versionId=original&scanId=saved&step=3')
    await screen.findByText('校园演示 · 演示流程')
    expect(request).toHaveBeenCalledWith('/projects/p/demo-state?versionId=original&scanId=saved')
    expect(screen.getByText(/不再是发布门槛/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button',{name:'下一步讲解'}))
    await waitFor(()=>expect(screen.getByLabelText('地址')).toHaveTextContent('versionId=original&scanId=saved&step=4'))
  })
  it('pins initially resolved context in the URL so refresh cannot select a newer version',async()=>{
    vi.spyOn(api,'request').mockResolvedValue(state)
    mount('/projects/p/demo')
    await waitFor(()=>expect(screen.getByLabelText('地址')).toHaveTextContent('versionId=original&scanId=saved'))
  })
  it('renders a retry action for rejected context and recovers after retry',async()=>{
    vi.spyOn(api,'request').mockRejectedValueOnce(new Error('项目中的扫描不存在')).mockResolvedValue(state)
    mount('/projects/p/demo?scanId=foreign')
    await screen.findByText('项目中的扫描不存在')
    fireEvent.click(screen.getByRole('button',{name:/重\s*试/}))
    await screen.findByText('校园演示 · 演示流程')
  })
})
