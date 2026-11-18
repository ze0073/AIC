import {afterEach,expect,it,vi} from 'vitest'
import {act,cleanup,fireEvent,render,screen,waitFor} from '@testing-library/react'
import {QueryClient,QueryClientProvider} from '@tanstack/react-query'
import SessionApp from './SessionApp'
import {ApiError,request} from './api'
import {MemoryRouter} from 'react-router-dom'
import {resetWorkspaceRailVisits} from './workspaceRailState'
vi.mock('./workspaceRailState',()=>({resetWorkspaceRailVisits:vi.fn()}))
vi.mock('./api',async()=>{const actual=await vi.importActual<typeof import('./api')>('./api');return {...actual,request:vi.fn()}})
vi.mock('./App',()=>({default:({loginEntrance,onLoginEntranceConsumed}:{loginEntrance?:boolean;onLoginEntranceConsumed?:()=>void})=> <div>已登录工作台{loginEntrance&&<button onClick={onLoginEntranceConsumed}>消耗登录动画</button>}</div>}))
vi.mock('./PublicLogin',()=>({PublicLogin:({onSuccess}:{onSuccess:()=>void})=><button onClick={onSuccess}>测试登录</button>}))
vi.mock('./BrowserLoginBoot',()=>({BrowserLoginBoot:({onReady}:{onReady:()=>void})=><button onClick={onReady}>完成浏览器加载</button>}))
afterEach(()=>{cleanup();vi.resetAllMocks()})
function mount(path='/'){const cache=new QueryClient({defaultOptions:{queries:{retry:false}}});render(<MemoryRouter initialEntries={[path]}><QueryClientProvider client={cache}><SessionApp/></QueryClientProvider></MemoryRouter>);return cache}
it('settles an initial 401 into a usable login screen',async()=>{
  vi.mocked(request).mockRejectedValue(new ApiError('UNAUTHORIZED','请先登录'))
  mount();fireEvent.click(await screen.findByRole('button',{name:'完成浏览器加载'}));expect(await screen.findByRole('button',{name:'测试登录'})).toBeTruthy()
})
it('offers retry after connection failure and then recovers',async()=>{
  vi.mocked(request).mockRejectedValueOnce(new Error('连接中断')).mockRejectedValue(new ApiError('UNAUTHORIZED','请先登录'))
  mount();fireEvent.click(await screen.findByRole('button',{name:/重\s*试/}));fireEvent.click(await screen.findByRole('button',{name:'完成浏览器加载'}));expect(await screen.findByRole('button',{name:'测试登录'})).toBeTruthy()
})
it('revocation clears private data, preserves the auth observer and allows a new login',async()=>{
  vi.mocked(request).mockResolvedValue({id:'first-user'})
  const cache=mount();await screen.findByText('已登录工作台');cache.setQueryData(['private-project'],{secret:'private'})
  fireEvent(window,new Event('auth-required'));await screen.findByRole('button',{name:'测试登录'})
  expect(screen.queryByRole('button',{name:'完成浏览器加载'})).toBeNull()
  expect(cache.getQueryData(['private-project'])).toBeUndefined()
  vi.mocked(request).mockResolvedValue({id:'next-user'})
  fireEvent.click(screen.getByRole('button',{name:'测试登录'}));expect(await screen.findByText('已登录工作台')).toBeTruthy()
  expect(await screen.findByRole('button',{name:'消耗登录动画'})).toBeTruthy()
})

it('does not arm an entrance when restoring an existing authenticated session',async()=>{
  vi.mocked(request).mockResolvedValue({id:'existing-user'})
  mount();await screen.findByText('已登录工作台')
  expect(screen.queryByRole('button',{name:'消耗登录动画'})).toBeNull()
  expect(resetWorkspaceRailVisits).not.toHaveBeenCalled()
})
it('arms the entrance once after successful login and not after auth refetch',async()=>{
  vi.mocked(request).mockRejectedValueOnce(new ApiError('UNAUTHORIZED','请先登录')).mockResolvedValue({id:'logged-in-user'})
  const cache=mount()
  fireEvent.click(await screen.findByRole('button',{name:'完成浏览器加载'}))
  fireEvent.click(await screen.findByRole('button',{name:'测试登录'}))
  fireEvent.click(await screen.findByRole('button',{name:'消耗登录动画'}))
  expect(resetWorkspaceRailVisits).toHaveBeenCalledTimes(1)
  await waitFor(()=>expect(screen.queryByRole('button',{name:'消耗登录动画'})).toBeNull())
  await act(async()=>{await cache.invalidateQueries({queryKey:['auth-me']})})
  expect(screen.queryByRole('button',{name:'消耗登录动画'})).toBeNull()
})
it('does not defer a deep-link login entrance until a later homepage visit',async()=>{
  vi.mocked(request).mockRejectedValueOnce(new ApiError('UNAUTHORIZED','请先登录')).mockResolvedValue({id:'deep-link-user'})
  mount('/sites')
  fireEvent.click(await screen.findByRole('button',{name:'完成浏览器加载'}))
  fireEvent.click(await screen.findByRole('button',{name:'测试登录'}))
  await screen.findByText('已登录工作台')
  expect(screen.queryByRole('button',{name:'消耗登录动画'})).toBeNull()
})

it('shows browser loading before the initial login form, and only once in that document',async()=>{
  vi.mocked(request).mockRejectedValue(new ApiError('UNAUTHORIZED','请先登录'))
  mount()
  const done=await screen.findByRole('button',{name:'完成浏览器加载'})
  expect(screen.queryByRole('button',{name:'测试登录'})).toBeNull()
  fireEvent.click(done)
  await screen.findByRole('button',{name:'测试登录'})
  fireEvent(window,new Event('auth-required'))
  expect(screen.queryByRole('button',{name:'完成浏览器加载'})).toBeNull()
})
