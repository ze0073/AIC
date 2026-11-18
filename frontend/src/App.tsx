import { lazy, Suspense, useEffect, useMemo, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, Navigate, Route, Routes, useLocation, useNavigate, useParams, useSearchParams } from 'react-router-dom'
import {
  Alert, App as AntApp, Badge, Button, Card, Checkbox, Col, Descriptions, Divider, Empty, Flex, Form,
  Input, Layout, List, Modal, Popconfirm, Progress, Row, Segmented, Select, Space, Spin, Statistic, Steps,
  Table, Tag, Typography, Upload,
} from 'antd'
import type { UploadProps } from 'antd'
import {
  ApartmentOutlined, ArrowLeftOutlined, ArrowRightOutlined, CloudUploadOutlined, CodeOutlined, DeleteOutlined,
  FolderOpenOutlined, LogoutOutlined, PlayCircleOutlined, PlusOutlined, SafetyCertificateOutlined, StopOutlined, ThunderboltOutlined,
} from '@ant-design/icons'
import { idempotencyKey, request, resetCsrf } from './api'
import {WorkflowContext} from './WorkflowNavigation'
import {QueryProblem} from './workflowShared'
import {PageBoundary} from './PageBoundary'
const DemoPage=lazy(()=>import('./pages/DemoPage').then(m=>({default:m.DemoPage})))
const ComparisonPage=lazy(()=>import('./pages/ComparisonPage').then(m=>({default:m.ComparisonPage})))
const SnapshotReportPage=lazy(()=>import('./pages/SnapshotReportPage').then(m=>({default:m.SnapshotReportPage})))
const CollaborationPage=lazy(()=>import('./quality').then(m=>({default:m.CollaborationPage})))
const QualityCenterPage=lazy(()=>import('./quality').then(m=>({default:m.QualityCenterPage})))
const TestRunPage=lazy(()=>import('./quality').then(m=>({default:m.TestRunPage})))
const AgentProjectPage=lazy(()=>import('./agent').then(m=>({default:m.AgentProjectPage})))
const AgentStartPage=lazy(()=>import('./agent').then(m=>({default:m.AgentStartPage})))
const AgentTaskPage=lazy(()=>import('./agent').then(m=>({default:m.AgentTaskPage})))
const AssistantSessionPage=lazy(()=>import('./assistant').then(m=>({default:m.AssistantSessionPage})))
const ProjectAssistantPage=lazy(()=>import('./assistant').then(m=>({default:m.ProjectAssistantPage})))
const Guide=lazy(()=>import('./Guide').then(m=>({default:m.Guide})))
const IssueRepairPage=lazy(()=>import('./IssueRepair').then(m=>({default:m.IssueRepairPage})))
import { PublicLogin } from './PublicLogin'
import { CardArtwork } from './CardArtwork'
import {VisualBanner,PrimaryPage} from './VisualBanner'
import { WorkbenchIntro } from './WorkbenchIntro'
import { WorkspaceRail } from './WorkspaceRail'
import { workspaceRailFor } from './workspaceRailState'
const WebsitePage=lazy(()=>import('./Websites').then(m=>({default:m.WebsitePage})))
const WebsitesPage=lazy(()=>import('./Websites').then(m=>({default:m.WebsitesPage})))
const AccountsPage=lazy(()=>import('./Accounts').then(m=>({default:m.AccountsPage})))
const SystemDiagnosticsPage=lazy(()=>import('./SystemDiagnostics').then(m=>({default:m.SystemDiagnosticsPage})))
import {ProfileMenu, UserCenterPage} from './UserCenter'
import {BrandLogo} from './BrandLogo'
import type { AiStatus, AnalysisTrace, AuthUser, Evidence, Finding, FindingReview, Patch, Project, Scan, TraceStep, Version } from './types'

const { Header, Content, Footer } = Layout
const terminal = new Set(['COMPLETED', 'PARTIAL', 'FAILED', 'CANCELLED'])
const stageNames: Record<string, string> = { FILE_CHECK:'文件检查', PAGE_START:'页面启动', DOM_ANALYSIS:'DOM 分析', CSS_ANALYSIS:'样式分析', RULE_CHECK:'规则检测', SCREENSHOT:'截图取证', KEYBOARD_TEST:'键盘测试', READING_ORDER:'阅读顺序分析', AI_ANALYSIS:'AI 辅助解释', AGGREGATION:'结果汇总', SCORING:'风险评分' }
const severity = { HIGH: { color:'red', text:'高' }, MEDIUM:{ color:'orange', text:'中' }, LOW:{ color:'blue', text:'低' } }

const versionSourceName:Record<string,string>={HTML:'原始 HTML',ZIP:'原始 ZIP',PATCH:'模板修复',AI_PATCH:'AI 候选修复',REPAIR_SOURCE:'修复源码',REPAIR_PATCH:'已修复源码'}
const patchStatusName:Record<string,string>={PENDING:'待确认',APPLIED:'已应用，等待复测',VERIFIED:'基础规则复测通过',FAILED:'复测未通过',REJECTED:'已拒绝',INVALID:'已失效',ROLLED_BACK:'已回滚'}
const scanStatusName:Record<string,string>={PENDING:'等待扫描',RUNNING:'扫描中',COMPLETED:'已完成',PARTIAL:'部分完成',FAILED:'失败',CANCELLED:'已取消'}

function verificationText(value?:string){
  try{
    const result=JSON.parse(value||'{}') as Record<string,unknown>;
    if(result.retestStatus==='COMPLETED'&&result.ruleCleared===true)return result.semanticReview==='HUMAN_ACCEPTED'?'基础规则复测通过，语义已人工确认':'基础规则复测通过';
    if(result.retestStatus==='COMPLETED')return '基础规则复测未通过';
    if(['FAILED','CANCELLED','PARTIAL'].includes(String(result.retestStatus)))return '复测未完成，可重新扫描';
    if(result.retestStatus==='PENDING')return '等待自动复测';
    return '尚未开始复测';
  }catch{return value||'-'}
}

