import { useEffect, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useLocation, useNavigate, useParams } from "react-router-dom";
import { Alert, App as AntApp, Button, Card, Checkbox, Descriptions, Flex, Form, Input, List, Progress, Select, Space, Spin, Steps, Tag, Typography } from "antd";
import { ArrowRightOutlined, CheckCircleOutlined, SafetyCertificateOutlined, ThunderboltOutlined } from "@ant-design/icons";
import { idempotencyKey, request } from "./api";
import { SuggestedValues } from "./SuggestedValues";
import type { OnlineTarget, Project, TestRun, TestSuite } from "./types";
import {SmartTaskWorkspace} from "./SmartInsights";
import {QueryProblem} from "./workflowShared";
import {VisualBanner} from './VisualBanner';

type AgentStep={stepNo:number;action:string;reason:string;argument:Record<string,unknown>;result?:Record<string,unknown>;status:string;runId?:string;createdAt?:string;finishedAt?:string};
type AgentTask={id:string;projectId:string;targetId:string;suiteVersionId:string;goal:string;status:string;planHash:string;
  plan:{modules:string[];includeFlow:boolean;flowSteps:Array<{action:string;selector:string;url:string;value?:string}>;pages:string[];
    performance:Record<string,unknown>;readOnlyApiCases:Array<{name:string;method:string;path:string;kind:string}>;excludedApiCaseCount:number;
    modelInput:Record<string,unknown>;mode?:"QWEN"|"RULE";baselineTaskId?:string;baseline?:BaselineTask;
    reason:string;model:string;targetName:string;targetUrl:string;flowUnavailableReason?:string};summary:Record<string,unknown>;errorMessage?:string;
  currentRunId?:string;cancelRequested:boolean;steps:AgentStep[];createdAt?:string;startedAt?:string;finishedAt?:string};
type BaselineModule={status:string;runId?:string;reason?:string};
type BaselineTask={id:string;goal:string;status:string;finishedAt:string;modules:Record<string,BaselineModule>};
const moduleName:Record<string,string>={FRONTEND:"前端质量",SECURITY:"后端安全",PERFORMANCE:"性能负载"};
const actionName:Record<string,string>={RUN_MODULE:"执行检测",READ_RUN:"读取结果",SEARCH_CASES:"查询知识案例",FINISH:"结束任务",REJECTED_ACTION:"决策中断"};
const statusName:Record<string,string>={AWAITING_START:"等待确认",RUNNING:"执行中",COMPLETED:"已完成",PARTIAL:"部分完成",FAILED:"失败",CANCELLED:"已取消",INTERRUPTED:"已中断"};

export function AgentProjectPage(){
  const nav=useNavigate();
  const projects=useQuery({queryKey:["projects"],queryFn:()=>request<Project[]>("/projects")});
  return <div className="page-content agent-page">
    <Link className="agent-back" to="/">← 返回首页</Link>
    <VisualBanner theme="agent" className="agent-landing">
      <div><span className="agent-kicker">AGENT / INTELLIGENT TESTING</span><Typography.Title>把检测目标交给智能体<br/><em>把执行范围留在你手中。</em></Typography.Title><Typography.Paragraph>先选择项目。接下来填写目标与套件，查看计划，再确认是否执行。</Typography.Paragraph></div>
      <div className="agent-landing-symbol" aria-hidden="true"><span><ThunderboltOutlined/></span><i>01 / 03</i></div>
    </VisualBanner>
    <div className="agent-select-layout">
      <Card className="agent-select-card" bordered={false}>
        <span className="form-kicker">STEP 01 · 选择工作空间</span>
        <Typography.Title level={3}>从哪个项目开始？</Typography.Title>
        <Typography.Paragraph type="secondary">选择一个已登记项目，智能体会读取其中的在线目标和测试套件。</Typography.Paragraph>
        {projects.isLoading?<Spin/>:projects.isError?<QueryProblem error={projects.error} retry={()=>projects.refetch()}/>:projects.data?.length?<Select aria-label="选择检测项目" showSearch optionFilterProp="label"
          placeholder="搜索或选择项目" size="large" style={{width:"100%"}}
          options={projects.data.map(project=>({value:project.id,label:project.name}))}
          onChange={projectId=>nav(`/projects/${projectId}/assistant`)}/>
          :<Alert type="info" showIcon title="还没有项目" description={<Link to="/">返回首页创建项目</Link>}/>}
      </Card>
      <aside className="agent-guide"><span className="form-kicker">接下来</span><div><b>01</b><span>选择项目与范围</span><CheckCircleOutlined/></div><div><b>02</b><span>查看智能体计划</span><ArrowRightOutlined/></div><div><b>03</b><span>确认执行并追踪证据</span><ArrowRightOutlined/></div></aside>
    </div>
  </div>;
}

