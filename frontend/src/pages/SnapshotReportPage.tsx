import { useEffect, useState } from 'react'
import { useMutation, useQuery } from '@tanstack/react-query'
import { Link, useParams, useSearchParams } from 'react-router-dom'
import { Alert, Button, Card, Descriptions, Empty, Select, Space, Spin, Table, Typography } from 'antd'
import { idempotencyKey, request } from '../api'
import { QueryProblem, conclusion } from '../workflowShared'
import type { Summary, Frozen } from '../workflowShared'

export function SnapshotReportPage() {
  const {scanId=''}=useParams(),[params,setParams]=useSearchParams(),[key,setKey]=useState(idempotencyKey)
  const id=params.get('snapshotId')||''
  const list=useQuery({queryKey:['snapshots',scanId],queryFn:()=>request<Summary[]>(`/scans/${scanId}/report-snapshots`)})
  const frozen=useQuery({queryKey:['snapshot',id],queryFn:()=>request<Frozen>(`/report-snapshots/${id}`),enabled:!!id})
  const create=useMutation({mutationFn:()=>request<Frozen>(`/scans/${scanId}/report-snapshots`,{method:'POST',headers:{'Idempotency-Key':key}}),onSuccess:r=>{const p=new URLSearchParams(params);p.set('snapshotId',r.snapshotId);setParams(p);setKey(idempotencyKey());list.refetch()}})
  const download=useMutation({mutationFn:async({snapshotId,format}:{snapshotId:string;format:'pdf'|'json'})=>{
    const response=await fetch(`/api/report-snapshots/${snapshotId}/export?format=${format}`)
    if(!response.ok){const failure=await response.json().catch(()=>null);throw new Error(failure?.message||'导出失败，可从同一快照重试')}
    const blob=await response.blob(),url=URL.createObjectURL(blob),link=document.createElement('a')
    link.href=url;link.download=`accessibility-report-${snapshotId}.${format}`;link.click();setTimeout(()=>URL.revokeObjectURL(url),1000);list.refetch()
  }})
  useEffect(()=>{if(!id&&list.data?.length){const p=new URLSearchParams(params);p.set('snapshotId',list.data[0].snapshotId);setParams(p,{replace:true})}},[id,list.data,params,setParams])
  const data=frozen.data,r=data?.report
  return <><Typography.Title level={2}>固定报告快照</Typography.Title><Link to={`/scans/${scanId}/findings`}>返回扫描结果</Link>
    <Alert type="info" showIcon title="每份报告固定记录生成时的结论" description="后续复核不会修改旧报告；PDF 和 JSON 均引用当前快照。分数越高表示当前规则下检出风险越少。"/>
    <Card><Space wrap><Button type="primary" loading={create.isPending} onClick={()=>create.mutate()}>生成新的报告快照</Button><Select aria-label="历史报告快照" placeholder="选择已生成快照" style={{minWidth:300}} value={id||undefined} options={(list.data||[]).map(s=>({value:s.snapshotId,label:`${s.generatedAt} · ${s.snapshotId.slice(-6)}`}))} onChange={v=>{const p=new URLSearchParams(params);p.set('snapshotId',v);setParams(p)}}/></Space></Card>
    {list.isError&&<QueryProblem error={list.error} retry={()=>list.refetch()}/>}{create.isError&&<QueryProblem error={create.error} retry={()=>create.mutate()}/>}{frozen.isError&&<QueryProblem error={frozen.error} retry={()=>frozen.refetch()}/>}{frozen.isFetching&&<Spin/>}
    {download.isError&&<QueryProblem error={download.error} retry={()=>download.mutate(download.variables!)}/>}
    {!id&&!list.isLoading&&<Empty description="尚无固定报告，请先生成快照"/>}
    {r&&data&&(r.scan.id!==scanId?<Alert type="error" title="报告快照与当前扫描不匹配"/>:<>
      <Card title={`${r.project.name} · V${r.version.versionNo}`}><Descriptions column={1} items={[{key:'id',label:'快照 ID',children:data.snapshotId},{key:'time',label:'生成时间',children:data.generatedAt},{key:'hash',label:'内容哈希',children:<Typography.Text style={{wordBreak:'break-all'}}>{data.contentHash}</Typography.Text>},{key:'score',label:'内部风险分',children:`${r.scan.riskScore??'-'} / 100`},{key:'count',label:'成果摘要',children:`结果 ${r.scan.foundCount} · 自动候选 ${r.scan.reviewCount} · 高 ${r.scan.highCount} / 中 ${r.scan.mediumCount} / 低 ${r.scan.lowCount}`} ]}/><Typography.Paragraph>{r.formula}</Typography.Paragraph><Space><Button loading={download.isPending} onClick={()=>download.mutate({snapshotId:id,format:'pdf'})}>导出本快照 PDF</Button><Button loading={download.isPending} onClick={()=>download.mutate({snapshotId:id,format:'json'})}>导出本快照 JSON</Button></Space></Card>
      {r.comparison?.details&&<Card title="快照中的前后对比"><Typography.Paragraph>未再检出 {r.comparison.details.notRedetected} · 仍存在 {r.comparison.details.remaining} · 新增 {r.comparison.details.introduced} · 复核变化 {r.comparison.details.reviewChanged}</Typography.Paragraph><Link to={`/projects/${r.project.id}/comparison?beforeScanId=${r.comparison.details.before.id}&afterScanId=${r.comparison.details.after.id}`}>查看两次扫描的当前证据对照（可能包含后续复核）</Link></Card>}
      <Card title="问题与自动候选"><Table rowKey="id" dataSource={r.findings} columns={[{title:'规则',dataIndex:'ruleId'},{title:'类别',dataIndex:'status',render:conclusion},{title:'页面',dataIndex:'pagePath'},{title:'问题',dataIndex:'title'}]}/></Card>
      <Card title="复核记录"><Table rowKey="id" dataSource={Object.values(r.reviews).flat()} columns={[{title:'操作',dataIndex:'action'},{title:'说明',dataIndex:'comment'},{title:'结论复用',dataIndex:'reused',render:v=>v?'是':'否'},{title:'时间',dataIndex:'createdAt'}]}/></Card>
      <Card title="修复与复测"><Table rowKey="id" dataSource={r.patches} columns={[{title:'文件',dataIndex:'targetFile'},{title:'状态',dataIndex:'status'},{title:'采用文本',dataIndex:'candidateText'},{title:'差异',dataIndex:'diffText',render:v=><pre className="json-view">{v}</pre>}]}/></Card>
      <Alert type="warning" title={r.disclaimer}/>
    </>)}
  </>
}
