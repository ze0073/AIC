import {useEffect,useState} from 'react'
import {useQuery} from '@tanstack/react-query'
import {Link} from 'react-router-dom'
import {Alert,Button,Card,Checkbox,Collapse,Descriptions,Form,Input,InputNumber,Progress,Select,Space,Spin,Tag,Upload} from 'antd'
import {CloudUploadOutlined} from '@ant-design/icons'
import {request,downloadFile,idempotencyKey} from './api'
import {importWebsiteRepository,uploadWebsiteSource} from './businessApi'
import {listRunReportSnapshots} from './reportSnapshot'
import {RepairDiff} from './IssueRepair'
import type {SourceFile} from './IssueRepair'
import type {Site,RuntimeConfig} from './Websites'
import type {OnlineTarget,Project,TestRun,TestSuite} from './types'
import type {GuideContext,GuideDraft,GuideGoal,GuideSession,GuideStep} from './guideTypes'

export const guideGoals:Array<{key:GuideGoal;title:string;description:string;number:string}>=[
  {key:'DEPLOY',title:'部署我的网站',description:'导入源码，准备环境并部署',number:'01'},
  {key:'CHECK',title:'检查网站质量',description:'检查页面、接口和性能',number:'02'},
  {key:'REPAIR',title:'修复发现的问题',description:'准备修改，检查差异并复测',number:'03'},
  {key:'REPORT',title:'查看检测结果',description:'读懂证据，导出固定报告',number:'04'},
  {key:'HELP',title:'了解平台',description:'认识功能与操作流程',number:'05'},
]
const names:Record<string,string>={QUEUED:'等待开始',IMPORTING:'导入中',BUILDING:'构建中',STARTING:'启动中',CHECKING:'检测中',RUNNING:'运行中',PENDING:'等待执行',COMPLETED:'已完成',PARTIAL:'部分完成',FAILED:'未完成',CANCELLED:'已取消',STOPPED:'已停止',EXPIRED:'已到期',AWAITING_START:'等待确认',VERIFIED:'复测已验证',APPLIED:'已应用'}
const terminal=new Set(['COMPLETED','PARTIAL','FAILED','CANCELLED','INTERRUPTED'])
type Props={session:GuideSession;draft:GuideDraft;busy:boolean;onDraft:(draft:GuideDraft)=>void;onContext:(context:GuideContext)=>Promise<void>;
  onGoal:(goal:GuideGoal,context?:GuideContext)=>void;prepare:(type:string,parameters?:Record<string,unknown>)=>void;
  operate:(fn:()=>Promise<unknown>)=>void;refresh:()=>void;guide:(step:GuideStep)=>void}