export function AgentStartPage(){
  const guideLocation=useLocation();
  const guided=guideLocation.state as {guideContext?:{targetId?:string;suiteVersionId?:string;baselineTaskId?:string};guideGoal?:string}|null;
  const {projectId=""}=useParams(),nav=useNavigate(),{message}=AntApp.useApp();
  const [draft,setDraft]=useState<AgentTask>();
  const [form]=Form.useForm();
  const selectedMode=Form.useWatch("mode",form) as "QWEN"|"RULE"|undefined;
  const selectedTarget=Form.useWatch("targetId",form) as string|undefined;
  const selectedSuite=Form.useWatch("suiteVersionId",form) as string|undefined;
  const project=useQuery({queryKey:["project",projectId],queryFn:()=>request<Project>(`/projects/${projectId}`)});
  const targets=useQuery({queryKey:["targets",projectId],queryFn:()=>request<OnlineTarget[]>(`/projects/${projectId}/targets`)});
  const suites=useQuery({queryKey:["test-suites",projectId],queryFn:()=>request<TestSuite[]>(`/projects/${projectId}/test-suites`)});
  const missingTargets=targets.isSuccess&&targets.data.length===0;
  const missingSuites=suites.isSuccess&&suites.data.length===0;
  const configurationReady=!!targets.data?.length&&!!suites.data?.length;
  useEffect(()=>{
    if(targets.data?.length===1&&!form.getFieldValue("targetId"))form.setFieldValue("targetId",targets.data[0].id);
    if(suites.data?.length===1&&!form.getFieldValue("suiteVersionId"))form.setFieldValue("suiteVersionId",suites.data[0].currentVersionId);
  },[targets.data,suites.data,form]);
  const baselines=useQuery({queryKey:["agent-baselines",projectId,selectedTarget,selectedSuite],enabled:!!selectedTarget&&!!selectedSuite,
    queryFn:()=>request<BaselineTask[]>(`/projects/${projectId}/agent-tasks/baselines?targetId=${encodeURIComponent(selectedTarget!)}&suiteVersionId=${encodeURIComponent(selectedSuite!)}`)});
  const create=useMutation({mutationFn:(values:{targetId:string;suiteVersionId:string;goal:string;mode:"QWEN"|"RULE";baselineTaskId?:string;aiConsent?:boolean})=>
    request<AgentTask>(`/projects/${projectId}/agent-tasks`,{method:"POST",body:JSON.stringify(values)}),
    onSuccess:setDraft,onError:e=>message.error((e as Error).message)});
  const start=useMutation({mutationFn:()=>request<AgentTask>(`/agent-tasks/${draft!.id}/start`,{
    method:"POST",headers:{"Idempotency-Key":idempotencyKey()},body:JSON.stringify({planHash:draft!.planHash})}),
    onSuccess:task=>{window.dispatchEvent(new CustomEvent("guide-business-result",{detail:{projectId:task.projectId,taskId:task.id,targetId:task.targetId,suiteVersionId:task.suiteVersionId}}));nav("/agent-tasks/"+task.id)},onError:e=>message.error((e as Error).message)});
  return <div className="page-content agent-page">
    <Link className="agent-back" to={`/projects/${projectId}/quality`}>← 返回测试中心</Link>
    <div className="agent-page-heading"><div><span className="agent-kicker">GUIDED / 智能检查</span><Typography.Title level={2}>{draft?"确认本次检查方案":"告诉我们这次想检查什么"}</Typography.Title><Typography.Paragraph>{project.data?`当前项目：${project.data.name}。`:""}只需选择网站、方案和目标，系统会先生成一份可确认的计划。</Typography.Paragraph></div><span className="agent-heading-icon"><ThunderboltOutlined/></span></div>
    <div className="agent-progress"><div className="active"><b>01</b><span>设置目标</span></div><div className={draft?"active":""}><b>02</b><span>确认计划</span></div><div><b>03</b><span>追踪结果</span></div></div>
    {!draft?<div className="agent-start-grid"><Card data-guide="agent-form" className="agent-form-card" title="三项信息即可生成计划">
      {(targets.isError||suites.isError)&&<Alert type="error" showIcon title="无法加载当前项目的在线配置" description="请检查登录状态和项目权限，然后重试。" action={<Button size="small" onClick={()=>{targets.refetch();suites.refetch()}}>重试加载</Button>} style={{marginBottom:16}}/>}
      {(missingTargets||missingSuites)&&<Alert type="info" showIcon title="当前项目还不能创建智能检测计划" description={<>
        请先在测试中心{missingTargets?"登记在线目标":""}{missingTargets&&missingSuites?"并":""}{missingSuites?"创建测试套件":""}。
        下拉框只能选择当前项目已登记的配置，不能直接输入网址。<br/>
        <Link to={`/projects/${projectId}/quality`}>打开测试中心</Link>，或 <Link to="/agent">切换项目</Link>。
      </>} style={{marginBottom:16}}/>}
      <div className="agent-form-intro"><span>1</span><p><b>先填写基本信息</b><small>所有检查都要在下一页确认后才会开始。</small></p></div>
      <Form form={form} layout="vertical" initialValues={{mode:"RULE",...guided?.guideContext,goal:guided?.guideGoal}} onFinish={values=>create.mutate(values)}>
        <Form.Item name="targetId" label="要检查的网站" extra="这里只显示当前项目中已登记并限制范围的网站。" rules={[{required:true,message:"请选择目标"}]}><Select size="large" showSearch optionFilterProp="label" loading={targets.isLoading} placeholder={missingTargets?"请先添加网站":"选择一个网站"} notFoundContent={missingTargets?"本项目暂无在线目标":"没有匹配的在线目标"} options={targets.data?.map(t=>({value:t.id,label:t.name}))||[]}/></Form.Item>
        <Form.Item name="suiteVersionId" label="检查方案" extra={<Link aria-label="不会填参数？打开参数配置助手" to={`/projects/${projectId}/quality?suiteAssistant=1`}>没有合适方案？使用推荐配置</Link>} rules={[{required:true,message:"请选择套件"}]}><Select size="large" showSearch optionFilterProp="label" loading={suites.isLoading} placeholder={missingSuites?"请先创建检查方案":"选择检查方案"} notFoundContent={missingSuites?"本项目暂无测试套件":"没有匹配的测试套件"} options={suites.data?.map(s=>({value:s.currentVersionId,label:`${s.name} · V${s.currentVersion}`}))||[]}/></Form.Item>
        <Form.Item name="goal" label="你希望这次检查什么？" extra="可以直接选择下方推荐目标，不需要使用专业术语。" rules={[{required:true,max:1000,message:"请填写检测目标（最多 1000 字）"}]}>
          <Input.TextArea rows={3} placeholder="例如：检查页面错误和性能，并保留自动候选证据"/>
        </Form.Item>
        <SuggestedValues items={[
          {label:"页面质量",value:"检查页面可用性和加载指标，保留实际证据"},
          {label:"只读接口权限",value:"检查已登记的只读接口权限，列出自动检测候选"},
          {label:"完整质量检查",value:"依次检查页面质量、只读接口权限和性能负载，说明未完成项"},
        ]} onSelect={goal=>form.setFieldValue("goal",goal)}
          hint="推荐文字只填写目标；实际模块和负载仍以确认页为准。"/>
        <details className="agent-advanced-options">
          <summary>高级规划选项 <span>AI 模式与历史结果对比</span></summary>
          <Form.Item name="mode" label="规划方式" rules={[{required:true,message:"请选择模式"}]}>
            <Select options={[{value:"RULE",label:"标准模式（推荐）· 不发送模型请求"},{value:"QWEN",label:"AI 自适应模式 · 根据检测证据调整顺序"}]}/>
          </Form.Item>
          <Form.Item name="baselineTaskId" label="与哪次历史检查对比（可选）"><Select allowClear showSearch optionFilterProp="label" loading={baselines.isLoading} disabled={!selectedTarget||!selectedSuite}
            placeholder={!selectedTarget||!selectedSuite?"先选择网站和检查方案":baselines.data?.length?"选择使用同一配置的已结束任务":"暂无可用的历史检查"}
            options={baselines.data?.map(item=>({value:item.id,label:`${item.goal} · ${item.finishedAt} · ${item.status}`}))||[]}/></Form.Item>
          {baselines.isError&&<Alert type="warning" showIcon title="历史检查暂时无法加载" description="可以留空继续创建计划。" style={{marginBottom:16}}/>}
        {selectedMode==="QWEN"?<><Alert type="info" showIcon title="模型数据告知" description="生成计划和执行决策时会向已配置的百炼 Qwen 发送脱敏的目标、套件摘要、实测指标、基线差值及不可比原因。请勿在检测目标中填写 Token 或 Cookie；凭据不会发送给模型。" style={{marginBottom:16}}/>
          <Form.Item name="aiConsent" valuePropName="checked" rules={[{validator:(_,value)=>value?Promise.resolve():Promise.reject(new Error("请先确认模型数据告知"))}]}>
            <Checkbox>我同意发送必要的脱敏摘要给 Qwen</Checkbox>
          </Form.Item></>:<Alert type="success" showIcon title="规则模式不会请求模型，也不需要发送同意" description="这是默认的标准模式。系统会根据目标关键词选择页面、安全或性能检查；每项只执行一次。" style={{marginBottom:16}}/>}
        </details>
        <Button aria-label="生成计划" size="large" block type="primary" htmlType="submit" loading={create.isPending} disabled={!configurationReady||targets.isError||suites.isError}>生成检查计划</Button>
      </Form>
    </Card><aside className="agent-form-aside"><div className="aside-icon"><SafetyCertificateOutlined/></div><span className="form-kicker">执行边界</span><h3>先知情，再运行</h3><p>智能体只会在你确认的模块、交互步骤和负载参数范围内执行。</p><div><span>01</span> 生成计划前同意发送必要摘要</div><div><span>02</span> 核对每项模块与测试参数</div><div><span>03</span> 确认后逐步执行并保留证据</div></aside></div>:<Space direction="vertical" size="middle" style={{width:"100%"}}>
      <Card className="agent-plan-card" title="请确认本次任务的执行范围">
        <div className="plan-plain-summary">
          <section><span className="plan-summary-icon"><CheckCircleOutlined/></span><div><small>这次会做</small><h3>{draft.plan.modules.map(m=>moduleName[m]||m).join("、")}</h3><p>系统会按顺序执行，每一步都有进度和证据。</p></div></section>
          <section><span className="plan-summary-icon safe"><SafetyCertificateOutlined/></span><div><small>安全边界</small><h3>只在已确认范围内</h3><p>{draft.plan.includeFlow?`包含 ${draft.plan.flowSteps.length} 个已登记交互步骤。`:"不执行点击、填写或上传操作。"}</p></div></section>
        </div>
        <details className="plan-technical-details">
          <summary>查看完整执行明细 <span>页面、接口、负载与模型信息</span></summary>
        <Descriptions column={1} bordered size="small" items={[
          {key:"goal",label:"检测目标",children:draft.goal},
          {key:"target",label:"目标",children:`${draft.plan.targetName} · ${draft.plan.targetUrl}`},
          {key:"modules",label:"检测模块",children:draft.plan.modules.map(m=>moduleName[m]||m).join(" → ")},
          {key:"model",label:"决策来源",children:draft.plan.mode==="RULE"?"规则模式（无模型请求）":`Qwen · ${draft.plan.model}`},
          {key:"baseline",label:"历史基线",children:draft.plan.baselineTaskId?`任务 ${draft.plan.baselineTaskId}`:"不使用基线"},
          {key:"reason",label:"计划理由",children:draft.plan.reason},
          {key:"pages",label:"页面",children:draft.plan.modules.includes("FRONTEND")?draft.plan.pages.join("、")||"目标首页":"不执行"},
          {key:"security",label:"安全接口",children:draft.plan.modules.includes("SECURITY")?`只读用例 ${draft.plan.readOnlyApiCases.length} 项；排除写入或上传用例 ${draft.plan.excludedApiCaseCount} 项；不执行主动漏洞扫描`:"不执行"},
          {key:"flow",label:"交互步骤",children:draft.plan.includeFlow?`${draft.plan.flowSteps.length} 步，将执行确认的点击或填写动作`:(draft.plan.flowUnavailableReason||"不执行")},
          {key:"load",label:"性能负载",children:draft.plan.modules.includes("PERFORMANCE")?performanceText(draft.plan.performance):"不执行"},
        ]}/>
        {draft.plan.includeFlow&&<List size="small" header="将执行的交互步骤" dataSource={draft.plan.flowSteps} renderItem={(step,index)=><List.Item>{index+1}. {step.action} {step.selector||step.url} {step.value||""}</List.Item>}/>}
        {draft.plan.readOnlyApiCases.length>0&&<List size="small" header="只读接口用例" dataSource={draft.plan.readOnlyApiCases} renderItem={item=><List.Item>{item.method} {item.path} · {item.name}</List.Item>}/>}
        {draft.plan.baselineTaskId&&draft.plan.baseline&&<Card size="small" title="基线覆盖情况" style={{marginTop:16}}>
          {draft.plan.modules.map(module=><Typography.Paragraph key={module}>{moduleName[module]}：{draft.plan.baseline!.modules[module]?.status==="COMPLETED"?<Link to={`/test-runs/${draft.plan.baseline!.modules[module].runId}`}>已完整执行 · 查看运行</Link>:draft.plan.baseline!.modules[module]?.reason||"缺失"}</Typography.Paragraph>)}
        </Card>}
        {draft.plan.mode!=="RULE"&&<Card size="small" title="生成计划时已发送给模型的摘要" style={{marginTop:16}}>
          <Typography.Paragraph>检测目标：{String(draft.plan.modelInput.goal||"")}；目标名称：{String(draft.plan.modelInput.targetName||"")}</Typography.Paragraph>
          <Typography.Paragraph>页面数：{String(draft.plan.modelInput.pageCount??0)}；交互动作：{Array.isArray(draft.plan.modelInput.flowActions)?draft.plan.modelInput.flowActions.join("、")||"无":"无"}；只读接口数：{String(draft.plan.modelInput.readOnlyApiCaseCount??0)}；性能配置：{draft.plan.modelInput.performanceConfigured?"有":"无"}</Typography.Paragraph>
          <Typography.Text type="secondary">后续决策仅发送目标、批准模块、已完成模块及最近三步的脱敏结果摘要。</Typography.Text>
        </Card>}
        </details>
        <Alert type="warning" showIcon title="确认后，智能体仅能执行这里列出的模块和步骤；安全候选不会被自动确认为漏洞。" style={{marginTop:16,marginBottom:16}}/>
        <Space><Button type="primary" loading={start.isPending} onClick={()=>start.mutate()}>确认并开始</Button><Button onClick={()=>setDraft(undefined)}>重新生成</Button></Space>
      </Card>
    </Space>}
  </div>;
}

