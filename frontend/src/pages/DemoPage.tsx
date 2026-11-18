import { useEffect } from 'react'
import { useMutation, useQuery } from '@tanstack/react-query'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { Alert, Button, Card, Space, Spin, Steps, Tag, Typography } from 'antd'
import { request } from '../api'
import type { Scan } from '../types'
import { QueryProblem } from '../workflowShared'
import type { DemoState } from '../workflowShared'

export function DemoPage() {
  const {projectId=''}=useParams(),[params,setParams]=useSearchParams(),nav=useNavigate()
  const versionId=params.get('versionId'),scanId=params.get('scanId'),step=Math.min(5,Math.max(0,Number(params.get('step'))||0))
  const query=useQuery({queryKey:['demo',projectId,versionId,scanId],queryFn:()=>request<DemoState>(`/projects/${projectId}/demo-state?${new URLSearchParams({...versionId?{versionId}:{},...scanId?{scanId}:{}})}`),refetchInterval:3000})
  const state=query.data
  useEffect(()=>{
    if(!state)return
    const next=new URLSearchParams(params)
    if(!versionId&&state.version)next.set('versionId',state.version.id)
    if(!scanId&&state.scan)next.set('scanId',state.scan.id)
    if(next.toString()!==params.toString())setParams(next,{replace:true})
  },[state,versionId,scanId,params,setParams])
  const scan=useMutation({mutationFn:()=>request<Scan>(`/versions/${state!.version!.id}/rescan`,{method:'POST',body:JSON.stringify({pages:[],enableKeyboardTest:true,enableAi:false,aiConsent:false,rules:[]})}),onSuccess:s=>{const next=new URLSearchParams(params);next.set('scanId',s.id);next.set('step','1');setParams(next)}})
  if(query.isError)return <QueryProblem error={query.error} retry={()=>query.refetch()}/>
  if(!state)return <Spin/>
  const sid=state.scan?.id
  const links=[`/projects/${projectId}/import`,sid?`/scans/${sid}/progress`:'',sid?`/scans/${sid}/interaction`:'',state.reviewFindingId?`/findings/${state.reviewFindingId}`:'',state.fixFindingId?`/findings/${state.fixFindingId}`:'',sid?`/scans/${sid}/report`:'']
  const descriptions=['导入静态项目。再次安装示例会创建独立项目，保留历史记录。','执行真实离线扫描，等待完成后查看证据。','切换焦点轨迹与阅读顺序；编号截图和文字表格提供相同信息。','此步骤不再是发布门槛；可按需记录风险、驳回候选或保留自动候选。自然顺序负例可没有候选。','优先选择页面语言模板，审阅 Diff 后应用，等待新版本自动复测。','生成固定快照，再从同一快照导出 PDF 和 JSON。可选审阅更新后可生成新快照。']
  return <><Typography.Title level={2}>{state.project.name} · 演示流程</Typography.Title>
    <Space wrap><Link to="/">项目首页</Link><Link to={`/projects/${projectId}/preview`}>项目预览</Link><Tag>版本 {state.version?`V${state.version.versionNo}`:'未导入'}</Tag><Tag>扫描 {sid||'未开始'}</Tag></Space>
    <Alert type="info" showIcon title="真实操作演示 · 默认离线" description="步骤状态来自业务记录。证据就绪不代表已被查看，报告就绪不代表已下载。AI 是可选展示。"/>
    <Card><Steps current={step} onChange={i=>{const p=new URLSearchParams(params);p.set('step',String(i));setParams(p)}} items={state.steps.map(s=>({title:s.title,description:({DONE:'已完成',READY:'已就绪',NOT_APPLICABLE:'本次不适用',PENDING:'待操作'}[s.status]),status:s.status==='DONE'?'finish':s.status==='READY'?'process':'wait'}))}/></Card>
    <Card title={state.steps[step].title}><Typography.Paragraph>{descriptions[step]}</Typography.Paragraph><Space wrap>
      {step===1&&<Button type="primary" loading={scan.isPending} disabled={!state.version||['PENDING','RUNNING'].includes(state.scan?.status||'')} onClick={()=>scan.mutate()}>开始新的离线扫描</Button>}
      {links[step]&&<Button onClick={()=>nav(`${links[step]}?demo=${encodeURIComponent(`/projects/${projectId}/demo?${params}`)}`)}>打开操作页面</Button>}
      {!links[step]&&<Typography.Text>先完成扫描，或此扫描没有适用问题。</Typography.Text>}
      <Button disabled={step===5} onClick={()=>{const p=new URLSearchParams(params);p.set('step',String(step+1));setParams(p)}}>下一步讲解</Button>
    </Space>{scan.isError&&<QueryProblem error={scan.error} retry={()=>scan.mutate()}/>}</Card>
    {state.scan&&<Alert type={state.scan.status==='COMPLETED'?'success':'info'} title={`扫描状态：${state.scan.status} · ${state.scan.progress}%`} description={state.scan.errorMessage}/>}
    {state.afterScanId&&<Card title="修复成果"><Space wrap><Link to={`/scans/${state.afterScanId}/progress`}>查看自动复测</Link><Link to={`/projects/${projectId}/comparison?beforeScanId=${state.beforeScanId}&afterScanId=${state.afterScanId}`}>打开前后证据对照</Link><Link to={`/scans/${state.afterScanId}/report`}>生成复测报告</Link></Space></Card>}
  </>
}