export function flowGuidance(s:GuideSession):GuideStep{
  const c=s.context
  switch(s.step){
    case 'SELECT_SITE':return {key:s.step,title:'创建网站工作区',description:'点击“部署我的网站”，填写名称并选择检测修复或长期托管。',path:'/sites',anchor:'create-site'}
    case 'IMPORT_SOURCE':return {key:s.step,title:'准备网站源码',description:'上传 ZIP／HTML，或连接 HTTPS 仓库。令牌只填入专用字段。',path:'/sites/'+c.siteId,anchor:'source-import'}
    case 'CONFIGURE':return {key:s.step,title:'确认运行设置',description:'检查识别出的网站类型和数据库；提交后自动部署和检测。',path:'/sites/'+c.siteId,anchor:'runtime-config'}
    case 'SELECT_TEST':return {key:s.step,title:'准备检查方案',description:'登记允许检测的网站和方案，再生成并确认检测计划。',path:c.projectId?'/projects/'+c.projectId+(c.targetId&&c.suiteVersionId?'/agent':'/quality'):'/agent',anchor:c.targetId&&c.suiteVersionId?'agent-form':'quality-config'}
    case 'PREPARE_REPAIR':return {key:s.step,title:'选择源码并准备修改',description:'选择检测时的源码和文件。生成修改后查看差异。',path:'/issues/'+c.issueId+'/repair',anchor:'repair-editor'}
    case 'REVIEW_REPAIR':return {key:s.step,title:'检查修复差异',description:'核对增加和删除的代码，勾选审阅后确认应用。',path:'/issues/'+c.issueId+'/repair?repairId='+c.repairId,anchor:'repair-review'}
    case 'RETEST':return {key:s.step,title:'跟踪复测结果',description:'平台网站自动部署复测；外部网站先部署修复代码，再确认复测。',path:'/issues/'+c.issueId+'/repair?repairId='+c.repairId,anchor:'repair-delivery'}
    case 'REPORT_PREPARE':case 'REPORT_READY':return {key:s.step,title:'阅读与导出报告',description:'生成报告快照，之后 PDF 和 JSON 引用同一份证据。',path:'/test-runs/'+c.runId,anchor:'run-report'}
    case 'RESULTS':return {key:s.step,title:'查看执行进度与证据',description:'检查每个模块是否完成。任务结束后选择问题进行修复，或生成报告。',path:c.taskId?'/agent-tasks/'+c.taskId:c.deploymentId?'/sites/'+c.siteId:'/test-runs/'+c.runId,anchor:c.deploymentId?'deployment-results':undefined}
    default:return {key:s.step,title:'选择要处理的资源',description:'先选择项目或检测记录，我会根据真实数据带你继续。',path:'/'}
  }
}
function ResourceError({error,retry}:{error:unknown;retry:()=>void}){return error?<Alert type="warning" showIcon title={(error as Error).message||'资源暂不可用'} action={<Button onClick={retry}>重试</Button>}/>:null}