export function AgentTaskPage(){
  const {taskId=""}=useParams(),{message}=AntApp.useApp(),cache=useQueryClient();
  const [lastLiveUpdate,setLastLiveUpdate]=useState<string>();
  const [streamState,setStreamState]=useState<"connecting"|"live"|"fallback">("connecting");
  const task=useQuery({queryKey:["agent-task",taskId],queryFn:()=>request<AgentTask>(`/agent-tasks/${taskId}`),
    refetchInterval:q=>q.state.data?.status==="RUNNING"?1500:false});
  const cancel=useMutation({mutationFn:()=>request<AgentTask>(`/agent-tasks/${taskId}/cancel`,{method:"POST"}),
    onSuccess:()=>task.refetch(),onError:e=>message.error((e as Error).message)});
  const start=useMutation({mutationFn:()=>request<AgentTask>(`/agent-tasks/${taskId}/start`,{
    method:"POST",headers:{"Idempotency-Key":idempotencyKey()},body:JSON.stringify({planHash:task.data!.planHash})}),
    onSuccess:()=>task.refetch(),onError:e=>message.error((e as Error).message)});
  const currentRunId=task.data?.currentRunId;
  const currentRun=useQuery({queryKey:["test-run",currentRunId],queryFn:()=>request<TestRun>(`/test-runs/${currentRunId}`),enabled:!!currentRunId});
  const performanceRunId=task.data?.steps.find(step=>step.action==="RUN_MODULE"&&step.argument.module==="PERFORMANCE"&&step.runId)?.runId;
  const performanceRun=useQuery({queryKey:["test-run",performanceRunId],queryFn:()=>request<TestRun>(`/test-runs/${performanceRunId}`),enabled:!!performanceRunId});
  useEffect(()=>{
    if(task.data?.status!=="RUNNING"||typeof EventSource==="undefined")return;
    setStreamState("connecting");
    const stream=new EventSource(`/api/agent-tasks/${taskId}/events`);
    stream.onopen=()=>setStreamState("live");
    stream.addEventListener("agent-task",event=>{
      try{cache.setQueryData(["agent-task",taskId],JSON.parse((event as MessageEvent<string>).data) as AgentTask);setLastLiveUpdate(new Date().toISOString())}catch{/* 保留轮询兜底 */}
    });
    stream.onerror=()=>setStreamState("fallback");
    return()=>stream.close();
  },[taskId,task.data?.status,cache]);
  useEffect(()=>{
    if(!currentRunId||task.data?.status!=="RUNNING"||typeof EventSource==="undefined")return;
    const stream=new EventSource(`/api/test-runs/${currentRunId}/events`);
    stream.addEventListener("test-run",event=>{
      try{cache.setQueryData(["test-run",currentRunId],JSON.parse((event as MessageEvent<string>).data) as TestRun);setLastLiveUpdate(new Date().toISOString())}catch{/* 任务事件仍会继续更新 */}
    });
    return()=>stream.close();
  },[currentRunId,task.data?.status,cache]);
  if(task.isLoading)return <Spin/>;
  if(task.isError||!task.data)return <Alert type="error" title={(task.error as Error)?.message||"无法读取智能体任务"}/>;
  const value=task.data;
  const summary=value.summary||{};
  const moduleEvidence=asRecord(summary.moduleEvidence);
  const baselineComparisons=asRecord(summary.baselineComparisons);
  const performanceAssessment=performanceRun.data?.modules.find(module=>module.moduleType==="PERFORMANCE")?.performanceAssessment;
  const terminal=["COMPLETED","PARTIAL","FAILED","CANCELLED","INTERRUPTED"].includes(value.status);
  const moduleStates=value.plan.modules.map(module=>{
    const step=[...value.steps].reverse().find(item=>item.action==="RUN_MODULE"&&item.argument.module===module);
    const runModule=step?.runId===currentRunId?currentRun.data?.modules.find(item=>item.moduleType===module):undefined;
    const effective=String(step?.result?.status||runModule?.status||step?.status||"PENDING");
    const stepStatus=effective==="COMPLETED"?"finish":effective==="RUNNING"||runModule?.status==="RUNNING"?"process":step&&["PARTIAL","FAILED","CANCELLED","INTERRUPTED"].includes(effective)?"error":"wait";
    return {module,step,effective,stepStatus:stepStatus as "finish"|"process"|"error"|"wait",summary:runModule?.summary};
  });
  const finishedModules=moduleStates.filter(item=>["finish","error"].includes(item.stepStatus)).length;
  const hasActive=moduleStates.some(item=>item.stepStatus==="process");
  const progressPercent=terminal?100:Math.round(((finishedModules+(hasActive?0.45:0))/Math.max(1,moduleStates.length))*100);
  const activeModule=moduleStates.find(item=>item.stepStatus==="process");
  return <div className="page-content agent-page">
    <Link className="agent-back" to={`/projects/${value.projectId}/quality`}>← 返回测试中心</Link>
    <div className="agent-page-heading"><div><span className="agent-kicker">AGENT / TASK DETAIL</span><Typography.Title level={2}>智能测试任务 <Tag>{statusName[value.status]||value.status}</Tag></Typography.Title><Typography.Paragraph>{value.goal}</Typography.Paragraph></div><span className="agent-heading-icon"><ThunderboltOutlined/></span></div>
    {value.status==="RUNNING"&&<Button danger loading={cancel.isPending} disabled={value.cancelRequested} onClick={()=>cancel.mutate()} style={{marginBottom:16}}>{value.cancelRequested?"正在取消":"取消任务"}</Button>}
    <Card title="本次计划" style={{marginBottom:16}}>
      <Typography.Paragraph>{value.plan.targetName} · {value.plan.targetUrl}</Typography.Paragraph>
      <Typography.Text>{value.plan.modules.map(m=>moduleName[m]||m).join(" → ")}</Typography.Text>
      <Typography.Paragraph>决策来源：{value.plan.mode==="RULE"?"规则模式 · 无模型请求":"Qwen 模式 · 模型理由需引用真实观察"}</Typography.Paragraph>
      {value.plan.baselineTaskId&&<Typography.Paragraph>历史基线：<Link to={`/agent-tasks/${value.plan.baselineTaskId}`}>{value.plan.baselineTaskId}</Link></Typography.Paragraph>}
      <Typography.Paragraph type="secondary" style={{marginTop:8}}>{value.plan.reason}</Typography.Paragraph>
      {value.status==="AWAITING_START"&&<>
        <Typography.Paragraph>{value.plan.modules.includes("SECURITY")?`安全模块仅执行 ${value.plan.readOnlyApiCases.length} 项只读用例，排除 ${value.plan.excludedApiCaseCount} 项写入或上传用例；不执行主动漏洞扫描。`:"本次不执行安全模块。"}</Typography.Paragraph>
        <Typography.Paragraph>交互步骤：{value.plan.includeFlow?`${value.plan.flowSteps.length} 步（含确认的点击或填写动作）`:"不执行"}</Typography.Paragraph>
        {value.plan.includeFlow&&<List size="small" dataSource={value.plan.flowSteps} renderItem={(step,index)=><List.Item>{index+1}. {step.action} {step.selector||step.url} {step.value||""}</List.Item>}/>}
        <Typography.Paragraph>性能负载：{value.plan.modules.includes("PERFORMANCE")?performanceText(value.plan.performance):"不执行"}</Typography.Paragraph>
        {value.plan.mode!=="RULE"&&<Typography.Paragraph>生成计划时发送的摘要：目标名称 {String(value.plan.modelInput.targetName||"")}、页面 {String(value.plan.modelInput.pageCount??0)} 个、只读接口 {String(value.plan.modelInput.readOnlyApiCaseCount??0)} 项。</Typography.Paragraph>}
        <Button type="primary" loading={start.isPending} onClick={()=>start.mutate()}>确认并开始</Button>
      </>}
    </Card>
    {value.status!=="AWAITING_START"&&<Card className="agent-live-progress" title={<Space>{value.status==="RUNNING"&&<span className="agent-live-dot" aria-hidden="true"/>}<span>实时执行进度</span><Tag color={value.status==="RUNNING"?"processing":value.status==="COMPLETED"?"success":value.status==="FAILED"?"error":"warning"}>{statusName[value.status]||value.status}</Tag></Space>} style={{marginBottom:16}}>
      <div aria-live="polite">
        <Progress percent={progressPercent} status={value.status==="FAILED"?"exception":value.status==="COMPLETED"?"success":"active"} format={()=>terminal?"已结束":`${finishedModules}/${moduleStates.length}`}/>
        <Flex justify="space-between" gap={12} wrap className="agent-live-caption">
          <Typography.Text>{activeModule?`正在执行：${moduleName[activeModule.module]||activeModule.module}${activeModule.summary?` · ${activeModule.summary}`:""}`:terminal?String(summary.message||value.errorMessage||"任务已结束"):"智能体正在准备下一项已确认操作"}</Typography.Text>
          <Typography.Text type="secondary">{value.status==="RUNNING"?(streamState==="live"?"实时通道已连接":streamState==="fallback"?"实时通道重连中，页面继续自动刷新":"正在连接实时通道"):value.finishedAt?`结束于 ${new Date(value.finishedAt).toLocaleString("zh-CN")}`:"进度已固定"}{lastLiveUpdate?` · 最近更新 ${new Date(lastLiveUpdate).toLocaleTimeString("zh-CN")}`:""}</Typography.Text>
        </Flex>
        <Steps responsive className="agent-module-progress" items={moduleStates.map(item=>({title:moduleName[item.module]||item.module,status:item.stepStatus,content:item.stepStatus==="process"?"正在采集证据":item.stepStatus==="finish"?"执行完成":item.stepStatus==="error"?`结果：${statusName[item.effective]||item.effective}`:"等待执行"}))}/>
      </div>
    </Card>}
    {terminal&&<SmartTaskWorkspace taskId={taskId} showProgress={false}/>} 
    {value.status!=="AWAITING_START"&&<details className="agent-technical-recap"><summary>查看执行数据与结果边界 <span>模块完成情况、阈值与自动候选说明</span></summary><Card bordered={false}>
      <Typography.Paragraph>{String(summary.message||value.errorMessage||"任务正在进行")}</Typography.Paragraph>
      <Typography.Text>已运行：{Array.isArray(summary.attemptedModules)?summary.attemptedModules.map(m=>moduleName[String(m)]||m).join("、")||"无":"等待中"}</Typography.Text><br/>
      <Typography.Text>完整完成：{Array.isArray(summary.completedModules)?summary.completedModules.map(m=>moduleName[String(m)]||m).join("、")||"无":"等待中"}</Typography.Text><br/>
      <Typography.Text>自动检测候选：{String(summary.reviewCandidateCount??"等待中")}</Typography.Text><br/>
      <Typography.Text>未完整完成：{Array.isArray(summary.incompleteModules)?summary.incompleteModules.map(m=>moduleName[String(m)]||m).join("、")||"无":"等待中"}</Typography.Text><br/>
      <Typography.Text>未执行：{Array.isArray(summary.unexecutedModules)?summary.unexecutedModules.map(m=>moduleName[String(m)]||m).join("、")||"无":"等待中"}</Typography.Text>
      {performanceAssessment?.status==="FAILED"&&<Alert type="error" showIcon title="性能验收未通过" description={performanceAssessment.reason} style={{marginTop:12}}/>}
      {performanceAssessment?.status==="INCONCLUSIVE"&&<Alert type="warning" showIcon title="性能结果不足，不能判定通过" description={performanceAssessment.reason} style={{marginTop:12}}/>}
      <Typography.Paragraph type="secondary" style={{marginTop:12}}>“完整完成”仅表示检测步骤已结束，不代表性能阈值通过；本页复盘摘要保持任务结束时的记录。</Typography.Paragraph>
      <Alert type="info" title="自动检测结果和引擎候选不会被自动确认为漏洞，智能体也不会应用修复；项目成员可按需审阅。" style={{marginTop:12}}/>
    </Card></details>}
    {Object.keys(moduleEvidence).length>0&&<Card title="实测证据与基线差值" style={{marginBottom:16}}>
      <Typography.Paragraph type="secondary">{String(summary.decisionSource||"服务端记录")}{summary.schemaVersion?` · 复盘版本 ${String(summary.schemaVersion)}`:""}。数值在任务结束时固定；后续可选审阅状态请到运行问题页查看。</Typography.Paragraph>
      {value.plan.modules.map(module=>{
        const item=asRecord(moduleEvidence[module]),metrics=asRecord(item.metrics),threshold=asRecord(item.threshold),comparison=asRecord(baselineComparisons[module]);
        return <Card key={module} size="small" title={moduleName[module]||module} style={{marginBottom:12}}>
          {Object.keys(item).length===0?<Typography.Text type="secondary">未取得该模块的完整实测证据</Typography.Text>:<>
            <Typography.Paragraph>执行状态：{statusName[String(item.status)]||String(item.status||"未知")}</Typography.Paragraph>
            <Typography.Paragraph>{metricDescription(module,metrics)}</Typography.Paragraph>
            {threshold.status&&<Alert type={threshold.status==="PASSED"?"success":threshold.status==="FAILED"?"error":"warning"} showIcon
              title={`性能阈值：${String(threshold.status)}`} description={String(threshold.reason||"")} style={{marginBottom:12}}/>}
            {item.reason&&<Typography.Paragraph type="secondary">执行说明：{String(item.reason)}</Typography.Paragraph>}
          </>}
          {value.plan.baselineTaskId&&<Typography.Paragraph>基线对比：{comparison.comparable===true?comparisonDescription(module,comparison):Array.isArray(comparison.reasons)?comparison.reasons.join("；"):"本次模块未执行或指标不可比"}</Typography.Paragraph>}
        </Card>;
      })}
      {Array.isArray(summary.linkedCases)&&summary.linkedCases.length>0&&<Typography.Paragraph>关联案例：{summary.linkedCases.map(item=>String(asRecord(item).title||"")).filter(Boolean).join("、")}</Typography.Paragraph>}
    </Card>}
    <Card title="行动记录">
      <List dataSource={value.steps} locale={{emptyText:"尚未执行任何动作"}} renderItem={step=><List.Item>
        <div style={{width:"100%"}}><Space wrap><Tag>{step.stepNo}</Tag><strong>{actionName[step.action]||step.action}</strong>
          {Boolean(step.argument.module)&&<Tag>{moduleName[String(step.argument.module)]||String(step.argument.module)}</Tag>}
          <Tag>{statusName[step.action==="RUN_MODULE"&&step.result?.status?String(step.result.status):step.status]
            ||(step.action==="RUN_MODULE"&&step.result?.status?String(step.result.status):step.status)}</Tag></Space>
          {step.reason&&<Typography.Paragraph type="secondary" style={{margin:"8px 0"}}>{step.action==="REJECTED_ACTION"?"中断原因：":step.argument.decisionSource==="RULE"?"规则说明：":"模型理由："}{step.reason}</Typography.Paragraph>}
          <Typography.Text type="secondary">来源：{step.argument.decisionSource==="RULE"?"规则步骤（非 AI 自主决策）":"Qwen 模型决策"}{Array.isArray(step.argument.evidenceStepNos)&&step.argument.evidenceStepNos.length?` · 依据步骤 ${step.argument.evidenceStepNos.join("、")}`:""}</Typography.Text><br/>
          {step.runId&&<Link to={`/test-runs/${step.runId}`}>查看检测运行与证据</Link>}
          {Boolean(step.result?.status)&&<Typography.Text style={{marginLeft:12}}>运行状态：{statusName[String(step.result?.status)]||String(step.result?.status)}</Typography.Text>}
          {typeof step.result?.reviewCandidateCount==="number"&&<Typography.Text style={{marginLeft:12}}>自动候选：{step.result.reviewCandidateCount}</Typography.Text>}
          {Array.isArray(step.result?.cases)&&<Typography.Paragraph>关联案例：{(step.result.cases as Array<{title:string}>).map(c=>c.title).join("、")||"暂无"}</Typography.Paragraph>}
        </div>
      </List.Item>}/>
    </Card>
  </div>;
}