function App({loginEntrance=false,onLoginEntranceConsumed}:{loginEntrance?:boolean;onLoginEntranceConsumed?:()=>void}={}) {
  const client=useQueryClient()
  const {message}=AntApp.useApp()
  const location=useLocation()
  useEffect(()=>{
    document.documentElement.scrollTop=0
    document.body.scrollTop=0
  },[location.pathname])
  const session=useQuery({queryKey:['auth-me'],queryFn:({signal})=>request<AuthUser>('/auth/me',{signal}),retry:false})
  useEffect(()=>{const handler=()=>{void client.cancelQueries({predicate:q=>q.queryKey[0]!=='auth-me'});resetCsrf();client.removeQueries({predicate:q=>q.queryKey[0]!=='auth-me'});client.setQueryData(['auth-me'],null)};window.addEventListener('auth-required',handler);return()=>window.removeEventListener('auth-required',handler)},[client])
  const logout=useMutation({mutationFn:()=>request<void>('/auth/logout',{method:'POST'}),onSuccess:async()=>{await client.cancelQueries({predicate:q=>q.queryKey[0]!=='auth-me'});resetCsrf();client.removeQueries({predicate:q=>q.queryKey[0]!=='auth-me'});client.setQueryData(['auth-me'],null)},onError:(error:Error)=>{message.error(error.message)}})
  if(session.isLoading)return <div className="session-loading"><Spin size="large"/></div>
  if(session.isError||!session.data)return <LoginPage onSuccess={()=>client.invalidateQueries({queryKey:['auth-me']})}/>
  const configuredRail=workspaceRailFor(location.pathname)
  const rail=configuredRail&&(!(configuredRail.section==='accounts'||configuredRail.section==='system')||session.data.platformRole==='ADMIN')?configuredRail:null
  return <Layout className="app-shell" data-rail-side={rail?.side}>
    <Header className="topbar">
      <div className="topbar-left">
        <Link to="/" className="brand brand-integrated" aria-label="视界，返回工作台"><BrandLogo/></Link>
        <nav className="topnav" aria-label="主要导航">
          <Link className={location.pathname==='/'?'active':''} to="/">工作台</Link>
          <Link className={location.pathname.startsWith('/sites')?'active':''} to="/sites">我的网站</Link>
          {session.data.platformRole==='ADMIN'&&<><Link className={location.pathname.startsWith('/accounts')?'active':''} to="/accounts">账号管理</Link><Link className={location.pathname==='/system'?'active':''} to="/system">运行诊断</Link></>}
          <Link className={location.pathname.startsWith('/agent')||location.pathname.includes('/assistant')?'active':''} to="/agent">智能检查</Link>
        </nav>
      </div>
      <Space className="topbar-actions"><Tag color="green">预览版</Tag><ProfileMenu key={session.data.id} user={session.data} onLogout={()=>logout.mutate()} loggingOut={logout.isPending}/></Space>
    </Header>
    {rail&&<WorkspaceRail key={`${session.data.id}:${rail.section}`} config={rail} userId={session.data.id}/>}
    <Content className="content"><WorkflowContext/><PageBoundary key={location.pathname}><Suspense fallback={<Spin aria-label="加载页面"/>}><Routes>
      <Route path="/system" element={session.data.platformRole==='ADMIN'?<PrimaryPage route="/system"><SystemDiagnosticsPage/></PrimaryPage>:<Navigate to="/" replace/>}/>
      <Route path="/profile" element={<UserCenterPage key={session.data.id} user={session.data}/>}/>
      <Route path="/sites" element={<PrimaryPage route="/sites"><WebsitesPage/></PrimaryPage>}/>
      <Route path="/accounts" element={session.data.platformRole==='ADMIN'?<PrimaryPage route="/accounts"><AccountsPage/></PrimaryPage>:<Navigate to="/" replace/>}/>
      <Route path="/sites/:siteId" element={<WebsitePage/>}/>
      <Route path="/projects/:projectId/demo" element={<DemoPage />} />
      <Route path="/projects/:projectId/comparison" element={<ComparisonPage />} />
      <Route path="/projects/:projectId/quality" element={<QualityCenterPage />} />
      <Route path="/agent" element={<PrimaryPage route="/agent"><AgentProjectPage/></PrimaryPage>} />
      <Route path="/projects/:projectId/agent" element={<AgentStartPage />} />
      <Route path="/agent-tasks/:taskId" element={<AgentTaskPage />} />
      <Route path="/projects/:projectId/assistant" element={<ProjectAssistantPage />} />
      <Route path="/assistant-sessions/:sessionId" element={<AssistantSessionPage />} />
      <Route path="/projects/:projectId/collaboration" element={<CollaborationPage />} />
      <Route path="/test-runs/:runId" element={<TestRunPage />} />
      <Route path="/issues/:issueId/repair" element={<IssueRepairPage />} />
      <Route path="/" element={<PrimaryPage route="/" skipEntrance={loginEntrance}><Home loginEntrance={loginEntrance} onLoginEntranceConsumed={onLoginEntranceConsumed}/></PrimaryPage>} />
      <Route path="/projects/:projectId/import" element={<ImportPage />} />
      <Route path="/projects/:projectId/preview" element={<PreviewPage />} />
      <Route path="/projects/:projectId/versions" element={<VersionsPage />} />
      <Route path="/scans/:scanId/progress" element={<ProgressPage />} />
      <Route path="/scans/:scanId/findings" element={<FindingsPage />} />
      <Route path="/scans/:scanId/interaction" element={<InteractionPage />} />
      <Route path="/findings/:findingId" element={<FindingPage />} />
      <Route path="/fix-patches/:patchId" element={<PatchPage />} />
      <Route path="/scans/:scanId/report" element={<SnapshotReportPage />} />
      <Route path="*" element={<Alert type="info" showIcon title="页面地址不存在" action={<Link to="/">返回工作台</Link>}/>} />
    </Routes></Suspense></PageBoundary></Content>
    <Suspense fallback={null}><Guide key={session.data.id} userId={session.data.id}/></Suspense>
    <Footer className="footer">视界 · AI 网站质量测试平台 · 自动检测结果与引擎候选均需结合适用范围理解</Footer>
  </Layout>
}

function LoginPage({onSuccess}:{onSuccess:()=>void}){
  return <PublicLogin onSuccess={onSuccess}/>
}

function PageHead({ title, subtitle, back }: { title:string; subtitle?:string; back?:string }) {
  return <div className="page-head"><div>{back && <Link to={back}><ArrowLeftOutlined /> 返回</Link>}<Typography.Title level={2}>{title}</Typography.Title>{subtitle && <Typography.Paragraph type="secondary">{subtitle}</Typography.Paragraph>}</div></div>
}

export function RiskScoreNotice(){
  return <Alert showIcon type="info" title="内部风险分，不是 WCAG 合规评分" description="分数越高表示当前规则下检出风险越少；自动候选与已驳回结果不计分。" />
}

