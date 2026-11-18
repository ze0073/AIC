import {lazy,Suspense,useCallback,useEffect,useState} from 'react'
import {useQuery,useQueryClient} from '@tanstack/react-query'
import {Alert,Button,Spin} from 'antd'
import {request,ApiError,resetCsrf} from './api'
import type {AuthUser} from './types'
import {PageBoundary} from './PageBoundary'
import {useLocation} from 'react-router-dom'
import {resetWorkspaceRailVisits} from './workspaceRailState'
import {BrowserLoginBoot} from './BrowserLoginBoot'
import {prepareBrowserLogin,type BootPreparer} from './loginBootResources'
const LoggedInApp=lazy(()=>import('./App'))
const loadLogin=()=>import('./PublicLogin').then(m=>({default:m.PublicLogin}))
const Login=lazy(loadLogin)
const prepareLogin:BootPreparer=(notify,signal)=>prepareBrowserLogin(loadLogin,notify,signal)
export default function SessionApp(){
  const cache=useQueryClient()
  const location=useLocation()
  const [loginEntrance,setLoginEntrance]=useState(false)
  const [browserLoginBoot,setBrowserLoginBoot]=useState(true)
  const finishBrowserLoginBoot=useCallback(()=>setBrowserLoginBoot(false),[])
  const consumeLoginEntrance=useCallback(()=>setLoginEntrance(false),[])
  const session=useQuery({queryKey:['auth-me'],queryFn:async({signal})=>{
    try{return await request<AuthUser>('/auth/me',{signal})}catch(error){
      if(error instanceof ApiError&&['UNAUTHORIZED','SESSION_REVOKED'].includes(error.code))return null
      throw error
    }
  },retry:false})
  useEffect(()=>{if(session.data)setBrowserLoginBoot(false)},[session.data])
  useEffect(()=>{const revoke=()=>{setLoginEntrance(false);void cache.cancelQueries({predicate:q=>q.queryKey[0]!=='auth-me'});resetCsrf();cache.removeQueries({predicate:q=>q.queryKey[0]!=='auth-me'});cache.setQueryData(['auth-me'],null)};window.addEventListener('auth-required',revoke);return()=>window.removeEventListener('auth-required',revoke)},[cache])
  if(session.isPending)return <div className="session-loading"><Spin size="large" aria-label="加载登录状态"/></div>
  const unauthorized=session.error instanceof ApiError&&['UNAUTHORIZED','SESSION_REVOKED'].includes(session.error.code)
  if(session.isError&&!unauthorized)return <div className="session-loading"><Alert type="error" showIcon title="暂时无法连接平台" description={session.error.message} action={<Button onClick={()=>void session.refetch()}>重试</Button>}/></div>
  return <PageBoundary><Suspense fallback={<div className="session-loading"><Spin size="large" aria-label="加载页面"/></div>}>{session.data?<LoggedInApp loginEntrance={loginEntrance} onLoginEntranceConsumed={consumeLoginEntrance}/>:browserLoginBoot?<BrowserLoginBoot prepare={prepareLogin} onReady={finishBrowserLoginBoot}/>:<Login onSuccess={()=>{resetWorkspaceRailVisits();setLoginEntrance(location.pathname==='/');void cache.invalidateQueries({queryKey:['auth-me']})}}/>}</Suspense></PageBoundary>
}
