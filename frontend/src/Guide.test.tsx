import '@testing-library/jest-dom/vitest'
import {cleanup,fireEvent,render,screen,waitFor} from '@testing-library/react'
import {App as AntApp,ConfigProvider} from 'antd'
import {QueryClient,QueryClientProvider} from '@tanstack/react-query'
import {MemoryRouter} from 'react-router-dom'
import {afterEach,beforeAll,beforeEach,describe,expect,it,vi} from 'vitest'
import {request} from './api'
import {Guide,GuideActionCard} from './Guide'
import {contextForGoal} from './guideApi'
import type {GuideAction,GuideSession} from './guideTypes'

vi.mock('./api',()=>({request:vi.fn(),idempotencyKey:()=> 'test-key',downloadFile:vi.fn(),ApiError:class extends Error{code=''}}))
let cache:QueryClient
const initial:GuideSession={id:'session',goal:'DEPLOY',assistance:'GUIDED',mode:'RULE',aiConsent:false,status:'ACTIVE',context:{},draft:{name:'保存的网站'},revision:0,updatedAt:'2026-10-04T12:00:00',step:'SELECT_SITE',messages:[],actions:[],resources:{},suggestedGoals:[]}
let stored:GuideSession,prefs:{welcomeSeen:boolean;characterVisible:boolean;voiceEnabled:boolean;qwenAvailable:boolean},history:GuideSession[]
beforeAll(()=>{
  vi.stubGlobal('ResizeObserver',class{observe(){}unobserve(){}disconnect(){}})
  vi.stubGlobal('matchMedia',vi.fn().mockImplementation((query:string)=>({matches:false,media:query,onchange:null,addListener:vi.fn(),removeListener:vi.fn(),addEventListener:vi.fn(),removeEventListener:vi.fn(),dispatchEvent:vi.fn()})))
  vi.stubGlobal('EventSource',class{addEventListener(){}close(){}})
  window.getComputedStyle=new Proxy(window.getComputedStyle,{apply:(target,thisArg,args)=>Reflect.apply(target,thisArg,[args[0]])})
})
beforeEach(()=>{
  stored=structuredClone(initial);prefs={welcomeSeen:false,characterVisible:true,voiceEnabled:false,qwenAvailable:false};history=[]
  window.sessionStorage.clear()
  cache=new QueryClient({defaultOptions:{queries:{retry:false,staleTime:Infinity},mutations:{retry:false}}})
  vi.mocked(request).mockReset().mockImplementation(async(path,init)=>{
    const body=init?.body?JSON.parse(init.body as string):{}
    if(path==='/guide/preferences'){if(init?.method==='PUT')prefs={...prefs,...body};return {...prefs} as never}
    if(path==='/guide/sessions'&&init?.method==='POST'){stored={...structuredClone(initial),...body};history=[stored];return stored as never}
    if(path==='/guide/sessions')return history as never
    if(path==='/guide/sessions/session'&&init?.method==='PATCH'){stored={...stored,...body,revision:stored.revision+1};return stored as never}
    if(path==='/guide/sessions/session')return stored as never
    if(path.endsWith('/messages'))return {...stored,messages:[{id:'m',role:'ASSISTANT',content:'请在操作卡确认',createdAt:'2026-10-04'}]} as never
    return [] as never
  })
})
afterEach(()=>{cleanup();cache.clear()})
function mount(){return render(<ConfigProvider><AntApp><QueryClientProvider client={cache}><MemoryRouter><Guide userId="user"/></MemoryRouter></QueryClientProvider></AntApp></ConfigProvider>)}
describe('全站向导',()=>{
  it('shows all five goals on first entry and persists skipping the welcome',async()=>{
    mount();await screen.findByRole('button',{name:/01 部署我的网站/})
    expect(screen.getByRole('button',{name:/05 了解平台/})).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button',{name:'先自己操作'}))
    await waitFor(()=>expect(prefs.welcomeSeen).toBe(true))
    expect(request).toHaveBeenCalledWith('/guide/preferences',expect.objectContaining({method:'PUT',body:JSON.stringify({welcomeSeen:true})}))
  })
  it('does not automatically expand after the welcome was dismissed',async()=>{
    prefs.welcomeSeen=true;mount();await screen.findByRole('button',{name:'打开视界向导'})
    expect(screen.queryByRole('button',{name:/01 部署我的网站/})).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button',{name:'打开视界向导'}));expect(await screen.findByRole('button',{name:/01 部署我的网站/})).toBeInTheDocument()
  })
  it('restores saved drafts and preserves them when switching operating modes',async()=>{
    prefs.welcomeSeen=true;history=[stored];mount()
    fireEvent.click(await screen.findByRole('button',{name:'打开视界向导'}))
    fireEvent.click(await screen.findByText('帮我办理'))
    await waitFor(()=>expect(stored.assistance).toBe('DELEGATED'))
    expect(await screen.findByLabelText('新网站名称')).toHaveValue('保存的网站')
    fireEvent.click(screen.getByText('带我操作'))
    await waitFor(()=>expect(stored.assistance).toBe('GUIDED'))
    expect(stored.draft.name).toBe('保存的网站')
  })
  it('sending confirmation text only invokes the message endpoint',async()=>{
    history=[stored];mount();await screen.findByLabelText('说出你的需求')
    fireEvent.change(screen.getByLabelText('说出你的需求'),{target:{value:'确认'}})
    fireEvent.click(screen.getByRole('button',{name:/发送需求/}))
    await waitFor(()=>expect(request).toHaveBeenCalledWith('/guide/sessions/session/messages',expect.objectContaining({method:'POST'})))
    expect(vi.mocked(request).mock.calls.some(([path])=>path.includes('/actions/'))).toBe(false)
  })
  it('falls back to readable steps when the page control is unavailable',async()=>{
    history=[stored];mount()
    fireEvent.click(await screen.findByRole('button',{name:'带我到页面操作'}))
    await waitFor(()=>expect(screen.getByText('请按文字步骤操作；完成后重新打开向导继续。')).toBeInTheDocument(),{timeout:5000})
    fireEvent.click(screen.getByRole('button',{name:'知道了'}))
    expect(screen.queryByText('请按文字步骤操作；完成后重新打开向导继续。')).not.toBeInTheDocument()
  })
  it('stops active speech when leaving the authenticated layout',async()=>{
    const cancel=vi.fn();vi.stubGlobal('speechSynthesis',{cancel})
    prefs.welcomeSeen=true;const view=mount();await screen.findByRole('button',{name:'打开视界向导'})
    view.unmount();expect(cancel).toHaveBeenCalled()
  })
  it('plays the initial request after voices load without a second click',async()=>{
    let voices:Array<{lang:string}>=[]
    const synth=Object.assign(new EventTarget(),{getVoices:()=>voices,speak:vi.fn(),cancel:vi.fn()})
    vi.stubGlobal('speechSynthesis',synth);vi.stubGlobal('SpeechSynthesisUtterance',class{})
    mount();fireEvent.click(await screen.findByRole('button',{name:/播报回复/}))
    expect(screen.getByText('正在加载中文声音')).toBeInTheDocument()
    expect(screen.queryByText(/没有可用中文声音/)).not.toBeInTheDocument()
    voices=[{lang:'zh-CN'}];synth.dispatchEvent(new Event('voiceschanged'))
    await waitFor(()=>expect(synth.speak).toHaveBeenCalledOnce())
    expect(await screen.findByRole('button',{name:/重新播报/})).toBeInTheDocument()
    expect(screen.queryByText(/没有可用中文声音/)).not.toBeInTheDocument()
  })
  it('stops a pending reply when the panel closes before voices load',async()=>{
    let voices:Array<{lang:string}>=[]
    const synth=Object.assign(new EventTarget(),{getVoices:()=>voices,speak:vi.fn(),cancel:vi.fn()})
    vi.stubGlobal('speechSynthesis',synth);vi.stubGlobal('SpeechSynthesisUtterance',class{})
    mount();fireEvent.click(await screen.findByRole('button',{name:/播报回复/}))
    fireEvent.click(screen.getByRole('button',{name:/^(关闭|Close)$/}))
    voices=[{lang:'zh-CN'}];synth.dispatchEvent(new Event('voiceschanged'))
    await waitFor(()=>expect(prefs.welcomeSeen).toBe(true))
    expect(synth.speak).not.toHaveBeenCalled();expect(prefs.voiceEnabled).toBe(false)
  })
})
describe('冻结操作卡',()=>{
  const action:GuideAction={id:'action',sessionId:'session',actionType:'APPLY_REPAIR',status:'PROPOSED',preview:{siteName:'原网站',file:'index.html',diffText:'- old\n+ new'},actionHash:'hash',receipt:{},expiresAt:'2099-01-01T00:00:00'}
  it('requires explicit diff review and a separate confirm click',()=>{
    const confirm=vi.fn();render(<GuideActionCard action={action} busy={false} onConfirm={confirm} onCancel={()=>{}}/>)
    expect(screen.getByRole('button',{name:'确认执行'})).toBeDisabled()
    fireEvent.click(screen.getByRole('checkbox',{name:'我已审阅差异，同意应用到新源码版本'}))
    expect(confirm).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button',{name:'确认执行'}));expect(confirm).toHaveBeenCalledWith(true)
  })
  it('does not expose a confirm button for expired or completed previews',()=>{
    render(<GuideActionCard action={{...action,status:'EXPIRED'}} busy={false} onConfirm={()=>{}} onCancel={()=>{}}/>)
    expect(screen.queryByRole('button',{name:'确认执行'})).not.toBeInTheDocument()
  })
})
describe('流程上下文',()=>{
  it('removes stale task and report references when changing goals',()=>{
    const c={projectId:'project',siteId:'site',taskId:'task',runId:'run',snapshotId:'snapshot',issueId:'issue'}
    expect(contextForGoal(c,'DEPLOY')).toEqual({projectId:'project',siteId:'site'})
    expect(contextForGoal(c,'REPAIR')).not.toHaveProperty('snapshotId')
    expect(contextForGoal(c,'HELP')).toEqual({})
  })
})
