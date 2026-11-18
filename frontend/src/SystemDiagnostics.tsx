import {useState} from 'react'
import {useMutation,useQuery} from '@tanstack/react-query'
import {Alert,Button,Card,Checkbox,Descriptions,Space,Table,Tag,Typography} from 'antd'
import {request} from './api'
import {VisualBanner} from './VisualBanner'
type Check={id:string;label:string;status:string;message:string}
type Verification={status:string;checkedAt?:string;message:string}
type Diagnostics={checks:Check[];gatewayBindHost:string;gatewayMessage:string;regressionTimeZone:string;assessedAt:string;queue:{scans:number;runs:number;agents:number};model:{configured:boolean;provider:string;model:string;verification:Verification}}
export function SystemDiagnosticsPage(){
  const [consent,setConsent]=useState(false)
  const data=useQuery({queryKey:['system-diagnostics'],queryFn:({signal})=>request<Diagnostics>('/admin/system/status',{signal,timeoutMs:40_000}),retry:false})
  const check=useMutation({mutationFn:()=>request<Verification>('/admin/system/model-check',{method:'POST',body:JSON.stringify({aiConsent:consent}),timeoutMs:35_000}),onSuccess:()=>void data.refetch()})
  return <><VisualBanner theme="system" compact title="运行诊断" description="检查本机依赖与任务状态，配置方式见项目启动说明。" actions={<Button type="primary" loading={data.isFetching} onClick={()=>void data.refetch()}>刷新诊断</Button>}/>
    {data.isError?<Alert type="error" title="诊断读取失败" description={data.error.message} action={<Button onClick={()=>void data.refetch()}>重试</Button>}/>:<>
      <Card title="运行依赖"><Table loading={data.isPending} rowKey="id" dataSource={data.data?.checks} pagination={false} columns={[{title:'依赖',dataIndex:'label'},{title:'状态',render:(_,c:Check)=><Tag color={c.status==='READY'?'green':'orange'}>{c.status==='READY'?'就绪':'需要处理'}</Tag>},{title:'说明',dataIndex:'message'}]}/></Card>
      {data.data&&<><Card title="入口与任务" style={{marginTop:16}}><Descriptions column={1} items={[{key:'bind',label:'网站入口监听',children:data.data.gatewayBindHost},{key:'gateway',label:'入口状态',children:data.data.gatewayMessage},{key:'zone',label:'回归执行时区',children:data.data.regressionTimeZone},{key:'queue',label:'等待或运行任务',children:`静态扫描 ${data.data.queue.scans} · 在线检测 ${data.data.queue.runs} · 智能任务 ${data.data.queue.agents}`},{key:'time',label:'检查时间',children:new Date(data.data.assessedAt).toLocaleString('zh-CN')}]}/></Card>
      <Card title="Qwen 模型" style={{marginTop:16}}><Space wrap><Tag>{data.data.model.configured?'配置已存在':'尚未配置'}</Tag><Tag color={data.data.model.verification.status==='VERIFIED'?'green':'default'}>{data.data.model.verification.status==='VERIFIED'?'本次进程真实调用已通过':'尚无有效调用验证'}</Tag><span>{data.data.model.provider} / {data.data.model.model}</span></Space><p>{check.data?.message||data.data.model.verification.message}</p>
        <Checkbox checked={consent} onChange={e=>setConsent(e.target.checked)}>同意向已配置模型发送一句功能说明请求；不包含项目数据，可能产生模型费用</Checkbox><div style={{marginTop:12}}><Button disabled={!consent||!data.data.model.configured} loading={check.isPending} onClick={()=>check.mutate()}>验证真实模型调用</Button></div>{check.isError&&<Alert type="warning" title="模型验证暂未完成" description={check.error.message}/>}</Card></>}
    </>}
  </>
}