export function GuideFlows(p:Props){
  const {session:s,draft,busy,onDraft,onContext,onGoal,prepare,operate,refresh,guide}=p
  const c=s.context,r=s.resources,delegated=s.assistance==='DELEGATED',site=r.site
  const sites=useQuery({queryKey:['guide-sites'],queryFn:()=>request<Site[]>('/sites'),enabled:s.goal==='DEPLOY'})
  const projects=useQuery({queryKey:['projects'],queryFn:()=>request<Project[]>('/projects'),enabled:s.goal==='CHECK'})
  const recent=useQuery({queryKey:['guide-runs'],queryFn:()=>request<Array<{id:string;projectId:string;projectName:string;status:string;issueCount:number}>>('/test-runs/recent'),enabled:['REPAIR','REPORT'].includes(s.goal)&&!c.runId})
  const targets=useQuery({queryKey:['guide-targets',c.projectId],queryFn:()=>request<OnlineTarget[]>('/projects/'+c.projectId+'/targets'),enabled:s.goal==='CHECK'&&!!c.projectId})
  const suites=useQuery({queryKey:['guide-suites',c.projectId],queryFn:()=>request<TestSuite[]>('/projects/'+c.projectId+'/test-suites'),enabled:s.goal==='CHECK'&&!!c.projectId})
  const recommendation=useQuery({queryKey:['guide-recommendation',c.projectId],queryFn:()=>request<{ready:boolean;warnings:string[];target?:{id:string;name:string};suite?:{versionId:string;name:string};baseline?:{id:string}}>('/projects/'+c.projectId+'/assistant-recommendation'),enabled:s.goal==='CHECK'&&!!c.projectId&&!c.taskId})
  const baseline=useQuery({queryKey:['guide-baselines',c.projectId,c.targetId,c.suiteVersionId],queryFn:()=>request<Array<{id:string;goal:string}>>('/projects/'+c.projectId+'/agent-tasks/baselines?targetId='+c.targetId+'&suiteVersionId='+c.suiteVersionId),enabled:s.goal==='CHECK'&&!!c.targetId&&!!c.suiteVersionId&&!c.taskId})
  const files=useQuery({queryKey:['guide-files',c.issueId,c.versionId],queryFn:()=>request<string[]>('/issues/'+c.issueId+'/repair-files?versionId='+c.versionId),enabled:s.goal==='REPAIR'&&!!c.issueId&&!!c.versionId&&!c.repairId})
  const source=useQuery({queryKey:['guide-file',c.issueId,c.versionId,draft.path],queryFn:()=>request<SourceFile>('/issues/'+c.issueId+'/repair-file?versionId='+c.versionId+'&path='+encodeURIComponent(draft.path||'')),enabled:s.goal==='REPAIR'&&!!c.issueId&&!!c.versionId&&!!draft.path&&!c.repairId})
  const snapshots=useQuery({queryKey:['guide-snapshots',c.runId],queryFn:()=>listRunReportSnapshots(c.runId!),enabled:s.goal==='REPORT'&&!!c.runId})
  const [repairConsent,setRepairConsent]=useState(false)
  useEffect(()=>setRepairConsent(false),[c.issueId,c.versionId,draft.path])
  const g=flowGuidance(s)
  const guideButton=<Button onClick={()=>guide(g)}>带我到页面操作</Button>
  const update=(change:Partial<GuideDraft>)=>onDraft({...draft,...change})
  return <div className="guide-flow">
    <div className="guide-step"><span>当前步骤</span><h3>{g.title}</h3><p>{g.description}</p>{guideButton}</div>
    {s.goal==='HELP'&&<div className="guide-introduction">{guideGoals.slice(0,4).map(item=><Card key={item.key} size="small" title={item.title}><p>{item.description}。{item.key==='DEPLOY'?'准备 ZIP、HTML 文件或代码仓库地址。':item.key==='CHECK'?'准备已授权的目标和检查方案。':item.key==='REPAIR'?'准备检测问题和对应源码。':'选择已有检测记录。'}</p><Button onClick={()=>onGoal(item.key,{})}>开始这个流程</Button></Card>)}<p>账号、成员、回归计划以及停止、回退和删除操作，可从各工作区进入，向导会保持当前流程。</p></div>}
    {s.goal==='DEPLOY'&&site&&<Button disabled={busy} onClick={()=>onGoal('DEPLOY',{})}>切换或创建其他网站</Button>}
    {['REPAIR','REPORT'].includes(s.goal)&&(c.runId||c.issueId)&&<Button disabled={busy} onClick={()=>onGoal(s.goal,{})}>选择其他检测记录</Button>}
    {s.goal==='DEPLOY'&&!c.siteId&&<Card size="small" title="选择或创建网站">
      <ResourceError error={sites.error} retry={()=>sites.refetch()}/>
      <label>已有的网站</label><Select aria-label="向导选择网站" placeholder="选择已有网站，或在下面创建" value={undefined} disabled={busy} loading={sites.isLoading} options={sites.data?.filter(x=>!x.deletionStatus||x.deletionStatus==='ACTIVE').map(x=>({value:x.id,label:x.name}))} onChange={id=>{const selected=sites.data?.find(x=>x.id===id);onGoal('DEPLOY',{siteId:id,projectId:selected?.projectId})}}/>
      {delegated?<><label htmlFor="guide-name">新网站名称</label><Input id="guide-name" value={draft.name||''} maxLength={120} onChange={e=>update({name:e.target.value})}/><label>网站用途</label><Select aria-label="向导网站用途" value={draft.purpose||'TEST'} options={[{value:'TEST',label:'检测修复环境'},{value:'HOST',label:'长期托管'}]} onChange={purpose=>update({purpose})}/><Button type="primary" disabled={!draft.name?.trim()||busy} onClick={()=>prepare('CREATE_SITE',{name:draft.name,purpose:draft.purpose||'TEST'})}>准备创建工作区</Button></>:<p>打开网站页面后，点击“部署我的网站”创建工作区。创建成功后向导继续导入步骤。</p>}
    </Card>}
    {s.goal==='DEPLOY'&&site&&!r.deployment&&<>
      <Card size="small" title={'网站：'+site.name}><p>{site.sourceVersionId?'源码已准备好，可以检查运行设置。':'先提供源码，平台会识别网站类型。'}</p>
        {delegated&&<WebsiteSourceForm site={site} busy={busy} operate={operate} refresh={refresh}/>}
        {site.sourceJob&&<Alert showIcon type={site.sourceJob.status==='FAILED'?'error':'info'} title={names[site.sourceJob.status]||site.sourceJob.status} description={site.sourceJob.errorMessage}/>}
      </Card>
      {delegated&&site.config&&<GuideRuntimeForm key={site.sourceVersionId} config={draft.config||site.config} load={draft.testPolicy?.load||false} busy={busy} onChange={(config,load)=>update({config,testPolicy:{write:false,active:false,load}})} onPrepare={(config,load)=>prepare('CONFIGURE_DEPLOY',{config,testPolicy:{write:false,active:false,load},suiteVersionId:site.suiteVersionId})}/>}
    </>}
    {s.goal==='CHECK'&&!c.taskId&&<Card size="small" title="选择检查范围">
      <ResourceError error={projects.error||targets.error||suites.error} retry={()=>{projects.refetch();targets.refetch();suites.refetch()}}/>
      <label>项目</label><Select aria-label="向导检测项目" value={c.projectId} placeholder="选择项目" loading={projects.isLoading} disabled={busy} options={projects.data?.map(x=>({value:x.id,label:x.name}))} onChange={projectId=>onGoal('CHECK',{projectId})}/>
      {c.projectId&&<><label>已登记网站</label><Select aria-label="向导检测目标" value={c.targetId} disabled={busy} options={targets.data?.map(x=>({value:x.id,label:x.name+' · '+x.baseUrl}))} onChange={targetId=>onContext({...c,targetId,baselineTaskId:undefined})}/><label>检查方案</label><Select aria-label="向导检查方案" value={c.suiteVersionId} disabled={busy} options={suites.data?.map(x=>({value:x.currentVersionId,label:x.name+' · V'+x.currentVersion}))} onChange={suiteVersionId=>onContext({...c,suiteVersionId,baselineTaskId:undefined})}/>
      {recommendation.data?.ready&&<Button disabled={busy} onClick={()=>onContext({...c,targetId:recommendation.data!.target!.id,suiteVersionId:recommendation.data!.suite!.versionId,baselineTaskId:recommendation.data?.baseline?.id})}>采用推荐网站与方案</Button>}
      {!recommendation.data?.ready&&recommendation.data?.warnings.map(x=><p key={x}>{x}</p>)}
      <label>同范围历史基线（可选）</label><Select aria-label="向导历史基线" allowClear value={c.baselineTaskId} disabled={busy} options={baseline.data?.map(x=>({value:x.id,label:x.goal}))} onChange={baselineTaskId=>onContext({...c,baselineTaskId})}/>
      <label htmlFor="guide-test-goal">这次想检查什么</label><Input.TextArea id="guide-test-goal" rows={2} maxLength={1000} value={draft.goal||'完成日常只读网站体检，解释证据与下一步'} onChange={e=>update({goal:e.target.value})}/>
      {delegated&&<Button type="primary" disabled={busy||!c.targetId||!c.suiteVersionId} onClick={()=>prepare('START_AGENT_TASK',{goal:draft.goal||'完成日常只读网站体检，解释证据与下一步'})}>准备检测计划</Button>}</>}
    </Card>}
    {['REPAIR','REPORT'].includes(s.goal)&&!c.runId&&!c.issueId&&<Card size="small" title="选择检测记录">
      <ResourceError error={recent.error} retry={()=>recent.refetch()}/>
      <Select aria-label="向导检测记录" placeholder="选择一条检测记录" loading={recent.isLoading} disabled={busy} options={recent.data?.map(x=>({value:x.id,label:x.projectName+' · '+(names[x.status]||x.status)+' · '+x.issueCount+' 项'}))} onChange={runId=>{const run=recent.data?.find(x=>x.id===runId);onContext({runId,projectId:run?.projectId})}}/>
    </Card>}
    {s.goal==='REPAIR'&&r.run&&!c.issueId&&<RunSummary run={r.run} onRepair={issueId=>onContext({...c,issueId,taskId:undefined,targetId:undefined,baselineTaskId:undefined})}/>}
    {s.goal==='REPAIR'&&r.repairWorkspace&&!c.repairId&&<Card size="small" title={r.repairWorkspace.issue.title}>
      <p>{r.repairWorkspace.issue.description}</p><label>对应的源码版本</label>
      <Select aria-label="向导源码版本" value={c.versionId} disabled={busy} options={r.repairWorkspace.versions.map(v=>({value:v.id,label:'V'+v.versionNo+' · '+v.entryPage}))} onChange={versionId=>{update({path:''});onContext({...c,versionId})}}/>
      {!c.versionId&&r.repairWorkspace.deployedSourceVersionId&&<Button disabled={busy} onClick={()=>onContext({...c,versionId:r.repairWorkspace!.deployedSourceVersionId,siteId:r.repairWorkspace!.hostedSiteId})}>使用检测时部署的源码</Button>}
      {!r.repairWorkspace.versions.length&&<p>请先在修复页面上传源码。</p>}
      {c.versionId&&<><label>要修改的文件</label><Select aria-label="向导修复文件" showSearch value={draft.path||undefined} disabled={busy} loading={files.isLoading} options={files.data?.map(path=>({value:path,label:path}))} onChange={path=>update({path})}/></>}
      <ResourceError error={files.error||source.error} retry={()=>{files.refetch();source.refetch()}}/>
      {source.isLoading&&<Spin/>}
      {source.data&&delegated&&<>
        {source.data.templateRule?<><p>此问题支持规则补丁。</p>{source.data.templateRule==='R004'&&<Select aria-label="向导页面语言" value={draft.language||'zh-CN'} options={[{value:'zh-CN',label:'简体中文'},{value:'en',label:'英语'},{value:'zh-TW',label:'繁体中文'}]} onChange={language=>update({language})}/>}
        {['R001','R003'].includes(source.data.templateRule)&&<><Input aria-label="向导元素描述" maxLength={source.data.templateRule==='R001'?300:120} disabled={draft.decorative} value={draft.candidateText||''} placeholder="填写图片内容或按钮操作名称" onChange={e=>update({candidateText:e.target.value})}/>{source.data.templateRule==='R001'&&<Checkbox checked={draft.decorative||false} onChange={e=>update({decorative:e.target.checked})}>这是装饰图片</Checkbox>}</>}
        <Button type="primary" disabled={busy||(['R001','R003'].includes(source.data.templateRule)&&!draft.decorative&&!draft.candidateText?.trim())} onClick={()=>prepare('PREPARE_REPAIR',{path:draft.path,mode:'TEMPLATE',language:draft.language||'zh-CN',candidateText:draft.candidateText||'',decorative:!!draft.decorative})}>准备规则补丁</Button></>:<Alert type="info" title="此文件没有可用规则补丁，可使用 AI 或打开源码编辑器。"/>}
        {r.repairWorkspace.aiEnabled&&<><Checkbox checked={repairConsent} onChange={e=>setRepairConsent(e.target.checked)}>同意发送选中文件与脱敏问题用于 AI 修复</Checkbox><Button disabled={busy||!repairConsent} onClick={()=>prepare('PREPARE_REPAIR',{path:draft.path,mode:'AI',repairAiConsent:true})}>准备 AI 修复候选</Button></>}
      </>}
      <Button onClick={()=>guide(g)}>打开源码编辑器</Button>
      {!!r.repairWorkspace.repairs.length&&<><label>继续已有修复</label><Select aria-label="向导已有修复" value={undefined} disabled={busy} options={r.repairWorkspace.repairs.map(x=>({value:x.id,label:x.targetFile+' · '+(names[x.status]||x.status)}))} onChange={repairId=>onContext({...c,repairId})}/></>}
    </Card>}
    {r.repair&&s.goal==='REPAIR'&&<Card size="small" title="修复与验证">
      <Tag>{names[r.repair.status]||r.repair.status}</Tag><p>{r.repair.explanation.summary}</p><RepairDiff diff={r.repair.diffText}/>
      {!r.repair.newVersionId&&delegated&&<Button type="primary" disabled={busy} onClick={()=>prepare('APPLY_REPAIR')}>准备应用修复</Button>}
      {r.repair.newVersionId&&!r.repairWorkspace?.hostedSiteId&&<><p>修复文件已生成。下载并部署到原网站，然后在修复页确认在线复测。</p><Button onClick={()=>operate(()=>downloadFile('/issue-repairs/'+r.repair!.id+'/download?inline=true','repaired-source.zip'))}>下载修复源码</Button></>}
      {r.repair.hostedDeploymentError&&<Alert type="warning" title="自动部署暂未完成" description={r.repair.hostedDeploymentError}/>}
      {r.repair.retest&&<p>复测：{names[r.repair.retest.status]||r.repair.retest.status}</p>}
    </Card>}
    {s.goal==='REPAIR'&&r.repair?.newVersionId&&r.run&&terminal.has(r.run.status)&&<Button onClick={()=>onGoal('REPORT',{projectId:r.run!.projectId,siteId:c.siteId,runId:r.run!.id})}>查看并导出复测报告</Button>}
    {(r.deployment||r.task)&&<Card size="small" title="真实执行进度">
      {r.deployment&&<><p>{names[r.deployment.status]||r.deployment.status} · {r.deployment.environmentKind==='HOST'?'正式更新':'独立测试环境'}</p><Progress percent={{QUEUED:0,BUILDING:25,STARTING:50,CHECKING:75,RUNNING:100}[r.deployment.status]||0} status={r.deployment.status==='FAILED'?'exception':r.deployment.status==='RUNNING'?'normal':'active'}/>{r.deployment.errorMessage&&<Alert type="warning" title={r.deployment.errorMessage}/>}<p>检测状态：{names[r.deployment.runStatus||'']||'尚未开始'}</p>
      {r.deployment.runId&&!c.runId&&<Button disabled={busy} onClick={()=>onContext({...c,runId:r.deployment!.runId})}>读取本次检测结果</Button>}
      {!r.deployment.published&&<Space wrap><Button onClick={()=>guide({key:'manage',title:'管理运行环境',description:'在网站工作区确认停止、回退或删除。',path:'/sites/'+r.deployment!.siteId,anchor:'deployment-results'})}>管理运行环境</Button>{['FAILED','STOPPED','EXPIRED','CANCELLED'].includes(r.deployment.status)&&<Button onClick={()=>operate(()=>request('/deployments/'+r.deployment!.id+'/retry',{method:'POST',headers:{'Idempotency-Key':idempotencyKey()}}).then((d)=>{window.dispatchEvent(new CustomEvent('guide-business-result',{detail:{...c,deploymentId:(d as {id:string}).id,runId:undefined}}))})) }>重试部署</Button>}</Space>}
      {r.deployment.published&&<p>正式网站入口已更新。</p>}
      {site?.purpose==='HOST'&&r.deployment.environmentKind==='TEST'&&delegated&&<Button type="primary" disabled={busy||!r.deployment.testPassed||r.deployment.status!=='RUNNING'} onClick={()=>prepare('PUBLISH_SITE',{deploymentId:r.deployment!.id})}>准备更新正式网站</Button>}
      {!r.deployment.testPassed&&r.deployment.runId&&<p>是否可以更新正式网站取决于完整检查与修复验证，查看检测证据后继续处理问题。</p>}
      <details><summary>部署过程</summary>{r.deployment.events.slice(-12).map(e=><p key={e.id}>{names[e.stage]||e.stage}：{e.message}</p>)}</details></>}
      {r.task&&<><p>{names[r.task.status]||r.task.status}</p>{r.task.errorMessage&&<Alert type="warning" title={r.task.errorMessage}/>}<ul>{r.task.steps.map(step=><li key={step.stepNo}>步骤 {step.stepNo} · {names[step.status]||step.status}{step.runId&&<Button type="link" onClick={()=>guide({key:step.runId!,title:'查看模块证据',description:'这里保存本模块的真实指标与问题。',path:'/test-runs/'+step.runId})}>查看证据</Button>}</li>)}</ul>
      {!terminal.has(r.task.status)&&r.task.status!=='AWAITING_START'&&<Button danger onClick={()=>operate(()=>request('/agent-tasks/'+r.task!.id+'/cancel',{method:'POST'}))}>取消检测任务</Button>}
      <Button onClick={()=>guide({key:'insights',title:'检测洞察',description:'查看完整模块证据和行动清单。',path:'/agent-tasks/'+r.task!.id})}>查看完整检测洞察</Button></>}
    </Card>}
    {!['REPAIR','REPORT'].includes(s.goal)&&(r.taskRuns?.length?r.taskRuns:r.run?[r.run]:[]).map(run=><div key={run.id}><RunSummary run={run} onRepair={issueId=>onGoal('REPAIR',{projectId:run.projectId,siteId:c.siteId,runId:run.id,issueId})}/>{terminal.has(run.status)&&<Button onClick={()=>onGoal('REPORT',{projectId:run.projectId,siteId:c.siteId,runId:run.id})}>生成这次运行的报告</Button>}</div>)}
    {s.goal==='REPORT'&&r.run&&<Card size="small" title={c.snapshotId?'固定报告快照':'准备检测报告'}>
      <RunSummary run={r.run} onRepair={issueId=>onGoal('REPAIR',{projectId:r.run!.projectId,runId:r.run!.id,issueId,siteId:c.siteId})}/>
      <ResourceError error={snapshots.error} retry={()=>snapshots.refetch()}/>
      <label>已有报告快照</label><Select aria-label="向导报告快照" value={c.snapshotId} placeholder="选择已有快照，或生成新的报告" disabled={busy} options={snapshots.data?.map(x=>({value:x.id,label:new Date(x.createdAt).toLocaleString('zh-CN')}))} onChange={snapshotId=>onContext({...c,snapshotId})}/>
      {delegated&&<Button disabled={busy||!terminal.has(r.run.status)} onClick={()=>prepare('CREATE_REPORT_SNAPSHOT')}>准备生成新快照</Button>}
      {c.snapshotId&&<><p>解释和导出均引用当前快照。后续审阅不会改写这份报告。</p><Space><Button onClick={()=>operate(()=>downloadFile('/test-run-report-snapshots/'+c.snapshotId+'/export?format=pdf','website-report.pdf'))}>导出 PDF</Button><Button onClick={()=>operate(()=>downloadFile('/test-run-report-snapshots/'+c.snapshotId+'/export?format=json','website-report.json'))}>导出 JSON</Button></Space></>}
    </Card>}
  </div>
}