function Home({loginEntrance,onLoginEntranceConsumed}:{loginEntrance:boolean;onLoginEntranceConsumed?:()=>void}) {
  const nav=useNavigate(), query=useQueryClient(), { message }=AntApp.useApp();
  const [playLoginIntro]=useState(loginEntrance);
  useEffect(()=>{if(playLoginIntro)onLoginEntranceConsumed?.()},[playLoginIntro,onLoginEntranceConsumed]);
  const [open,setOpen]=useState(false); const [projectSearch,setProjectSearch]=useState(''); const [showAllProjects,setShowAllProjects]=useState(false); const [form]=Form.useForm();
  const projects=useQuery({queryKey:['projects'],queryFn:()=>request<Project[]>('/projects'),refetchInterval:q=>q.state.data?.some(p=>p.deletionStatus==='DELETING')?2000:false});
  const [cleanupId,setCleanupId]=useState<string>();
  const cleanup=useQuery({queryKey:['project-cleanup',cleanupId],queryFn:()=>request<{id:string;status:string;errorMessage?:string}>(`/projects/cleanup/${cleanupId}`),enabled:!!cleanupId,refetchInterval:q=>['COMPLETED','FAILED'].includes(q.state.data?.status||'')?false:2000});
  useEffect(()=>{if(cleanup.data?.status==='COMPLETED'){void query.invalidateQueries({queryKey:['projects']});setCleanupId(undefined);message.success('项目及关联文件已清理')}},[cleanup.data?.status,query,message]);
  const recent=useQuery({queryKey:['recent-test-runs'],queryFn:()=>request<Array<{id:string;projectId:string;projectName:string;status:string;createdAt:string;issueCount:number}>>('/test-runs/recent')});
  const create=useMutation({mutationFn:({startMode:_,...v}:{name:string;description?:string;startMode?:'ONLINE'|'STATIC'})=>request<Project>('/projects',{method:'POST',headers:{'Idempotency-Key':idempotencyKey()},body:JSON.stringify(v)}),onSuccess:(p,values)=>{setOpen(false);query.invalidateQueries({queryKey:['projects']});nav(values.startMode==='STATIC'?`/projects/${p.id}/import`:`/projects/${p.id}/quality`)},onError:e=>message.error((e as Error).message)});
  const remove=useMutation({mutationFn:(id:string)=>request<{id:string}>(`/projects/${id}`,{method:'DELETE',headers:{'Idempotency-Key':idempotencyKey()}}),onSuccess:job=>{setCleanupId(job.id);void query.invalidateQueries({queryKey:['projects']});message.info('清理任务已创建，完成后自动移除项目')},onError:e=>message.error((e as Error).message)});
  const matchingProjects=(projects.data||[]).filter(p=>`${p.name} ${p.description||''}`.toLowerCase().includes(projectSearch.trim().toLowerCase()));
  const visibleProjects=projectSearch||showAllProjects?matchingProjects:matchingProjects.slice(0,6);
  const latest=recent.data?.[0];
  return <>
    {playLoginIntro&&<WorkbenchIntro/>}
    <Link to="/sites" className="hosting-home-entry"><span><CloudUploadOutlined/><b>部署我的网站</b><small>上传或连接仓库，在平台内完成检测与修复</small></span><ArrowRightOutlined/></Link>
    <VisualBanner theme="workbench" className="hero-panel home-hero">
      <div className="home-hero-copy">
        <span className="home-eyebrow"><span className="live-dot"/>SHIJIE / 智能质量工作台</span>
        <Typography.Title>让每一次上线，<br/><em>都更有把握。</em></Typography.Title>
        <Typography.Paragraph>选择网站，用一句话描述目标。系统会推荐安全的检查方案，执行前让你确认，结束后直接解释问题和改进方法。</Typography.Paragraph>
        <Space wrap className="home-hero-actions">
          <Button type="primary" size="large" icon={<ThunderboltOutlined/>} onClick={()=>nav('/agent')}>开始智能检查 <ArrowRightOutlined/></Button>
          <Button size="large" ghost onClick={()=>document.getElementById('projects')?.scrollIntoView({behavior:window.matchMedia('(prefers-reduced-motion: reduce)').matches?'auto':'smooth'})}>打开已有项目</Button>
        </Space>
        <div className="hero-assurance"><span>✓ 推荐安全设置</span><span>✓ 运行前再确认</span><span>✓ 自动生成通俗总结</span></div>
      </div>
      <div className="home-hero-visual" aria-label="智能测试的三个阶段">
        <div className="visual-topline"><span><span className="visual-signal"/> AGENT WORKSPACE</span><span>WORKFLOW / 03</span></div>
        <div className="hero-artwork-window" aria-hidden="true"><span>DEPLOY / INSPECT / IMPROVE</span></div>
        <div className="visual-step-list">
          <div><span className="visual-step-index">01</span><span><b>选择网站</b><small>使用已登记项目，无需填写复杂参数</small></span><FolderOpenOutlined/></div>
          <div><span className="visual-step-index">02</span><span><b>确认方案</b><small>系统说明会检查什么、不会做什么</small></span><SafetyCertificateOutlined/></div>
          <div><span className="visual-step-index">03</span><span><b>读懂结果</b><small>图表、证据和改进建议放在一起</small></span><ArrowRightOutlined/></div>
        </div>
      </div>
    </VisualBanner>
    <div className="home-overview" aria-label="工作台概览">
      <div><span className="overview-icon"><FolderOpenOutlined/></span><span><small>已登记项目</small><b>{projects.data?.length??'—'}</b></span></div>
      <div><span className="overview-icon"><PlayCircleOutlined/></span><span><small>近期测试记录</small><b>{recent.data?.length??'—'}</b></span></div>
      <div><span className="overview-icon"><SafetyCertificateOutlined/></span><span><small>检测能力</small><b>3 <i>类模块</i></b></span></div>
    </div>
    <div className="section-title"><div><span className="section-kicker">ACTIVITY / 近期动态</span><Typography.Title level={3}>最近测试</Typography.Title></div>{latest&&<Button type="link" onClick={()=>nav(`/test-runs/${latest.id}`)}>查看最新记录 <ArrowRightOutlined/></Button>}</div>
    {recent.isLoading?<Spin/>:recent.isError?<QueryProblem error={recent.error} retry={()=>void recent.refetch()}/>:recent.data?.length?<div className="recent-runs">{recent.data.slice(0,4).map((r,index)=><button type="button" className="recent-run" key={r.id} onClick={()=>nav(`/test-runs/${r.id}`)}><span className="recent-run-top"><span className="recent-run-index">{String(index+1).padStart(2,'0')}</span><Tag color={r.status==='COMPLETED'?'green':r.status==='RUNNING'?'blue':'orange'}>{scanStatusName[r.status]||r.status}</Tag></span><strong className="recent-run-project">{r.projectName}</strong><span className="recent-run-bottom"><span>{new Date(r.createdAt).toLocaleString('zh-CN')} · {r.issueCount} 个问题或候选</span><ArrowRightOutlined/></span></button>)}</div>:<Card className="recent-empty"><Empty description="还没有在线测试；先在项目的质量测试中心登记目标和测试套件。"/></Card>}
    <div className="section-title" id="projects"><div><span className="section-kicker">WORKSPACES / 项目空间</span><Typography.Title level={3}>我的项目</Typography.Title></div><Space><Input.Search aria-label="搜索项目" placeholder="搜索项目" allowClear value={projectSearch} onChange={event=>setProjectSearch(event.target.value)} style={{width:220}}/><Button type="primary" icon={<PlusOutlined/>} onClick={()=>setOpen(true)}>创建项目</Button></Space></div>
    {cleanup.data?.status==='FAILED'&&<Alert type="warning" title="项目清理需要重试" description={cleanup.data.errorMessage}/>}
    {projects.isLoading?<Spin/>:projects.isError?<QueryProblem error={projects.error} retry={()=>void projects.refetch()}/>:projects.data?.length?<>
      <Row gutter={[18,18]}>{visibleProjects.map((p,index)=><Col xs={24} md={12} lg={8} key={p.id}>
        <Card hoverable cover={<CardArtwork index={index} theme="workbench"/>} className="project-card" onClick={()=>{if(p.deletionStatus==='ACTIVE'||!p.deletionStatus)nav(`/projects/${p.id}/quality`)}}>
          <Flex justify="space-between"><span className="project-icon"><FolderOpenOutlined/></span><Space><Tag>{p.deletionStatus==='DELETING'?'正在清理':p.deletionStatus==='DELETE_FAILED'?'清理失败，可重试':p.currentVersionId?'含网页文件':'在线网站项目'}</Tag>{p.projectRole==='ADMIN'&&<Popconfirm title={p.deletionStatus==='DELETE_FAILED'?'重试清理项目？':'删除这个项目？'} description="版本、扫描、证据和报告也会一并删除。" okText="删除" cancelText="取消" onConfirm={event=>{event?.stopPropagation();remove.mutate(p.id)}}><Button type="text" danger icon={<DeleteOutlined/>} aria-label={`删除 ${p.name}`} disabled={p.deletionStatus==='DELETING'||remove.isPending} onClick={event=>event.stopPropagation()}/></Popconfirm>}</Space></Flex>
          <span className="project-card-index">PROJECT / {String(index+1).padStart(2,'0')}</span>
          <Typography.Title level={4}>{p.name}</Typography.Title>
          <Typography.Paragraph ellipsis={{rows:2}} type="secondary">{p.description||'还没有项目说明'}</Typography.Paragraph>
          <Flex gap={8} wrap><Button disabled={!!p.deletionStatus&&p.deletionStatus!=='ACTIVE'} type="primary" size="small" onClick={event=>{event.stopPropagation();nav(`/projects/${p.id}/quality`)}}>开始检查 <ArrowRightOutlined/></Button><Button size="small" onClick={event=>{event.stopPropagation();nav(p.currentVersionId?`/projects/${p.id}/preview`:`/projects/${p.id}/import`)}}>网页文件</Button></Flex>
        </Card>
      </Col>)}</Row>
      {matchingProjects.length===0&&<Empty description="未找到匹配项目"/>}
      {!projectSearch&&matchingProjects.length>6&&<Flex justify="center" style={{marginTop:20}}><Button onClick={()=>setShowAllProjects(value=>!value)}>{showAllProjects?'收起项目':`查看全部 ${matchingProjects.length} 个项目`}</Button></Flex>}
    </>:<Empty description="还没有项目，可新建项目开始检查"/>}
    <Modal title="创建项目空间" open={open} onCancel={()=>setOpen(false)} onOk={()=>form.submit()} okText="创建并继续" confirmLoading={create.isPending}>
      <div className="create-project-intro"><span><FolderOpenOutlined/></span><div><b>先给网站建一个项目</b><p>项目会集中保存检查方案、历史结果和修复记录。</p></div></div>
      <Form form={form} layout="vertical" initialValues={{startMode:'ONLINE'}} onFinish={v=>create.mutate(v)}>
        <Form.Item name="name" label="项目名称" rules={[{required:true,message:'请输入项目名称'},{max:120}]}><Input size="large" placeholder="例如：学校门户网站"/></Form.Item>
        <Form.Item name="description" label="项目说明（可选）" rules={[{max:500}]}><Input.TextArea rows={2} placeholder="例如：每次发布前检查报名流程"/></Form.Item>
        <Form.Item name="startMode" label="接下来要做什么？">
          <Segmented block options={[{label:'检查在线网站（推荐）',value:'ONLINE'},{label:'上传静态网页',value:'STATIC'}]}/>
        </Form.Item>
        <Alert type="info" showIcon title="创建项目不会立即开始检查" description="下一页会继续引导你添加网站和选择检查方案。"/>
      </Form>
    </Modal></>
}

