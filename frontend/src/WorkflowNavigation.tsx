import { useEffect, useRef } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { Card, Space, Typography } from 'antd'
import { request } from './api'
import type { Scan, Finding, Patch } from './types'
// Resolve navigation context from the actual resource; never infer a scan from the current version pointer.
export function WorkflowContext() {
  const location=useLocation(),nav=useNavigate()
  const match=location.pathname.match(/^\/(scans|findings|fix-patches)\/([^/]+)/)
  const resource=useQuery({queryKey:['context-resource',match?.[1],match?.[2]],queryFn:async()=>{
    if(match![1]==='scans')return request<Scan>(`/scans/${match![2]}`)
    let findingId=match![2]
    if(match![1]==='fix-patches'){const patch=await request<Patch>(`/fix-patches/${findingId}`);findingId=patch.findingId}
    const d=await request<{finding:Finding}>(`/findings/${findingId}`)
    return request<Scan>(`/scans/${d.finding.scanTaskId}`)
  },enabled:!!match})
  const scan=resource.data,projectId=scan?.projectId||location.pathname.match(/^\/projects\/([^/]+)/)?.[1]
  const params=new URLSearchParams(location.search),demo=params.get('demo')
  const saved=useRef<string|null>(null)
  const isDemo=location.pathname.endsWith('/demo')
  if(isDemo&&(params.has('versionId')||params.has('scanId')))saved.current=location.pathname+location.search
  if(demo&&/^\/projects\/[^/]+\/demo\?/.test(demo))saved.current=demo
  const back=saved.current?.startsWith(`/projects/${projectId}/demo?`)?saved.current:undefined
  useEffect(()=>{
    if(!projectId||isDemo||!back||demo)return
    const p=new URLSearchParams(location.search);p.set('demo',back)
    nav(`${location.pathname}?${p}`,{replace:true})
  },[location.pathname,location.search,back,demo,nav,projectId,isDemo])
  if(!projectId)return null
  return <Card size="small" className="workflow-context"><Space wrap><Link to={`/projects/${projectId}/preview`}>项目预览</Link><Link to={back||`/projects/${projectId}/demo${scan?`?versionId=${scan.versionId}&scanId=${scan.id}`:''}`}>返回演示流程</Link><Link to={`/projects/${projectId}/comparison`}>前后对照</Link><Link to={`/projects/${projectId}/versions`}>版本管理</Link>{scan&&<Typography.Text type="secondary">扫描 {scan.id} · {scan.status}</Typography.Text>}</Space></Card>
}
