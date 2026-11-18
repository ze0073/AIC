import '@testing-library/jest-dom/vitest'
import {act, cleanup, fireEvent, render, screen, waitFor} from '@testing-library/react'
import {App as AntApp, ConfigProvider} from 'antd'
import {QueryClient, QueryClientProvider} from '@tanstack/react-query'
import {MemoryRouter} from 'react-router-dom'
import {afterEach, beforeAll, beforeEach, describe, expect, it, vi} from 'vitest'
import {request} from './api'
import {profileKey, UserCenterPage, validPhone} from './UserCenter'
import type {UserProfile} from './types'

vi.mock('./api', ()=>({request:vi.fn()}))
const original:UserProfile = {id:'a',username:'alice',displayName:'Alice',enabled:true,platformRole:'USER',createdAt:'2026-10-03T12:00:00',email:'alice@example.com',phone:'',bio:'Hello'}
let cache:QueryClient
beforeAll(()=>{
  vi.stubGlobal('ResizeObserver', class {observe(){} unobserve(){} disconnect(){}})
  vi.stubGlobal('matchMedia', vi.fn().mockImplementation((query:string)=>({matches:false,media:query,onchange:null,addListener:vi.fn(),removeListener:vi.fn(),addEventListener:vi.fn(),removeEventListener:vi.fn(),dispatchEvent:vi.fn()})))
  window.getComputedStyle = new Proxy(window.getComputedStyle, {apply:(target,thisArg,args)=>Reflect.apply(target,thisArg,[args[0]])})
  URL.createObjectURL = vi.fn(()=> 'blob:avatar-preview')
  URL.revokeObjectURL = vi.fn()
})
beforeEach(()=>{
  cache = new QueryClient({defaultOptions:{queries:{retry:false,staleTime:Infinity},mutations:{retry:false}}})
  cache.setQueryData(['auth-me'], original)
  vi.mocked(request).mockReset().mockImplementation(async(path, init)=>{
    if (path==='/auth/profile' && init?.method==='PUT') return {...original,...JSON.parse(init.body as string)} as never
    if (path==='/auth/profile') return {...original} as never
    if (path==='/auth/quota') return {projectLimit:3,environmentLimit:2,buildLimit:1} as never
    return undefined as never
  })
})
afterEach(()=>{cleanup(); cache.clear()})
function mount(path='/profile') {
  return render(<ConfigProvider><AntApp><QueryClientProvider client={cache}><MemoryRouter initialEntries={[path]}><UserCenterPage user={original}/></MemoryRouter></QueryClientProvider></AntApp></ConfigProvider>)
}
async function ready() {await screen.findByDisplayValue('Alice')}