function ImportPage(){
  const {projectId=''}=useParams(),nav=useNavigate(),{message}=AntApp.useApp(); const [uploading,setUploading]=useState(false);const [entryPage,setEntryPage]=useState('');
  const upload:NonNullable<UploadProps['customRequest']>=async(option)=>{setUploading(true);try{const data=new FormData();data.append('file',option.file as File);const query=entryPage.trim()?`?entryPage=${encodeURIComponent(entryPage.trim())}`:'';await request<Version>(`/projects/${projectId}/upload${query}`,{method:'POST',headers:{'Idempotency-Key':idempotencyKey()},body:data});message.success('项目导入成功');option.onSuccess?.({});nav(`/projects/${projectId}/preview`)}catch(e){option.onError?.(e as Error);message.error((e as Error).message)}finally{setUploading(false)}};
  return <><PageHead title="导入静态网页" subtitle="支持单个 HTML 或 ZIP 项目；系统不会执行项目中的命令和构建脚本。" back="/"/><Card><Typography.Text strong>入口页面（可选）</Typography.Text><Input value={entryPage} onChange={e=>setEntryPage(e.target.value)} placeholder="例如 index.html；留空时自动选择" style={{margin:'8px 0 18px'}}/><Upload.Dragger accept=".zip,.html,.htm" maxCount={1} customRequest={upload} disabled={uploading} showUploadList><p className="ant-upload-drag-icon"><CloudUploadOutlined/></p><p className="ant-upload-text">点击或拖拽文件到这里</p><p className="ant-upload-hint">上传上限 20 MB · 最多 500 个文件 · 解压上限 100 MB</p></Upload.Dragger><Alert className="import-note" type="info" showIcon title="安全边界" description="拒绝路径穿越、符号链接、嵌套压缩包和可执行文件；预览与扫描默认阻断网络访问。"/></Card></>
}

function PreviewPage(){
  const {projectId=''}=useParams(),nav=useNavigate(),{message,modal}=AntApp.useApp(); const [width,setWidth]=useState<'desktop'|'mobile'>('desktop');const [previewPage,setPreviewPage]=useState('');const [scanPages,setScanPages]=useState<string[]>([]);const [enableAi,setEnableAi]=useState(false);
  const project=useQuery({queryKey:['project',projectId],queryFn:()=>request<Project>(`/projects/${projectId}`)});
  const files=useQuery({queryKey:['files',projectId,project.data?.currentVersionId],enabled:!!project.data?.currentVersionId,queryFn:()=>request<{entryPage:string;files:string[]}>(`/projects/${projectId}/files?versionId=${project.data!.currentVersionId}`)});
  const ai=useQuery({queryKey:['ai-status'],queryFn:()=>request<AiStatus>('/ai/status'),retry:false});
  const htmlFiles=useMemo(()=>files.data?.files.filter(file=>/\.html?$/i.test(file))||[],[files.data?.files]);
  useEffect(()=>{if(files.data&&!previewPage){setPreviewPage(files.data.entryPage);setScanPages(htmlFiles.slice(0,10))}},[files.data,htmlFiles,previewPage]);
  const scan=useMutation({mutationFn:({aiConsent}:{aiConsent:boolean})=>request<Scan>(`/projects/${projectId}/scans`,{method:'POST',headers:{'Idempotency-Key':idempotencyKey()},body:JSON.stringify({pages:scanPages,enableAi,aiConsent,enableKeyboardTest:true,rules:['R001','R002','R003','R004','R005','R006','R007','R008','R009']})}),onSuccess:s=>nav(`/scans/${s.id}/progress`),onError:e=>message.error((e as Error).message)});
  const startScan=()=>{if(!enableAi){scan.mutate({aiConsent:false});return}modal.confirm({title:'确认启用 AI 语义分析',content:`系统将向 ${ai.data?.provider||'已配置的'} 外部 AI 服务发送问题元素的最小截图、相关 HTML/CSS 片段和周边文字；不会发送整个网页项目、数据库数据或密钥。AI 结果仅作为自动候选，不会自动确认为风险。`,okText:'同意并开始扫描',cancelText:'取消',onOk:()=>scan.mutate({aiConsent:true})})};
  if(project.isError)return <QueryProblem error={project.error} retry={()=>void project.refetch()}/>;if(files.isError)return <QueryProblem error={files.error} retry={()=>void files.refetch()}/>;
  if(project.isLoading||files.isLoading)return <Spin/>; if(!project.data?.currentVersionId)return <Navigate to={`/projects/${projectId}/import`} replace/>;
  const src=`/api/projects/${projectId}/versions/${project.data.currentVersionId}/preview/${previewPage||files.data?.entryPage}`;
  return <><PageHead title={project.data.name} subtitle="受限预览环境：网络、下载和持久化能力已关闭" back="/"/><Flex gap={10} justify="space-between" wrap><Space wrap><Select aria-label="预览页面" value={previewPage||files.data?.entryPage} style={{width:220}} onChange={setPreviewPage} options={htmlFiles.map(value=>({value,label:value}))}/><Segmented value={width} onChange={v=>setWidth(v as 'desktop'|'mobile')} options={[{label:'桌面宽度',value:'desktop'},{label:'移动宽度',value:'mobile'}]}/><Button icon={<ApartmentOutlined/>} onClick={()=>nav(`/projects/${projectId}/versions`)}>版本管理</Button></Space><Button type="primary" size="large" icon={<PlayCircleOutlined/>} loading={scan.isPending} disabled={scanPages.length===0} onClick={startScan}>扫描所选页面</Button></Flex><Card className="preview-card" bodyStyle={{padding:12}}><iframe title="项目预览" sandbox="allow-scripts allow-forms" className={width==='mobile'?'preview mobile':'preview'} src={src}/></Card><Card size="small"><Typography.Text strong>扫描范围（最多 10 页）</Typography.Text><Select mode="multiple" maxCount={10} value={scanPages} onChange={setScanPages} options={htmlFiles.map(value=>({value,label:value}))} style={{width:'100%',marginTop:10}} placeholder="选择需要扫描的 HTML 页面"/><div className="ai-option"><Checkbox checked={enableAi} disabled={!ai.data?.available} onChange={event=>setEnableAi(event.target.checked)}>启用 AI 语义分析（P1）</Checkbox><Typography.Text type="secondary">{ai.data?.available?`将使用 ${ai.data.provider} / ${ai.data.model}，扫描前仍需确认数据发送范围。`:ai.isLoading?'正在检查 AI 服务配置…':ai.data?.unavailableReason||'AI 服务当前不可用，仍可执行完整离线规则扫描。'}</Typography.Text></div></Card></>
}