export function RunSummary({run,onRepair}:{run:TestRun;onRepair:(id:string)=>void}){
  const issues=[...run.issues].sort((a,b)=>({HIGH:0,MEDIUM:1,LOW:2}[a.severity]-{HIGH:0,MEDIUM:1,LOW:2}[b.severity]))
  const incomplete=run.modules.filter(m=>m.status!=='COMPLETED')
  return <section className="guide-run-summary"><h4>检测证据 · {names[run.status]||run.status}</h4><p>共 {run.issues.length} 项问题或候选，其中高优先级 {run.issues.filter(x=>x.severity==='HIGH').length} 项。</p>
    {!!incomplete.length&&<Alert type="warning" title="部分模块尚未完整完成" description="未执行、失败或缺失的结果不能解释为质量通过。"/>}
    <ul>{run.modules.map(m=><li key={m.id}>{({FRONTEND:'页面体验',SECURITY:'接口与权限',PERFORMANCE:'承载能力'} as Record<string,string>)[m.moduleType]}：{names[m.status]||m.status}{m.performanceAssessment&&' · '+m.performanceAssessment.reason}</li>)}</ul>
    {issues.slice(0,8).map(issue=><article key={issue.id}><Tag color={issue.severity==='HIGH'?'red':'orange'}>{({HIGH:'高',MEDIUM:'中',LOW:'低'} as Record<string,string>)[issue.severity]}优先级</Tag><b>{issue.title}</b><p>{issue.description}</p><small>{issue.evidenceConfidence==='INCONCLUSIVE'?'证据不足，需要核对':issue.source==='ZAP'?'扫描引擎候选，未自动确认为漏洞':'请结合检测范围核对证据'}</small><Space><Link to={'/projects/'+run.projectId+'/collaboration?issueId='+issue.id}>查看具体证据</Link><Button onClick={()=>onRepair(issue.id)}>处理这个问题</Button></Space></article>)}
    {!issues.length&&<p>{incomplete.length?'当前未获得完整问题结果。':'在本次已执行的检查范围内没有检出问题。'}</p>}
  </section>
}

