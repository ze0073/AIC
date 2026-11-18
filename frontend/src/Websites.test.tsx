import '@testing-library/jest-dom/vitest'
import { cleanup,fireEvent,render,screen,waitFor } from '@testing-library/react'
import { QueryClient,QueryClientProvider } from '@tanstack/react-query'
import { App } from 'antd'
import { MemoryRouter } from 'react-router-dom'
import { afterEach,beforeEach,expect,it,vi } from 'vitest'
import { DeleteWebsiteButton,DeploymentCard,type Deployment,type Site } from './Websites'
import { PublicLogin } from './PublicLogin'
import { request } from './api'
vi.mock('./api',()=>({request:vi.fn(),downloadFile:vi.fn(),resetCsrf:vi.fn(),idempotencyKey:()=> 'key'}))
beforeEach(()=>{vi.stubGlobal('ResizeObserver',class{observe(){}unobserve(){}disconnect(){}});vi.stubGlobal('matchMedia',vi.fn(()=>({matches:false,addListener:vi.fn(),removeListener:vi.fn(),addEventListener:vi.fn(),removeEventListener:vi.fn()})))})
afterEach(()=>{cleanup();vi.restoreAllMocks();vi.mocked(request).mockReset();vi.unstubAllGlobals()})
const runtime={kind:'STATIC',root:'',port:8080,buildCommand:'',startCommand:'',outputDirectory:'',healthPath:'/',database:'NONE',frontendRoot:'',apiPrefix:'/api/'}
const deployment:Deployment={id:'deployment',siteId:'site',sourceVersionId:'source',status:'RUNNING',environmentKind:'TEST',suiteVersionId:'suite',runId:'run',runStatus:'COMPLETED',issueCount:1,testPassed:false,published:false,url:'http://site.localhost:8090/',config:runtime,events:[]}
const site:Site={id:'site',projectId:'project',name:'测试网站',purpose:'HOST',suiteVersionId:'suite',hasRepositoryCredential:false,hasEnvironmentSecret:false,liveUrl:'http://live.localhost/',deployments:[deployment]}
function setup(component:React.ReactNode){render(<QueryClientProvider client={new QueryClient({defaultOptions:{queries:{retry:false},mutations:{retry:false}}})}><App><MemoryRouter>{component}</MemoryRouter></App></QueryClientProvider>)}
it('keeps deployment completion separate from permission to publish',()=>{setup(<DeploymentCard deployment={deployment} site={site} refresh={()=>{}}/>);expect(screen.getByRole('button',{name:/确认更新网站/})).toBeDisabled();expect(screen.getByText('需查看检测结果')).toBeInTheDocument();expect(screen.getByRole('link',{name:/查看检测与修复/})).toHaveAttribute('href','/test-runs/run')})
it('opens private preview using an authorized launch request and removes opener access',async()=>{const popup={opener:{},location:{href:''},close:vi.fn()};vi.spyOn(window,'open').mockReturnValue(popup as unknown as Window);vi.mocked(request).mockResolvedValue({url:'http://preview.localhost/__shijie/access/one-use'});setup(<DeploymentCard deployment={deployment} site={site} refresh={()=>{}}/>);fireEvent.click(screen.getByRole('button',{name:/打开网站/}));await waitFor(()=>expect(popup.location.href).toContain('/__shijie/access/'));expect(popup.opener).toBeNull();expect(request).toHaveBeenCalledWith('/deployments/deployment/launch',{method:'POST'})})
it('collects captcha and shows no registration submit until a challenge is loaded',async()=>{vi.mocked(request).mockResolvedValue({id:'captcha-id',image:'data:image/png;base64,a'});setup(<PublicLogin onSuccess={()=>{}}/>);fireEvent.click(screen.getByText('创建账号'));await screen.findByAltText(/注册验证码/);expect(screen.getByRole('button',{name:'创建账号并开始'})).toBeEnabled();expect(screen.getByLabelText('怎么称呼你')).toBeInTheDocument();expect(request).toHaveBeenCalledWith('/auth/captcha')})

it('shows deletion only to project administrators',()=>{setup(<DeleteWebsiteButton site={{...site,canDelete:false}}/>);expect(screen.queryByRole('button',{name:/删除网站/})).not.toBeInTheDocument()})
it('requires confirmation and keeps cancel from deleting or opening the website',async()=>{
  const openSite=vi.fn();setup(<div onClick={openSite}><DeleteWebsiteButton site={{...site,canDelete:true}}/></div>);
  fireEvent.click(screen.getByRole('button',{name:'删除网站：测试网站'}));
  expect(await screen.findByText('删除后无法恢复')).toBeInTheDocument();
  fireEvent.click(screen.getByRole('button',{name:'保留网站'}));
  expect(request).not.toHaveBeenCalled();expect(openSite).not.toHaveBeenCalled();
})
it('submits confirmed deletion once and refreshes the website list',async()=>{
  const onAccepted=vi.fn();vi.mocked(request).mockResolvedValue({accepted:true});
  setup(<DeleteWebsiteButton site={{...site,canDelete:true}} onAccepted={onAccepted}/>);
  fireEvent.click(screen.getByRole('button',{name:'删除网站：测试网站'}));
  fireEvent.click(await screen.findByRole('button',{name:'永久删除网站'}));
  await waitFor(()=>expect(onAccepted).toHaveBeenCalledOnce());
  expect(request).toHaveBeenCalledExactlyOnceWith('/sites/site',{method:'DELETE',headers:{'Idempotency-Key':'key'}});
})
it('keeps a busy website visible and explains how to finish deletion',async()=>{
  vi.mocked(request).mockRejectedValue(new Error('请先取消任务，等待任务结束后再删除'));
  setup(<DeleteWebsiteButton site={{...site,canDelete:true}}/>);
  fireEvent.click(screen.getByRole('button',{name:'删除网站：测试网站'}));
  fireEvent.click(await screen.findByRole('button',{name:'永久删除网站'}));
  expect(await screen.findByText('请先取消任务，等待任务结束后再删除')).toBeInTheDocument();
  await waitFor(()=>expect(screen.getByRole('button',{name:'保留网站'})).toBeEnabled());
})
