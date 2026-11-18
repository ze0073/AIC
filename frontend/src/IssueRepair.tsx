import { useEffect, useRef, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Alert, App as AntApp, Button, Card, Checkbox, Empty, Input, Progress, Select, Space, Spin, Steps, Tabs, Tag, Upload } from 'antd'
import { ArrowLeftOutlined, CheckCircleOutlined, CloudUploadOutlined, CodeOutlined, DownloadOutlined, FileSearchOutlined, PlayCircleOutlined, SafetyCertificateOutlined, ThunderboltOutlined, ToolOutlined } from '@ant-design/icons'
import { Link, useParams, useSearchParams } from 'react-router-dom'
import { downloadFile, idempotencyKey, request } from './api'
import {applyIssueRepair} from './businessApi'
import type { Version } from './types'
import { HostedRepairDelivery } from './Websites'

type RepairSummary = { id:string; status:string; targetFile:string; mode:string; createdAt:string }
export type Workspace = { issue:{ id:string; projectId:string; runId:string; title:string; description:string; source:string }; versions:Version[]; repairs:RepairSummary[]; aiEnabled:boolean; aiUnavailableReason:string; hostedSiteId?:string; deployedSourceVersionId?:string }
export type SourceFile = { path:string; content:string; sha:string; templateRule?:string }
type Notes = { summary:string; reason:string; benefit:string; prevention:string }
export type Repair = { id:string; issueId:string; baseVersionId:string; newVersionId?:string; targetFile:string; status:string; mode:string; content:string; diffText:string; explanation:Notes; createdAt:string; hostedDeploymentError?:string; staticScan?:{ id:string; status:string; progress:number; foundCount:number }; retest?:{ id:string; status:string; issues:Array<{title:string}>; errorMessage?:string; modules:Array<{status:string; moduleType:string}> } }
const emptyNotes:Notes={summary:'',reason:'',benefit:'',prevention:''}
const statusNames:Record<string,string>={PENDING:'待确认',APPLIED:'已应用，待复测',VERIFIED:'在线复测已验证',RUNNING:'检查中',COMPLETED:'已完成',PARTIAL:'部分完成',FAILED:'未完成',CANCELLED:'已取消'}
const terminal=new Set(['COMPLETED','PARTIAL','FAILED','CANCELLED'])

export function IssueRepairPage(){
  const {issueId=''}=useParams()
  return <RepairWorkspace key={issueId} issueId={issueId}/>
}