function performanceText(config:Record<string,unknown>){
  const vus=Array.isArray(config.vus)?config.vus.join(" → "):"1 → 5 → 10";
  return `${vus} 个并发用户；每阶段 ${String(config.stageDurationSeconds??20)} 秒；每秒最多 ${String(config.ratePerSecond??50)} 次请求；P95 阈值 ${String(config.p95Ms??1000)} 毫秒；错误率阈值 ${Number(config.errorRate??0.01)*100}%${config.path?`；路径 ${String(config.path)}`:""}`;
}

function asRecord(value:unknown):Record<string,unknown>{return value&&typeof value==="object"&&!Array.isArray(value)?value as Record<string,unknown>:{};}
function metricDescription(module:string,metrics:Record<string,unknown>){
  if(module==="FRONTEND"){
    const pages=asRecord(metrics.pages),first=asRecord(Object.values(pages)[0]);
    return Object.keys(pages).length?`页面 ${Object.keys(pages).length} 个；首个页面平均导航 ${numberText(first.navigationMs," ms")}，LCP ${numberText(first.lcpMs," ms")}，CLS ${numberText(first.cls,"")}`:"页面指标缺失，不能判定改善";
  }
  if(module==="SECURITY")return `安全候选 ${numberText(metrics.issueCount," 个")}；已记录风险数仅供历史参考 ${numberText(metrics.humanConfirmedCount," 个")}`;
  return metrics.p95Ms!==undefined?`P50 ${numberText(metrics.p50Ms," ms")}；P95 ${numberText(metrics.p95Ms," ms")}；P99 ${numberText(metrics.p99Ms," ms")}；吞吐 ${numberText(metrics.throughputPerSecond," 次/秒")}；错误率 ${typeof metrics.errorRate==="number"?(metrics.errorRate*100).toFixed(2)+"%":"缺失"}`:"性能指标缺失，不能判定通过或改善";
}
function numberText(value:unknown,unit:string){return typeof value==="number"&&Number.isFinite(value)?`${value.toFixed(2)}${unit}`:"缺失";}
function comparisonDescription(module:string,comparison:Record<string,unknown>){
  const delta=asRecord(comparison.delta);
  if(module==="PERFORMANCE")return `可比；P95 变化 ${numberText(delta.p95Ms," ms")}，吞吐变化 ${numberText(delta.throughputPerSecond," 次/秒")}，错误率变化 ${typeof delta.errorRate==="number"?(delta.errorRate*100).toFixed(2)+" 个百分点":"缺失"}。负值不一律代表改善，请结合指标方向查看。`;
  if(module==="SECURITY")return `可比；安全候选数量变化 ${numberText(delta.issueCount," 个")}。候选变化不等于漏洞已修复。`;
  const pages=asRecord(delta.pages);return `可比；${Object.keys(pages).length} 个页面有指标差值，详情可从关联运行查看。`;
}