function ProgressPage(){
  const {scanId=''}=useParams(),nav=useNavigate(),client=useQueryClient(),{message}=AntApp.useApp();
  const scan=useQuery({queryKey:['scan',scanId],queryFn:()=>request<Scan>(`/scans/${scanId}`),refetchInterval:q=>terminal.has(q.state.data?.status||'')?false:3000});
  const logs=useQuery({queryKey:['scan-logs',scanId,scan.data?.progress],queryFn:()=>request<Array<{id:string;level:string;message:string;stage:string}>>(`/scans/${scanId}/logs`),enabled:!!scan.data});
  useEffect(()=>{const es=new EventSource(`/api/scans/${scanId}/events`);es.addEventListener('scan-progress',e=>{client.setQueryData(['scan',scanId],JSON.parse((e as MessageEvent).data));client.invalidateQueries({queryKey:['scan-logs',scanId]})});return()=>es.close()},[scanId,client]);
  const cancel=useMutation({mutationFn:()=>request<Scan>(`/scans/${scanId}/cancel`,{method:'POST'}),onSuccess:()=>message.info('已提交取消请求'),onError:e=>message.error((e as Error).message)});
  const s=scan.data;if(!s)return <Spin/>; const done=terminal.has(s.status);
  return <><PageHead title={done?'扫描已结束':'正在分析网页'} subtitle={`任务 ${scanId}`} back="/"/><Card className="progress-card"><Row gutter={32} align="middle"><Col xs={24} md={8}><Progress type="dashboard" percent={s.progress} status={s.status==='FAILED'?'exception':s.status==='COMPLETED'?'success':'active'} strokeColor="#176b87"/></Col><Col xs={24} md={16}><Tag color={done?(s.status==='COMPLETED'?'green':'orange'):'processing'}>{scanStatusName[s.status]||s.status}</Tag><Typography.Title level={3}>{stageNames[s.stage]||s.stage}</Typography.Title><Typography.Paragraph type="secondary">已发现 {s.foundCount||0} 条结果，其中 {s.reviewCount||0} 条为自动候选；页面轨迹会在没有问题时照常保存。</Typography.Paragraph>{s.errorMessage&&<Alert type="warning" showIcon title={s.errorMessage}/>}<Space wrap><Button disabled={!done||s.status==='FAILED'} type="primary" onClick={()=>nav(`/scans/${scanId}/findings`)}>查看问题</Button><Button disabled={!done||s.status==='FAILED'} onClick={()=>nav(`/scans/${scanId}/interaction`)}>交互分析</Button><Button disabled={!done||s.status==='FAILED'} onClick={()=>nav(`/scans/${scanId}/report`)}>查看报告</Button>{!done&&<Button danger icon={<StopOutlined/>} loading={cancel.isPending} onClick={()=>cancel.mutate()}>取消扫描</Button>}</Space></Col></Row></Card><Card title="任务日志"><List size="small" dataSource={logs.data||[]} renderItem={l=><List.Item><Badge status={l.level==='ERROR'?'error':l.level==='WARN'?'warning':'processing'}/><Typography.Text type="secondary">{stageNames[l.stage]||l.stage}</Typography.Text><span className="grow">{l.message}</span></List.Item>}/></Card></>
}

function FindingsPage(){
  const {scanId=''}=useParams();const [params,setParams]=useSearchParams();
  const sev=params.get('severity')||undefined,rule=params.get('ruleId')||undefined,status=params.get('status')||undefined,source=params.get('source')||undefined,sort=params.get('sort')||'severity';
  const scan=useQuery({queryKey:['scan',scanId],queryFn:()=>request<Scan>(`/scans/${scanId}`)});
  const search=new URLSearchParams({...sev&&{severity:sev},...rule&&{ruleId:rule},...status&&{status},...source&&{source},sort});
  const list=useQuery({queryKey:['findings',scanId,sev,rule,status,source,sort],queryFn:()=>request<{items:Finding[];total:number}>(`/scans/${scanId}/findings?${search}`)});
  const setFilter=(key:string,value?:string)=>{value?params.set(key,value):params.delete(key);setParams(params)};
  const stateLabel:Record<string,{text:string;color:string}>={NEEDS_REVIEW:{text:'自动候选',color:'gold'},CONFIRMED:{text:'已记录风险',color:'red'},DISMISSED:{text:'已驳回',color:'default'},PENDING:{text:'确定性问题',color:'blue'}};
  const columns=[{title:'结论',dataIndex:'status',width:118,render:(v:string)=><Tag color={(stateLabel[v]||stateLabel.PENDING).color}>{(stateLabel[v]||stateLabel.PENDING).text}</Tag>},{title:'等级',dataIndex:'severity',width:76,render:(v:keyof typeof severity)=><Tag color={severity[v].color}>{severity[v].text}</Tag>},{title:'规则',dataIndex:'ruleId',width:72},{title:'问题',dataIndex:'title',render:(v:string,r:Finding)=><Link to={`/findings/${r.id}`}>{v}</Link>},{title:'页面',dataIndex:'pagePath'},{title:'来源',dataIndex:'source',width:95,render:(v:string)=><Tag>{v==='HEURISTIC'?'启发式':'确定性规则'}</Tag>}];
  return <><PageHead title="问题清单" subtitle={`共 ${list.data?.total||0} 条可追溯结果`} back={`/scans/${scanId}/progress`}/>{scan.data&&<Row gutter={[12,12]} className="stats"><Col xs={12} md={5}><Card><Statistic title="内部风险分" value={scan.data.riskScore??'-'} suffix="/100"/></Card></Col><Col xs={12} md={5}><Card><Statistic title="高风险" value={scan.data.highCount} valueStyle={{color:'#cf3f3f'}}/></Card></Col><Col xs={12} md={5}><Card><Statistic title="中风险" value={scan.data.mediumCount} valueStyle={{color:'#b26a00'}}/></Card></Col><Col xs={12} md={4}><Card><Statistic title="低风险" value={scan.data.lowCount}/></Card></Col><Col xs={12} md={5}><Card><Statistic title="自动候选" value={scan.data.reviewCount||0} valueStyle={{color:'#9b6900'}}/></Card></Col></Row>}<RiskScoreNotice/><Flex justify="end" style={{marginTop:12}}><Link to={`/scans/${scanId}/interaction`}>查看键盘焦点与阅读顺序证据 →</Link></Flex><Card className="table-card"><Flex gap={10} wrap className="filters"><Select allowClear placeholder="严重程度" value={sev} style={{width:140}} onChange={v=>setFilter('severity',v)} options={Object.entries(severity).map(([value,x])=>({value,label:x.text+'风险'}))}/><Select allowClear placeholder="规则" value={rule} style={{width:130}} onChange={v=>setFilter('ruleId',v)} options={Array.from({length:9},(_,i)=>({value:`R00${i+1}`,label:`R00${i+1}`}))}/><Select allowClear placeholder="审阅状态" value={status} style={{width:150}} onChange={v=>setFilter('status',v)} options={Object.entries(stateLabel).map(([value,x])=>({value,label:x.text}))}/><Select allowClear placeholder="来源" value={source} style={{width:140}} onChange={v=>setFilter('source',v)} options={[{value:'RULE',label:'确定性规则'},{value:'HEURISTIC',label:'启发式'}]}/><Select aria-label="排序" value={sort} style={{width:150}} onChange={v=>setFilter('sort',v)} options={[{value:'severity',label:'按风险等级'},{value:'review',label:'自动候选优先'}]}/></Flex><Table rowKey="id" loading={list.isLoading} dataSource={list.data?.items} columns={columns} pagination={false}/></Card></>
}