function RepairWorkspace({issueId}:{issueId:string}){
  const {message}=AntApp.useApp(),cache=useQueryClient(),[params,setParams]=useSearchParams()
  const [chosenVersion,setChosenVersion]=useState(''),[chosenPath,setChosenPath]=useState('')
  const restoredEdit=useRef<{versionId:string;path:string;content:string}|undefined>(undefined)
  const [editor,setEditor]=useState(''),[mode,setMode]=useState('MANUAL'),[notes,setNotes]=useState<Notes>(emptyNotes)
  const [editorVisible,setEditorVisible]=useState(false)
  const [aiConsent,setAiConsent]=useState(false),[reviewAccepted,setReviewAccepted]=useState(false),[deployed,setDeployed]=useState(false)
  const [language,setLanguage]=useState('zh-CN'),[candidateText,setCandidateText]=useState(''),[decorative,setDecorative]=useState(false),[linkedRun,setLinkedRun]=useState('')
  const repairId=params.get('repairId')||''
  const workspace=useQuery({queryKey:['repair-workspace',issueId],queryFn:()=>request<Workspace>(`/issues/${issueId}/repair-workspace`)})
  const repair=useQuery({queryKey:['issue-repair',repairId],enabled:!!repairId,queryFn:()=>request<Repair>(`/issue-repairs/${repairId}`),
    refetchInterval:query=>{const r=query.state.data;return r&&((workspace.data?.hostedSiteId&&r.status==='APPLIED')||(r.staticScan&&!terminal.has(r.staticScan.status))||(r.retest&&!terminal.has(r.retest.status)))?2000:false}})
  const versionId=chosenVersion||repair.data?.baseVersionId||workspace.data?.deployedSourceVersionId||workspace.data?.versions[0]?.id||''
  const files=useQuery({queryKey:['repair-files',issueId,versionId],enabled:!!versionId,queryFn:()=>request<string[]>(`/issues/${issueId}/repair-files?versionId=${encodeURIComponent(versionId)}`)})
  const entry=workspace.data?.versions.find(v=>v.id===versionId)?.entryPage
  const path=chosenPath||(files.data?.includes(entry||'')?entry:files.data?.[0])||''
  const source=useQuery({queryKey:['repair-file',issueId,versionId,path],enabled:!!versionId&&!!path,queryFn:()=>request<SourceFile>(`/issues/${issueId}/repair-file?versionId=${encodeURIComponent(versionId)}&path=${encodeURIComponent(path)}`)})
  useEffect(()=>{setEditor(restoredEdit.current?.versionId===versionId&&restoredEdit.current.path===path?restoredEdit.current.content:source.data?.content||'');setMode('MANUAL');setNotes(emptyNotes);setAiConsent(false)},[source.data,versionId,path])
  useEffect(()=>{setReviewAccepted(false);setDeployed(false)},[repairId])
  const refresh=(value:Repair)=>{cache.setQueryData(['issue-repair',value.id],value);cache.invalidateQueries({queryKey:['repair-workspace',issueId]});cache.invalidateQueries({queryKey:['work-issues',workspace.data?.issue.projectId]})}
  const fail=(error:Error)=>message.error(error.message)
  const upload=useMutation({mutationFn:(file:File)=>{const body=new FormData();body.append('file',file);return request<Version>(`/issues/${issueId}/repair-source`,{method:'POST',body})},onSuccess:v=>{setChosenVersion(v.id);setChosenPath('');setParams({});cache.invalidateQueries({queryKey:['repair-workspace',issueId]});message.success('源码已准备好，可以开始修复')},onError:fail})
  const ai=useMutation({mutationFn:()=>request<Notes&{content:string}>(`/issues/${issueId}/repair-candidate`,{method:'POST',timeoutMs:35_000,body:JSON.stringify({versionId,path,beforeSha:source.data?.sha,aiConsent})}),onSuccess:r=>{setEditor(r.content);setNotes(r);setMode('AI');message.success('已生成实际代码修改，请预览差异')},onError:fail})
  const generate=useMutation({mutationFn:(requestedMode:string)=>request<Repair>(`/issues/${issueId}/repairs`,{method:'POST',headers:{'Idempotency-Key':idempotencyKey()},body:JSON.stringify({versionId,path,beforeSha:source.data?.sha,mode:requestedMode,content:editor,language,candidateText,decorative,...notes})}),onSuccess:r=>{refresh(r);setParams({repairId:r.id});message.success('补丁已保存，请检查改动并应用')},onError:fail})
  const apply=useMutation({mutationFn:()=>applyIssueRepair(repairId!,reviewAccepted),onSuccess:r=>{refresh(r);message.success('已实际修改源码并创建修复版本')},onError:fail})
  const retest=useMutation({mutationFn:()=>request<Repair>(`/issue-repairs/${repairId}/retest`,{method:'POST',headers:{'Idempotency-Key':idempotencyKey()},body:JSON.stringify({deployed})}),onSuccess:r=>{refresh(r);message.success('在线复测已开始，进度会自动更新')},onError:fail})
  const verify=useMutation({mutationFn:()=>request<Repair>(`/issue-repairs/${repairId}/verify`,{method:'POST',headers:{'Idempotency-Key':idempotencyKey()}}),onSuccess:r=>{refresh(r);message.success('原问题未再次检出，已记录复测验证')},onError:fail})
  const linkRun=useMutation({mutationFn:()=>request<Repair>(`/issue-repairs/${repairId}/link-retest`,{method:'POST',body:JSON.stringify({runId:linkedRun.trim()})}),onSuccess:r=>{refresh(r);message.success('已关联复测运行')},onError:fail})
  const download=useMutation({mutationFn:()=>downloadFile(`/issue-repairs/${repairId}/download?inline=true`,`repaired-source-${repairId}.zip`),onSuccess:()=>message.success('修复源码已准备好，请查看浏览器下载'),onError:fail})
  if(workspace.isLoading)return <Card><Spin/> 正在准备修复工作台…</Card>
  if(workspace.isError||!workspace.data)return <Alert type="error" showIcon title="无法读取问题" description={(workspace.error as Error)?.message} action={<Button onClick={()=>workspace.refetch()}>重试</Button>}/>
  const w=workspace.data,r=repair.data,busy=ai.isPending||generate.isPending||upload.isPending
  if(r&&r.issueId!==issueId)return <Alert type="error" title="该补丁不属于当前问题" action={<Button onClick={()=>setParams({})}>返回当前问题</Button>}/>
  const stage=r?.newVersionId?3:r?2:versionId?1:0
  const editChanged=!!source.data&&editor!==source.data.content&&!!editor.trim()
  return <div className="repair-workspace">
    <Link className="repair-back" to={`/projects/${w.issue.projectId}/collaboration?issueId=${issueId}`}><ArrowLeftOutlined/> 返回问题列表</Link>
    <header className="repair-hero"><span className="repair-hero-icon"><ToolOutlined/></span><div><small>问题解决工作台</small><h1>把问题修好</h1><p>{w.issue.title}</p></div><Link to={`/test-runs/${w.issue.runId}`}><Button icon={<FileSearchOutlined/>}>查看检测证据</Button></Link></header>
    <Steps className="repair-steps" current={stage} items={[{title:'准备源码',icon:<CloudUploadOutlined/>},{title:'生成修改',icon:<CodeOutlined/>},{title:'确认应用',icon:<CheckCircleOutlined/>},{title:'下载与验证',icon:<SafetyCertificateOutlined/>}]}/>
    {repair.isError&&<Alert type="error" showIcon title="无法读取补丁" description={(repair.error as Error).message} action={<Button onClick={()=>repair.refetch()}>重试</Button>}/>}
    {!r&&<>
      <Card className="repair-source-card" title={<Space><span className="repair-step-number">1</span>准备需要修改的源码</Space>}>
        <p>上传这个网站的源码，系统才能把改动写入文件。已有源码可以直接选用。</p>
        <div className="repair-source-options"><div><label htmlFor="repair-version">选择已有版本</label><Select id="repair-version" aria-label="选择源码版本" value={versionId||undefined} placeholder="还没有源码，请在右侧上传" disabled={busy} options={w.versions.map(v=>({value:v.id,label:`V${v.versionNo} · ${v.entryPage}`}))} onChange={value=>{setChosenVersion(value);setChosenPath('')}}/></div>
          <Upload accept=".zip,.html,.htm" showUploadList={false} beforeUpload={file=>{upload.mutate(file);return false}} disabled={busy}><Button icon={<CloudUploadOutlined/>} loading={upload.isPending} size="large">上传源码 HTML / ZIP</Button></Upload></div>
        <div className="repair-help">支持网页、前端组件与后端源码；ZIP 最大 20 MB、500 个文件。打包前移除 .env、.git 和 node_modules。</div>
      </Card>
      {versionId&&<Card data-guide="repair-editor" className="repair-editor-card" title={<Space><span className="repair-step-number">2</span>选择文件并生成修改</Space>}>
        <label htmlFor="repair-file">要修改哪个文件</label><Select id="repair-file" aria-label="选择修复文件" showSearch value={path||undefined} loading={files.isLoading} disabled={busy} options={(files.data||[]).map(f=>({value:f,label:f}))} onChange={setChosenPath}/>
        {(files.isError||source.isError)&&<Alert type="warning" showIcon title="暂时无法读取源码" description={((files.error||source.error) as Error)?.message}/>}
        {files.data?.length===0&&<Empty description="此版本没有可编辑的源码文件，请上传源码"/>}
        {source.isLoading&&<Spin/>}
        {source.data&&<>
          {source.data.templateRule&&<div className="repair-template"><div><ThunderboltOutlined/><b>此问题支持自动补丁</b><p>系统会针对当前问题生成具体代码改动。</p></div>
            {source.data.templateRule==='R004'&&<Select aria-label="页面语言" value={language} onChange={setLanguage} options={[{value:'zh-CN',label:'简体中文'},{value:'en',label:'英语'},{value:'zh-TW',label:'繁体中文'}]}/>}
            {['R001','R003'].includes(source.data.templateRule)&&<div className="repair-template-text">{source.data.templateRule==='R001'&&<Checkbox checked={decorative} onChange={e=>setDecorative(e.target.checked)}>这是装饰图片</Checkbox>}<Input aria-label="确认元素描述" placeholder={source.data.templateRule==='R001'?'用一句话描述图片内容':'填写按钮或链接的操作名称'} value={candidateText} disabled={decorative} maxLength={source.data.templateRule==='R001'?300:120} onChange={e=>setCandidateText(e.target.value)}/></div>}
            <Button type="primary" icon={<ThunderboltOutlined/>} loading={generate.isPending} disabled={busy||(['R001','R003'].includes(source.data.templateRule)&&!decorative&&!candidateText.trim())} onClick={()=>generate.mutate('TEMPLATE')}>生成自动修复补丁</Button></div>}
          <Tabs defaultActiveKey={w.aiEnabled?'ai':'manual'} onChange={key=>setEditorVisible(key==='manual')} items={[
            {key:'ai',label:<span><ThunderboltOutlined/> AI 修改源码</span>,children:<div className="repair-ai-panel"><h3>让智能体修改这个文件</h3><p>根据保存的问题证据生成修复代码，随后查看前后差异。</p>{!w.aiEnabled&&<Alert type="info" title={w.aiUnavailableReason||'AI 服务暂不可用，可使用自动补丁或编辑源码'}/>}
              <Checkbox checked={aiConsent} disabled={busy||!w.aiEnabled} onChange={e=>setAiConsent(e.target.checked)}>同意将当前选中文件与脱敏问题发送给 AI 修复服务</Checkbox><Button type="primary" icon={<ThunderboltOutlined/>} loading={ai.isPending} disabled={!aiConsent||!w.aiEnabled||busy} onClick={()=>ai.mutate()}>让 AI 生成修复代码</Button>
              {ai.isPending&&<p role="status">正在修改选定文件，最多等待约 30 秒…</p>}{ai.isError&&<Alert type="warning" showIcon title="这次未生成代码" description={(ai.error as Error).message}/>}</div>},
            {key:'manual',label:'自己编辑 / 粘贴修改',children:<p className="repair-help">在下面编辑源码或粘贴修复后的完整文件，然后预览差异。也可以让开发同事在这里完成修改。</p>},
          ]}/>
          {(editorVisible||mode==='AI'||!w.aiEnabled)?<><label htmlFor="repair-source-editor">{mode==='AI'?'AI 生成的修复代码':'源码编辑器'}</label><Input.TextArea id="repair-source-editor" aria-label="修复后的完整源码" className="repair-code-editor" spellCheck={false} value={editor} onChange={e=>{setEditor(e.target.value);setMode('MANUAL');setNotes(emptyNotes)}} rows={14} disabled={busy}/></>:<Button type="link" icon={<CodeOutlined/>} onClick={()=>setEditorVisible(true)}>展开源码编辑器</Button>}
          <div className="repair-editor-footer"><span>{editChanged?'代码已有变化，可以预览并保存补丁':'原文件已载入，编辑后即可预览差异'}</span><Button type="primary" icon={<FileSearchOutlined/>} loading={generate.isPending} disabled={!editChanged||busy} onClick={()=>generate.mutate(mode)}>预览修改并保存补丁</Button></div>
        </>}
      </Card>}
    </>}
    {repair.isLoading&&repairId&&<Card><Spin/> 正在读取已保存的补丁…</Card>}
    {r&&<>
      <Card data-guide="repair-review" className="repair-review-card" title={<Space><span className="repair-step-number">3</span>{r.newVersionId?'本次已应用的修改':'检查改动，再确认应用'}<Tag color={r.status==='VERIFIED'?'green':r.newVersionId?'cyan':'orange'}>{statusNames[r.status]||r.status}</Tag></Space>} extra={!r.newVersionId?<Button onClick={()=>{restoredEdit.current={versionId:r.baseVersionId,path:r.targetFile,content:r.content};setChosenVersion(r.baseVersionId);setChosenPath(r.targetFile);setParams({});setEditor(r.content)}}>返回编辑</Button>:<Button onClick={()=>{restoredEdit.current=undefined;setChosenVersion(r.newVersionId||'');setChosenPath(r.targetFile);setParams({})}}>基于此版本继续修改</Button>}>
        <RepairExplanation notes={r.explanation}/>
        <div className="repair-diff-heading"><b><CodeOutlined/> {r.targetFile}</b><span>红色为删除 · 绿色为新增</span></div><RepairDiff diff={r.diffText}/>
        {!r.newVersionId&&<div className="repair-confirm"><Checkbox checked={reviewAccepted} onChange={e=>setReviewAccepted(e.target.checked)}>我已检查差异，同意将这些改动应用到新的源码版本</Checkbox><Button size="large" type="primary" icon={<ToolOutlined/>} disabled={!reviewAccepted} loading={apply.isPending} onClick={()=>apply.mutate()}>确认应用修复</Button></div>}
      </Card>
      {r.newVersionId&&w.hostedSiteId&&<HostedRepairDelivery siteId={w.hostedSiteId} repairId={r.id} versionId={r.newVersionId} deploymentError={r.hostedDeploymentError}/>}
      {r.newVersionId&&!w.hostedSiteId&&<Card data-guide="repair-delivery" className="repair-delivery-card" title={<Space><span className="repair-step-number">4</span>下载修复代码，验证效果</Space>}>
        <div className="repair-delivery"><CheckCircleOutlined/><div><h3>修复代码已经写入新版本</h3><p>完整源码包包含本次改动，原始源码仍保留。下载后将代码发布到原检测网站。</p></div><Button size="large" type="primary" icon={<DownloadOutlined/>} loading={download.isPending} onClick={()=>download.mutate()}>下载修复后的源码</Button></div>
        {r.staticScan&&<div className="repair-static-progress"><Space><b>源码页面基础检查</b><Tag>{statusNames[r.staticScan.status]||r.staticScan.status}</Tag><Link to={`/scans/${r.staticScan.id}/progress`}>查看检查详情</Link></Space><Progress percent={r.staticScan.progress||0} status={r.staticScan.status==='FAILED'?'exception':r.staticScan.status==='COMPLETED'?'success':'active'}/><small>这项检查只覆盖源码中的 HTML 页面；在线网站的修复效果需要部署后复测。</small></div>}
        {r.status!=='VERIFIED'&&<div className="repair-online-retest"><h3>发布完成后，再检查原网站</h3><p>沿用原目标和方案版本执行只读复测。主动安全、写入和承载能力用例请从原运行入口执行完整复测后关联。</p><Checkbox checked={deployed} onChange={e=>setDeployed(e.target.checked)}>修复代码已部署到原检测目标</Checkbox><Button icon={<PlayCircleOutlined/>} type="primary" disabled={!deployed||!!r.retest&&!terminal.has(r.retest.status)} loading={retest.isPending} onClick={()=>retest.mutate()}>启动在线复测</Button></div>}
        {r.retest&&<div className="repair-retest-result"><Space wrap><b>在线复测</b><Tag color={r.status==='VERIFIED'?'green':r.retest.status==='COMPLETED'?'cyan':'orange'}>{statusNames[r.retest.status]||r.retest.status}</Tag><Link to={`/test-runs/${r.retest.id}`}>查看本次复测证据</Link></Space>
          {!terminal.has(r.retest.status)?<><Progress percent={Math.round(r.retest.modules.filter(m=>terminal.has(m.status)).length/Math.max(r.retest.modules.length,1)*100)} status="active"/><p role="status">正在检查原网站，进度会自动更新…</p></>:r.status==='VERIFIED'?<Alert type="success" showIcon title="原问题未再次检出，已完成本次复测验证" description="请继续留意本次运行中的其他问题，并将此场景加入日常回归检查。"/>:<><Alert type={r.retest.status==='COMPLETED'?'info':'warning'} showIcon title={r.retest.status==='COMPLETED'?'复测已结束，可以校验原问题是否消失':'复测没有完整完成，暂不能确认修复'} description={r.retest.errorMessage||`本次保存 ${r.retest.issues.length} 项问题。系统将核对原问题、检查范围与证据完整性。`}/><Button type="primary" icon={<SafetyCertificateOutlined/>} disabled={r.retest.status!=='COMPLETED'} loading={verify.isPending} onClick={()=>verify.mutate()}>校验并记录修复结果</Button></>}
          {verify.isError&&<Alert type="warning" showIcon title="尚不能确认修复完成" description={(verify.error as Error).message}/>}</div>}
        {r.status!=='VERIFIED'&&<details className="repair-link-run"><summary>已有完整复测结果？关联运行</summary><p>使用原目标和方案版本，且在本次修复应用后创建的运行。</p><Space.Compact><Input aria-label="复测运行编号" placeholder="粘贴复测运行编号" value={linkedRun} onChange={e=>setLinkedRun(e.target.value)}/><Button disabled={!linkedRun.trim()} loading={linkRun.isPending} onClick={()=>linkRun.mutate()}>关联结果</Button></Space.Compact><Link to={`/test-runs/${w.issue.runId}`}>前往原运行查看方案</Link></details>}
      </Card>}
    </>}
    {!!w.repairs.length&&<Card className="repair-history-card" title="修复记录"><Space wrap>{w.repairs.map(item=><Button key={item.id} type={item.id===repairId?'primary':'default'} onClick={()=>setParams({repairId:item.id})}>{item.targetFile} · {statusNames[item.status]||item.status}</Button>)}</Space></Card>}
  </div>
}

export function RepairDiff({diff}:{diff:string}){
  return <pre className="repair-diff" aria-label="修复前后代码差异">{diff.split('\n').map((line,index)=><span key={index} className={line.startsWith('@@')?'diff-hunk':line.startsWith('+')&&!line.startsWith('+++')?'diff-added':line.startsWith('-')&&!line.startsWith('---')?'diff-removed':''}>{line||' '}<br/></span>)}</pre>
}
function RepairExplanation({notes}:{notes:Notes}){
  return <div className="repair-explanation">{[{key:'summary',label:'改了什么',icon:<CodeOutlined/>},{key:'reason',label:'为什么这样改',icon:<FileSearchOutlined/>},{key:'benefit',label:'带来哪些好处',icon:<CheckCircleOutlined/>},{key:'prevention',label:'下次怎么避免',icon:<SafetyCertificateOutlined/>}].map(item=><section key={item.key}><h3>{item.icon}{item.label}</h3><p>{notes[item.key as keyof Notes]}</p></section>)}</div>
}