function WebsiteSourceForm({site,busy,operate,refresh}:{site:Site;busy:boolean;operate:(fn:()=>Promise<unknown>)=>void;refresh:()=>void}){
  const [form]=Form.useForm()
  const importing=['QUEUED','IMPORTING'].includes(site.sourceJob?.status||'')||site.deployments.some(d=>['QUEUED','BUILDING','STARTING','CHECKING'].includes(d.status))
  return <div className="guide-source-form"><Upload accept=".zip,.html,.htm" disabled={busy||importing} showUploadList={false} beforeUpload={file=>{operate(()=>uploadWebsiteSource(site.id,file).then(()=>{refresh()}));return false}}><Button icon={<CloudUploadOutlined/>} disabled={busy||importing}>上传 ZIP／HTML</Button></Upload>
    <p>最大 20 MB、500 个文件。上传前移除 .git、node_modules 和 .env。</p>
    <Collapse items={[{key:'repository',label:'连接代码仓库',children:<Form form={form} layout="vertical" initialValues={{branch:'main',username:'git'}} onFinish={values=>operate(async()=>{await importWebsiteRepository(site.id,values);form.setFieldValue('token','');refresh()})}>
      <Form.Item name="url" label="仓库 HTTPS 地址" rules={[{required:true}]}><Input autoComplete="off"/></Form.Item><Form.Item name="branch" label="分支" rules={[{required:true}]}><Input/></Form.Item>
      <Collapse items={[{key:'credential',label:'私有仓库凭据',children:<><Form.Item name="username" label="凭据用户名"><Input autoComplete="off"/></Form.Item><Form.Item name="token" label="只读访问令牌"><Input.Password autoComplete="new-password"/></Form.Item><p>凭据通过专用接口加密保存，不进入向导聊天或模型请求。</p></>}]}/>
      <Button htmlType="submit" disabled={busy||importing}>确认导入仓库</Button>
    </Form>}]}/>
  </div>
}
function GuideRuntimeForm({config,load,busy,onChange,onPrepare}:{config:RuntimeConfig;load:boolean;busy:boolean;onChange:(config:RuntimeConfig,load:boolean)=>void;onPrepare:(config:RuntimeConfig,load:boolean)=>void}){
  const [form]=Form.useForm()
  return <Card size="small" title="确认运行设置"><Form form={form} layout="vertical" initialValues={{...config,load}} onValuesChange={(_,values)=>{const {load,...runtime}=values;onChange({...config,...runtime},!!load)}} onFinish={values=>{const {load,...runtime}=form.getFieldsValue(true);onPrepare({...config,...runtime},!!load)}}>
    <Form.Item name="kind" label="网站类型"><Select options={[['STATIC','普通网页'],['FRONTEND','React／Vue'],['NODE','Node.js'],['JAVA','Java'],['PYTHON','Python'],['CUSTOM','Dockerfile']].map(([value,label])=>({value,label}))}/></Form.Item>
    <Form.Item name="database" label="数据库"><Select options={[{value:'NONE',label:'不需要'},{value:'MYSQL',label:'MySQL'},{value:'POSTGRESQL',label:'PostgreSQL'}]}/></Form.Item>
    <Collapse items={[{key:'advanced',label:'高级运行设置',children:<><Form.Item name="root" label="网站目录"><Input/></Form.Item><Form.Item name="port" label="监听端口"><InputNumber min={1024} max={65535}/></Form.Item>
      {(['buildCommand','startCommand','outputDirectory','frontendRoot','apiPrefix','healthPath'] as const).map((name,i)=><Form.Item key={name} name={name} label={['构建命令','启动命令','前端输出目录','独立前端目录','接口前缀','健康检查路径'][i]}><Input/></Form.Item>)}</>}]}/>
    <Form.Item name="load" valuePropName="checked"><Checkbox>执行既有方案的小规模压力测试</Checkbox></Form.Item><p>环境变量和密钥在网站工作区专用表单填写；已有值继续沿用。</p>
    <Button type="primary" htmlType="submit" disabled={busy}>准备部署并自动检测</Button>
  </Form></Card>
}