function InteractionPage(){
  const {scanId=''}=useParams(); const [params,setParams]=useSearchParams();
  const traces=useQuery({queryKey:['analysis-traces',scanId],queryFn:()=>request<AnalysisTrace[]>(`/scans/${scanId}/analysis-traces`)});
  const pages=Array.from(new Set((traces.data||[]).map(item=>item.pagePath)));
  const page=params.get('pagePath')||pages[0]||'';
  const view=params.get('type')==='READING_ORDER'?'READING_ORDER':'KEYBOARD_FOCUS';
  useEffect(()=>{if(!params.get('pagePath')&&pages[0]){params.set('pagePath',pages[0]);setParams(params,{replace:true})}},[pages,params,setParams]);
  const selected=traces.data?.find(item=>item.pagePath===page&&item.traceType===view);
  const setParam=(key:string,value:string)=>{params.set(key,value);setParams(params)};
  return <><PageHead title="交互路径与阅读顺序" subtitle="编号截图与文字表格同步展示，颜色不是唯一识别方式。" back={`/scans/${scanId}/findings`}/><Card><Flex gap={12} wrap justify="space-between"><Select aria-label="分析页面" value={page||undefined} style={{minWidth:240}} onChange={value=>setParam('pagePath',value)} options={pages.map(value=>({value,label:value}))}/><Segmented value={view} onChange={value=>setParam('type',String(value))} options={[{label:'键盘焦点路径',value:'KEYBOARD_FOCUS'},{label:'阅读顺序',value:'READING_ORDER'}]}/></Flex></Card>{traces.isLoading?<Spin/>:selected?<InteractionEvidence trace={selected}/>:<Empty description="当前页面没有该类轨迹；可能是扫描时未启用对应规则。"/>}</>
}

export function InteractionEvidence({trace,focusSelector}:{trace:AnalysisTrace;focusSelector?:string}){
  const [focusDirection,setFocusDirection]=useState<'forwardSteps'|'reverseSteps'>('forwardSteps');
  const [orderMode,setOrderMode]=useState<'domSequence'|'visualSequence'>('domSequence');
  useEffect(()=>{setFocusDirection('forwardSteps');setOrderMode('domSequence')},[trace.id]);
  const payload=trace.payload||{};
  const isFocus=trace.traceType==='KEYBOARD_FOCUS';
  const steps=(payload[isFocus?focusDirection:orderMode] as TraceStep[]|undefined)||[];
  const inversions=(payload.inversions as Array<Record<string,unknown>>|undefined)||[];
  const explicit=(payload.explicitOrder as Array<Record<string,unknown>>|undefined)||[];
  const matchedIndex=focusSelector?steps.findIndex(step=>step.selector===focusSelector):-1;
  const focusIndex=matchedIndex>=0?matchedIndex:undefined;
  return <Card className="interaction-card" title={<Space><span>{isFocus?'键盘焦点路径':'阅读顺序证据'}</span><Tag color={trace.status==='RISK'||trace.status==='PARTIAL'?'gold':trace.status==='UNSUPPORTED'?'default':'green'}>{trace.status}</Tag></Space>} extra={isFocus?<Segmented value={focusDirection} onChange={value=>setFocusDirection(value as typeof focusDirection)} options={[{label:'正向 Tab',value:'forwardSteps'},{label:'反向 Shift+Tab',value:'reverseSteps'}]}/>:<Segmented value={orderMode} onChange={value=>setOrderMode(value as typeof orderMode)} options={[{label:'DOM 序列',value:'domSequence'},{label:'视觉序列',value:'visualSequence'}]}/> }>
    {!isFocus&&trace.status==='UNSUPPORTED'&&<Alert type="info" showIcon title="当前书写模式未生成问题" description={String(payload.reason||'只支持 horizontal-tb。')}/>} 
    {isFocus&&<Alert type={payload.truncated||payload.cycleDetected?'warning':'info'} showIcon title={`最多采集 ${String(payload.maxSteps||30)} 步`} description={`循环：${payload.cycleDetected?'是':'否'}；截断：${payload.truncated?'是':'否'}；无焦点目标：${payload.noFocusTarget?'是':'否'}`}/>} 
    {!isFocus&&trace.status!=='UNSUPPORTED'&&<Alert type="warning" showIcon title="R009 仅生成自动候选" description={`候选 ${String(payload.candidateCount||0)} 个，明确倒置 ${String(payload.inversionCount||0)} 对，比例 ${String(payload.inversionRatio||0)}；CSS order 重排 ${explicit.length} 项。系统不会自动确认，候选不进入内部风险分。`}/>} 
    {trace.artifactId&&<TracePreview artifactId={trace.artifactId} steps={steps} focusIndex={focusIndex}/>} 
    <Table size="small" rowKey={(row:TraceStep)=>`${row.selector}-${row.index??row.domIndex??row.visualIndex??0}`} pagination={{pageSize:12}} dataSource={steps} columns={[
      {title:'序号',width:68,render:(_:unknown,row:TraceStep,index:number)=><span className="path-number">{isFocus?row.index??index+1:index+1}</span>},
      {title:'元素',render:(_:unknown,row:TraceStep)=><><Typography.Text code>{row.selector}</Typography.Text><div><Typography.Text type="secondary">{row.accessibleName||row.name||`${row.tag||''} ${row.role||''}`}</Typography.Text></div></>},
      ...(isFocus?[{title:'tabindex',dataIndex:'tabindex',width:90,render:(value:unknown)=>String(value??'默认')},{title:'焦点样式',dataIndex:'focusIndicator',width:120,render:(value:string)=><Tag color={value==='VISIBLE'?'green':'gold'}>{value==='VISIBLE'?'可见':'疑似不可见'}</Tag>}]:[{title:'DOM / 视觉',width:120,render:(_:unknown,row:TraceStep)=>`${(row.domIndex??0)+1} / ${(row.visualIndex??0)+1}`},{title:'CSS order',dataIndex:'order',width:100}])
    ]}/>
    {!isFocus&&inversions.length>0&&<><Divider/><Typography.Title level={5}>冲突对与判断依据</Typography.Title><List size="small" dataSource={inversions} renderItem={(item,index)=><List.Item><span className="path-number">{index+1}</span><Typography.Text code>{String(item.visuallyBefore)}</Typography.Text><span>在视觉位置上先于</span><Typography.Text code>{String(item.domBefore)}</Typography.Text><Typography.Text type="secondary">（至少 16px 的上下错位或同行逆序）</Typography.Text></List.Item>}/></>}
  </Card>
}

function TracePreview({artifactId,steps,focusIndex}:{artifactId:string;steps:TraceStep[];focusIndex?:number}){
  const [natural,setNatural]=useState({width:1440,height:1000});
  return <div className="trace-preview"><img src={`/api/artifacts/${artifactId}`} alt="页面交互分析截图" onLoad={event=>setNatural({width:event.currentTarget.naturalWidth,height:event.currentTarget.naturalHeight})}/>{steps.map((step,index)=>{const box=step.boundingBox;if(!box||box.width<=0||box.height<=0)return null;return <span key={`${step.selector}-${index}`} className={`trace-marker ${focusIndex===index?'active':''}`} aria-label={`序号 ${index+1}：${step.accessibleName||step.name||step.selector}`} style={{left:`${box.x/natural.width*100}%`,top:`${box.y/natural.height*100}%`,width:`${Math.max(box.width/natural.width*100,1.7)}%`,height:`${Math.max(box.height/natural.height*100,.8)}%`}}><b>{step.index??index+1}</b></span>})}</div>
}