describe('UserCenter', ()=>{
  it('loads own editable profile alongside readonly account information and quota', async()=>{
    mount(); await ready()
    expect(screen.getByText('alice')).toBeInTheDocument()
    expect(screen.getByDisplayValue('alice@example.com')).toBeInTheDocument()
    expect(screen.getByText('项目数量上限')).toBeInTheDocument()
    expect(screen.getByRole('button',{name:'保存资料'})).toBeDisabled()
    expect(screen.getAllByText('仅作联系资料，不用于登录或找回密码')).toHaveLength(2)
  })
  it('saves only editable fields and synchronizes the session display name', async()=>{
    mount(); await ready()
    fireEvent.change(screen.getByLabelText('显示名称'),{target:{value:'  新名称  '}})
    fireEvent.change(screen.getByLabelText('联系邮箱'),{target:{value:''}})
    fireEvent.click(screen.getByRole('button',{name:'保存资料'}))
    await waitFor(()=>expect(cache.getQueryData<UserProfile>(['auth-me'])?.displayName).toBe('新名称'))
    const call = vi.mocked(request).mock.calls.find(([path,init])=>path==='/auth/profile'&&init?.method==='PUT')!
    expect(JSON.parse(call[1]!.body as string)).toEqual({displayName:'新名称',email:'',phone:'',bio:'Hello'})
    expect(screen.getByRole('button',{name:'保存资料'})).toBeDisabled()
  })
  it('keeps entered values and prior saved data when saving fails', async()=>{
    mount(); await ready()
    vi.mocked(request).mockRejectedValueOnce(new Error('保存暂时失败'))
    fireEvent.change(screen.getByLabelText('显示名称'),{target:{value:'Edited'}})
    fireEvent.click(screen.getByRole('button',{name:'保存资料'}))
    await screen.findByText('保存暂时失败')
    expect(screen.getByDisplayValue('Edited')).toBeInTheDocument()
    expect(cache.getQueryData<UserProfile>(profileKey('a'))?.displayName).toBe('Alice')
    await waitFor(()=>expect(screen.getByRole('button',{name:'保存资料'})).toBeEnabled())
  })
  it('blocks invalid email and phone before sending a write request', async()=>{
    mount(); await ready()
    fireEvent.change(screen.getByLabelText('联系邮箱'),{target:{value:'bad-address'}})
    fireEvent.change(screen.getByLabelText('手机号'),{target:{value:'123'}})
    fireEvent.click(screen.getByRole('button',{name:'保存资料'}))
    await screen.findByText('请输入有效邮箱')
    expect(screen.getByText('请输入有效手机号，可包含国际区号、空格和连字符')).toBeInTheDocument()
    expect(vi.mocked(request).mock.calls.filter(([,init])=>init?.method==='PUT')).toHaveLength(0)
  })
  it('does not write a late save response into another account session', async()=>{
    mount(); await ready()
    let finish!:(value:UserProfile)=>void
    vi.mocked(request).mockImplementationOnce(()=>new Promise(resolve=>{finish=resolve}) as never)
    fireEvent.change(screen.getByLabelText('显示名称'),{target:{value:'Edited'}})
    fireEvent.click(screen.getByRole('button',{name:'保存资料'}))
    await waitFor(()=>expect(finish).toBeDefined())
    cache.setQueryData(['auth-me'],{...original,id:'b',username:'bob',displayName:'Bob'})
    await act(async()=>{finish({...original,displayName:'Edited'})})
    expect(cache.getQueryData<UserProfile>(['auth-me'])?.displayName).toBe('Bob')
    expect(cache.getQueryData<UserProfile>(profileKey('a'))?.displayName).toBe('Alice')
  })
  it('opens the password dialog from the shortcut and sends no confirmation field', async()=>{
    mount('/profile?password=1'); await ready()
    await screen.findByText('修改登录密码')
    fireEvent.change(screen.getByLabelText('当前密码'),{target:{value:'old-password'}})
    fireEvent.change(screen.getByLabelText('新密码'),{target:{value:'new-password-123'}})
    fireEvent.change(screen.getByLabelText('确认新密码'),{target:{value:'new-password-123'}})
    fireEvent.click(screen.getByRole('button',{name:'保存新密码'}))
    await waitFor(()=>expect(vi.mocked(request).mock.calls.some(([path])=>path==='/auth/password')).toBe(true))
    const call = vi.mocked(request).mock.calls.find(([path])=>path==='/auth/password')!
    expect(JSON.parse(call[1]!.body as string)).toEqual({currentPassword:'old-password',newPassword:'new-password-123'})
  })
  it('preserves unsaved text while saving an avatar independently', async()=>{
    const view = mount(); await ready()
    fireEvent.change(screen.getByLabelText('显示名称'),{target:{value:'Unsaved Name'}})
    const file = new File(['image-bytes'],'avatar.png',{type:'image/png'})
    fireEvent.change(view.container.querySelector('input[type="file"]')!,{target:{files:[file]}})
    const upload = await screen.findByRole('button',{name:'上传并保存头像'})
    vi.mocked(request).mockResolvedValueOnce({...original,avatarUrl:'/api/auth/avatar?v=1'})
    fireEvent.click(upload)
    await screen.findByText('头像已保存')
    expect(screen.getByDisplayValue('Unsaved Name')).toBeInTheDocument()
    expect(cache.getQueryData<UserProfile>(['auth-me'])?.displayName).toBe('Alice')
    expect(cache.getQueryData<UserProfile>(profileKey('a'))?.avatarUrl).toBe('/api/auth/avatar?v=1')
    const call = vi.mocked(request).mock.calls.find(([path])=>path==='/auth/avatar')!
    expect(call[1]?.body).toBeInstanceOf(FormData)
    expect((call[1]?.body as FormData).get('file')).toBe(file)
    await waitFor(()=>expect(screen.getByRole('button',{name:'保存资料'})).toBeEnabled())
  })
  it('does not send a password request when confirmation differs', async()=>{
    mount('/profile?password=1'); await ready()
    fireEvent.change(screen.getByLabelText('当前密码'),{target:{value:'old-password'}})
    fireEvent.change(screen.getByLabelText('新密码'),{target:{value:'new-password-123'}})
    fireEvent.change(screen.getByLabelText('确认新密码'),{target:{value:'different'}})
    fireEvent.click(screen.getByRole('button',{name:'保存新密码'}))
    await screen.findByText('两次输入的新密码不一致')
    expect(vi.mocked(request).mock.calls.some(([path])=>path==='/auth/password')).toBe(false)
  })
  it('retries quota without replacing editable profile values', async()=>{
    vi.mocked(request).mockImplementation(async(path)=>{
      if (path==='/auth/quota') throw new Error('quota unavailable')
      return {...original} as never
    })
    mount(); await ready()
    fireEvent.change(screen.getByLabelText('显示名称'),{target:{value:'Unsaved Name'}})
    await screen.findByText('额度加载失败')
    vi.mocked(request).mockResolvedValueOnce({projectLimit:5,environmentLimit:3,buildLimit:2})
    fireEvent.click(screen.getByRole('button',{name:/重\s*试/}))
    await screen.findByText('项目数量上限')
    expect(screen.getByDisplayValue('Unsaved Name')).toBeInTheDocument()
  })
  it('accepts international contact numbers and rejects malformed ones', ()=>{
    expect(validPhone('+86 138-0013-8000')).toBe(true)
    expect(validPhone('')).toBe(true)
    expect(validPhone('123')).toBe(false)
    expect(validPhone('1e13800138000')).toBe(false)
    expect(validPhone('+1234567890123456')).toBe(false)
  })
})