function FindingPage(){
  const {findingId=''}=useParams(),nav=useNavigate(),client=useQueryClient(),{message}=AntApp.useApp();
  const [reviewComment,setReviewComment]=useState('');
  const detail=useQuery({queryKey:['finding',findingId],queryFn:()=>request<{finding:Finding;evidences:Evidence[];reviews:FindingReview[]}>(`/findings/${findingId}`)});
  const fix=useMutation({mutationFn:(body:{language?:string;aiEvidenceId?:string;candidateText?:string;decorative?:boolean})=>request<Patch>(`/findings/${findingId}/fix-patches`,{method:'POST',body:JSON.stringify(body)}),onSuccess:p=>nav(`/fix-patches/${p.id}`),onError:e=>message.error((e as Error).message)});
  const f0=detail.data?.finding;
  const traceType=f0?.ruleId==='R009'?'READING_ORDER':'KEYBOARD_FOCUS';
  const traceQuery=useQuery({queryKey:['finding-trace',f0?.scanTaskId,f0?.pagePath,traceType],enabled:!!f0&&['R007','R009'].includes(f0.ruleId),queryFn:()=>request<AnalysisTrace[]>(`/scans/${f0!.scanTaskId}/analysis-traces?type=${traceType}&pagePath=${encodeURIComponent(f0!.pagePath)}`)});
  const review=useMutation({mutationFn:(action:'CONFIRM_RISK'|'DISMISS_FALSE_POSITIVE'|'NEEDS_FOLLOW_UP')=>request<{finding:Finding;review:FindingReview;scan:Scan}>(`/findings/${findingId}/reviews`,{method:'POST',headers:{'Idempotency-Key':idempotencyKey()},body:JSON.stringify({action,comment:reviewComment})}),onSuccess:r=>{setReviewComment('');client.invalidateQueries({queryKey:['finding',findingId]});client.invalidateQueries({queryKey:['scan',r.scan.id]});client.invalidateQueries({queryKey:['findings',r.scan.id]});message.success('复核结论已记录')},onError:e=>message.error((e as Error).message)});
  if(detail.isError)return <QueryProblem error={detail.error} retry={()=>detail.refetch()}/>;
  if(!detail.data)return <Spin/>;
  const {finding:f,evidences,reviews=[]}=detail.data;
  const data=evidences.find(e=>e.kind!=='AI')||evidences[0];
  const aiEvidence=evidences.find(e=>e.kind==='AI');
  let payload:Record<string,unknown>={};let aiPayload:Record<string,unknown>={};
  try{payload=JSON.parse(data?.payload||'{}')}catch{}
  try{aiPayload=JSON.parse(aiEvidence?.payload||'{}')}catch{}
  const box=payload.boundingBox as {x:number;y:number;width:number;height:number}|undefined;
  const reasons=Array.isArray(aiPayload.reasons)?aiPayload.reasons as string[]:[];
  const confidence=String(aiPayload.confidenceBand||'manual_review').toLowerCase();
  const canGenerateAi=!!aiEvidence&&['R001','R003'].includes(f.ruleId)&&['high','medium'].includes(confidence);
  const interactionTrace=traceQuery.data?.[0];
  const statusText=f.status==='CONFIRMED'?'已记录风险':f.status==='DISMISSED'?'已驳回':f.status==='NEEDS_REVIEW'?'自动候选':'确定性问题';
  return <><PageHead title={f.title} subtitle={`${f.ruleId} · ${f.pagePath}`} back={`/scans/${f.scanTaskId}/findings`}/>{interactionTrace&&<InteractionEvidence trace={interactionTrace} focusSelector={f.selector}/>}<Row gutter={[18,18]}><Col xs={24} lg={14}><Card title="页面证据">{data?.artifactId&&!interactionTrace&&<EvidencePreview artifactId={data.artifactId} box={box}/>}<Descriptions column={1} bordered size="small" items={[{key:'selector',label:'元素定位',children:<Typography.Text code>{f.selector}</Typography.Text>},{key:'summary',label:'证据摘要',children:data?.summary},{key:'kind',label:'证据类型',children:data?.kind},{key:'status',label:'结论状态',children:<Tag>{statusText}</Tag>}]}/></Card></Col><Col xs={24} lg={10}><Card><Space><Tag color={severity[f.severity].color}>{severity[f.severity].text}风险</Tag>{f.source==='HEURISTIC'&&<Tag color="gold">启发式候选</Tag>}</Space><Typography.Title level={3}>{f.title}</Typography.Title><Typography.Paragraph>{f.description}</Typography.Paragraph><Divider/><Typography.Title level={5}>检测详情</Typography.Title><pre className="json-view">{JSON.stringify(payload,null,2)}</pre>{aiEvidence&&<><Divider/><div className="ai-analysis"><Flex justify="space-between" align="center"><Typography.Title level={5}>AI 辅助解释</Typography.Title><Tag color="purple">{confidence}</Tag></Flex><Typography.Paragraph>{aiEvidence.summary}</Typography.Paragraph>{reasons.length>0&&<ul>{reasons.map((reason,index)=><li key={index}>{reason}</li>)}</ul>}<Typography.Text strong>可选审阅建议：</Typography.Text><Typography.Paragraph>{String(aiPayload.recommendedAction||'可按需审阅。')}</Typography.Paragraph>{String(aiPayload.candidateText||'').trim()&&<Alert type="info" showIcon title="候选文本，尚未应用" description={String(aiPayload.candidateText)}/>}<Typography.Text type="secondary">AI 不改变 R009 是否生成，也不能确认风险或生成补丁。</Typography.Text>{canGenerateAi&&<AiCandidateEditor ruleId={f.ruleId} initialText={String(aiPayload.candidateText||'')} loading={fix.isPending} onGenerate={(candidateText,decorative)=>fix.mutate({aiEvidenceId:aiEvidence.id,candidateText,decorative})}/>}</div></>}{f.ruleId==='R009'?<><Divider/><Typography.Title level={5}>可选审阅</Typography.Title><Input.TextArea value={reviewComment} maxLength={1000} showCount rows={3} onChange={event=>setReviewComment(event.target.value)} placeholder="驳回候选必须填写 5–1000 字理由；其他操作说明可选"/><Flex gap={8} wrap style={{marginTop:12}}><Button type="primary" loading={review.isPending} onClick={()=>review.mutate('CONFIRM_RISK')}>记录为风险</Button><Button danger disabled={reviewComment.trim().length<5} loading={review.isPending} onClick={()=>review.mutate('DISMISS_FALSE_POSITIVE')}>驳回候选</Button><Button loading={review.isPending} onClick={()=>review.mutate('NEEDS_FOLLOW_UP')}>保持候选</Button></Flex><List className="review-history" size="small" header="审阅历史" dataSource={reviews} locale={{emptyText:'暂无审阅记录'}} renderItem={item=><List.Item><div><Space><Tag>{item.action}</Tag>{item.reused&&<Tag color="cyan">复用项目结论</Tag>}</Space><div>{item.comment||'未填写说明'}</div><Typography.Text type="secondary">{new Date(item.createdAt).toLocaleString()}</Typography.Text></div></List.Item>}/></>:f.fixMode==='TEMPLATE_PATCH'?<Button block size="large" type="primary" icon={<CodeOutlined/>} loading={fix.isPending} onClick={()=>fix.mutate({language:'zh-CN'})}>生成模板修复</Button>:!canGenerateAi&&<Alert type="info" showIcon title="该问题未自动确认" description={aiEvidence&&!['high','medium'].includes(confidence)?'AI 置信度不足，不生成可应用补丁。':'当前类型只提供修复建议，项目成员可按需审阅。'}/>}</Card></Col></Row></>
}

export function AiCandidateEditor({ruleId,initialText,loading,onGenerate}:{ruleId:string;initialText:string;loading?:boolean;onGenerate:(text:string,decorative:boolean)=>void}){
  const [text,setText]=useState(initialText),[decorative,setDecorative]=useState(false);
  useEffect(()=>{setText(initialText);setDecorative(false)},[initialText,ruleId]);
  const limit=ruleId==='R001'?300:120;
  const valid=(decorative&&ruleId==='R001')||(!decorative&&text.trim().length>0&&text.trim().length<=limit);
  return <div className="ai-fix-editor"><Divider/><Typography.Title level={5}>生成候选修复</Typography.Title>{ruleId==='R001'&&<Checkbox checked={decorative} onChange={event=>setDecorative(event.target.checked)}>这是装饰性图片，使用空 alt</Checkbox>}<Input.TextArea aria-label={ruleId==='R001'?'图片替代文本':'按钮或链接可访问名称'} value={text} disabled={decorative} maxLength={limit} showCount rows={3} onChange={event=>setText(event.target.value)} placeholder={ruleId==='R001'?'描述图片传达的内容':'描述该控件执行的操作'}/><Alert type="warning" showIcon title="候选文本必须由你确认" description="系统只生成最小属性补丁，不会将 AI 建议视为无障碍合规结论。"/><Button aria-label="生成并审阅代码 Diff" block type="primary" size="large" icon={<CodeOutlined/>} disabled={!valid} loading={loading} onClick={()=>onGenerate(decorative?'':text,decorative)}>生成并审阅代码 Diff</Button></div>
}

function EvidencePreview({artifactId,box}:{artifactId:string;box?:{x:number;y:number;width:number;height:number}}){
  const [natural,setNatural]=useState({width:1440,height:900});
  const valid=box&&box.width>0&&box.height>0&&natural.width>0&&natural.height>0;
  return <div className="evidence-wrap"><img className="evidence-image" src={`/api/artifacts/${artifactId}`} alt="问题页面截图" onLoad={event=>setNatural({width:event.currentTarget.naturalWidth,height:event.currentTarget.naturalHeight})}/>{valid&&<span className="evidence-marker" aria-label="问题元素位置" style={{left:`${box.x/natural.width*100}%`,top:`${box.y/natural.height*100}%`,width:`${box.width/natural.width*100}%`,height:`${box.height/natural.height*100}%`}}/>}</div>
}

function PatchPage(){
  const {patchId=''}=useParams(),nav=useNavigate(),client=useQueryClient(),{message,modal}=AntApp.useApp();const patch=useQuery({queryKey:['patch',patchId],queryFn:()=>request<Patch>(`/fix-patches/${patchId}`)});
  const apply=useMutation({mutationFn:()=>request<{patch:Patch;rescan?:Scan}>(`/fix-patches/${patchId}/apply`,{method:'POST',headers:{'Idempotency-Key':idempotencyKey()},body:JSON.stringify({reviewAccepted:patch.data?.patchType==='AI_CANDIDATE'})}),onSuccess:r=>{client.invalidateQueries({queryKey:['patch',patchId]});message.success('已创建不可变修复版本');r.rescan?nav(`/scans/${r.rescan.id}/progress`):nav('/')},onError:e=>message.error((e as Error).message)});
  const reject=useMutation({mutationFn:()=>request<Patch>(`/fix-patches/${patchId}/reject`,{method:'POST',headers:{'Idempotency-Key':idempotencyKey()}}),onSuccess:()=>{message.info('已拒绝补丁');nav(-1)},onError:e=>message.error((e as Error).message)});
  if(patch.isError)return <QueryProblem error={patch.error} retry={()=>patch.refetch()}/>;
  if(!patch.data)return <Spin/>;
  const p=patch.data,isAi=p.patchType==='AI_CANDIDATE';
  const confirm=()=>isAi?modal.confirm({title:'确认采用 AI 候选文本',content:p.decorative?'你确认该图片仅用于装饰，并同意写入空 alt。':`你确认采用“${p.candidateText}”。系统只验证基础规则是否消失，语义正确性由你负责。`,okText:'确认并应用',cancelText:'返回检查',onOk:()=>apply.mutate()}):apply.mutate();
  return <><PageHead title="审阅修复补丁" subtitle={`${p.targetFile} · ${patchStatusName[p.status]||p.status}`} back={`/findings/${p.findingId}`}/><Alert type="warning" showIcon title="应用后会生成新版本，并使用原页面、规则和键盘配置自动复测；不会再次调用 AI，原始版本不会被覆盖。"/>{isAi&&<Card className="patch-meta" title="AI 候选来源"><Descriptions column={1} bordered size="small" items={[{key:'type',label:'补丁类型',children:'AI 候选修复'},{key:'candidate',label:'最终采用内容',children:p.decorative?'装饰性图片（alt=""）':p.candidateText},{key:'model',label:'来源模型',children:`${p.sourceProvider||'-'} / ${p.sourceModel||'-'}`},{key:'review',label:'人工确认',children:p.reviewedAt?new Date(p.reviewedAt).toLocaleString():'等待确认'}]}/></Card>}<Card className="diff-card" title="统一代码差异"><pre>{p.diffText}</pre></Card><Alert type="info" showIcon title={verificationText(p.verification)}/><Flex justify="end" gap={10}><Button danger disabled={p.status!=='PENDING'} loading={reject.isPending} onClick={()=>reject.mutate()}>拒绝补丁</Button><Button type="primary" size="large" disabled={p.status!=='PENDING'} loading={apply.isPending} onClick={confirm}>{isAi?'确认候选文本并复测':'确认应用并复测'}</Button></Flex></>
}

function VersionsPage(){
  const {projectId=''}=useParams(),nav=useNavigate(),{message}=AntApp.useApp();const query=useQueryClient();
  const versions=useQuery({queryKey:['versions',projectId],queryFn:()=>request<Version[]>(`/projects/${projectId}/versions`)});
  const project=useQuery({queryKey:['project',projectId],queryFn:()=>request<Project>(`/projects/${projectId}`)});
  const rollback=useMutation({mutationFn:(id:string)=>request<Version>(`/versions/${id}/rollback`,{method:'POST',headers:{'Idempotency-Key':idempotencyKey()}}),onSuccess:()=>{query.invalidateQueries({queryKey:['project',projectId]});message.success('当前展示版本已切换')}});
  const rescan=useMutation({mutationFn:(id:string)=>request<Scan>(`/versions/${id}/rescan`,{method:'POST',body:JSON.stringify({pages:[],enableKeyboardTest:true,rules:[]})}),onSuccess:s=>nav(`/scans/${s.id}/progress`)});
  return <><PageHead title="版本管理" subtitle="所有版本保持只读；回滚只改变当前展示指针。" back={`/projects/${projectId}/preview`}/><Card><Steps direction="vertical" items={(versions.data||[]).map(v=>({title:<Space>V{v.versionNo}<Tag color={project.data?.currentVersionId===v.id?'green':'default'}>{project.data?.currentVersionId===v.id?'当前版本':versionSourceName[v.sourceType]||v.sourceType}</Tag></Space>,description:<Space><span>{new Date(v.createdAt).toLocaleString()}</span>{/\.html?$/i.test(v.entryPage)&&<Button size="small" onClick={()=>rescan.mutate(v.id)}>重新扫描</Button>}{v.sourceType.startsWith("REPAIR_")&&<Link to={`/projects/${projectId}/collaboration`}>前往问题修复工作台</Link>}{!v.sourceType.startsWith("REPAIR_")&&project.data?.currentVersionId!==v.id&&<Button size="small" onClick={()=>rollback.mutate(v.id)}>回滚到此版本</Button>}</Space>}))}/></Card></>
}

export default App
