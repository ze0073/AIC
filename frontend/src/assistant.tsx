import { useEffect, useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useNavigate, useParams } from "react-router-dom";
import {
  Alert,
  App as AntApp,
  Button,
  Card,
  Checkbox,
  Descriptions,
  Empty,
  Form,
  Input,
  Segmented,
  Select,
  Space,
  Spin,
  Tag,
  Typography,
} from "antd";
import {
  ArrowRightOutlined,
  CheckCircleOutlined,
  MessageOutlined,
  RobotOutlined,
  SafetyCertificateOutlined,
  SendOutlined,
} from "@ant-design/icons";
import { idempotencyKey, request } from "./api";
import type { OnlineTarget, Project, TestSuite } from "./types";
import {SmartTaskWorkspace} from "./SmartInsights";
import {openGuide} from "./guideApi";

export type AssistantMode = "RULE" | "QWEN";
type AssistantIntent = "PREPARE_AGENT_TASK" | "PROJECT_OVERVIEW" | "TASK_STATUS" | "HELP";

type AssistantMessage = {
  id?: string;
  role?: string;
  content?: string;
  createdAt?: string;
};

type AssistantAction = {
  id: string;
  actionType?: string;
  type?: string;
  status?: string;
  actionHash?: string;
  preview?: unknown;
  payload?: unknown;
  argument?: unknown;
  result?: unknown;
  receipt?: unknown;
  createdAt?: string;
  expiresAt?: string;
  agentTaskId?: string;
  taskId?: string;
};

type AssistantSession = {
  id: string;
  projectId: string;
  mode?: AssistantMode;
  aiConsent?: boolean;
  status?: string;
  messages?: AssistantMessage[];
  actions?: AssistantAction[];
  suggestions?: string[];
  createdAt?: string;
  updatedAt?: string;
};

type BaselineTask = { id:string; goal:string; status:string; finishedAt?:string };
type AssistantRecommendation={schemaVersion:string;ready:boolean;defaultGoal:string;warnings:string[];
  target?:{id:string;name:string;baseUrl:string;reason:string};suite?:{id:string;versionId:string;name:string;version:number;reason:string};
  baseline?:{id:string;goal:string;status:string;finishedAt?:string;reason:string}};

type MessageRequest = {
  content: string;
  intent?: AssistantIntent;
  targetId?: string;
  suiteVersionId?: string;
  baselineTaskId?: string;
  goal?: string;
};

const pendingStatuses=new Set(["PROPOSED","PENDING","PREPARED","AWAITING_CONFIRMATION","PENDING_CONFIRMATION"]);
const moduleNames:Record<string,string>={FRONTEND:"前端质量",SECURITY:"后端安全",PERFORMANCE:"性能负载"};
const roleNames:Record<string,string>={USER:"你",ASSISTANT:"项目助手",SYSTEM:"系统",TOOL:"工具回执"};
const actionStatusMeta:Record<string,{label:string;color:string;type:"info"|"success"|"warning"|"error";title:string;description:string}>={
  PROPOSED:{label:"等待确认",color:"gold",type:"info",title:"这是一份待确认操作",description:"确认后只能执行此卡片列出的范围。聊天消息不会触发确认接口；安全候选不会被自动确认为漏洞。"},
  EXECUTING:{label:"正在启动",color:"blue",type:"info",title:"正在启动真实任务",description:"系统正在校验冻结范围并创建执行回执，请勿重复提交。"},
  SUCCEEDED:{label:"已确认执行",color:"green",type:"success",title:"已确认并生成真实任务回执",description:"本卡片已经执行，不能再次确认。可通过下方链接查看任务状态、实测证据和未执行项。"},
  CANCELLED:{label:"已取消",color:"default",type:"warning",title:"计划已取消",description:"这份计划没有启动检测；如需检测，请重新生成并核对新的计划。"},
  EXPIRED:{label:"已失效",color:"orange",type:"warning",title:"确认时间已过",description:"为避免执行过期范围，这份计划不能再确认。请重新生成计划。"},
  FAILED:{label:"启动失败",color:"red",type:"error",title:"任务未能启动",description:"系统已保留失败回执，没有把未执行内容标为通过。请查看提示后重新生成计划。"},
};
const defaultSuggestions=[
  "帮我检查页面质量和加载速度",
  "检查只读接口权限，并列出自动检测候选",
  "用低负载完成一次完整质量检查",
];

export function buildPrepareRequest(values:{content?:string;goal:string;targetId:string;suiteVersionId:string;baselineTaskId?:string}):MessageRequest{
  return {
    content:values.content?.trim()||`请为“${values.goal.trim()}”生成一份可确认的检测计划。`,
    intent:"PREPARE_AGENT_TASK",
    targetId:values.targetId,
    suiteVersionId:values.suiteVersionId,
    ...(values.baselineTaskId?{baselineTaskId:values.baselineTaskId}:{}),
    goal:values.goal.trim(),
  };
}

export function ProjectAssistantPage(){
  const {projectId=""}=useParams(),nav=useNavigate(),{message}=AntApp.useApp();
  const [mode,setMode]=useState<AssistantMode>("RULE");
  const [consent,setConsent]=useState(false);
  const project=useQuery({queryKey:["project",projectId],queryFn:()=>request<Project>(`/projects/${projectId}`)});
  const sessions=useQuery({queryKey:["assistant-sessions",projectId],queryFn:()=>request<AssistantSession[]>(`/projects/${projectId}/assistant-sessions`),retry:false});
  const create=useMutation({
    mutationFn:()=>request<AssistantSession>(`/projects/${projectId}/assistant-sessions`,{
      method:"POST",body:JSON.stringify({mode,aiConsent:mode==="QWEN"&&consent}),
    }),
    onSuccess:value=>nav(`/assistant-sessions/${value.id}`),
    onError:error=>message.error((error as Error).message),
  });
  return <div className="page-content assistant-page">
    <Button icon={<RobotOutlined/>} onClick={()=>openGuide('CHECK',{projectId})}>打开视界全站向导</Button>
    <Link className="agent-back" to={`/projects/${projectId}/quality`}>← 返回测试中心</Link>
    <section className="assistant-hero">
      <div><span className="agent-kicker">AGENT V0.8.0 / GUIDED WORKSPACE</span><Typography.Title>说清想检查什么，<br/><em>一路看到怎么改。</em></Typography.Title><Typography.Paragraph>系统会推荐网站、方案和历史基线。确认一次后自动完成只读检查，并在同一工作区整理趋势、证据和下一步。</Typography.Paragraph></div>
      <span className="assistant-hero-icon" aria-hidden="true"><MessageOutlined/></span>
    </section>
    <div className="assistant-start-grid">
      <Card title={`开始${project.data?`「${project.data.name}」`:"项目"}智能体检`} className="assistant-create-card">
        <div className="assistant-start-promise"><span><CheckCircleOutlined/></span><div><b>默认使用安全的标准模式</b><p>不发送模型数据；确认后只执行页面、只读接口和你明确选择的低负载检查。</p></div></div>
        <Button type="primary" size="large" block icon={<RobotOutlined/>} loading={create.isPending} onClick={()=>{setMode("RULE");setConsent(false);create.mutate()}}>开始智能引导</Button>
        <details className="assistant-advanced-mode"><summary>高级：使用 Qwen 自适应规划 <span>可选</span></summary>
          <Segmented block value={mode} onChange={value=>{setMode(value as AssistantMode);if(value==="RULE")setConsent(false)}} options={[
            {value:"RULE",label:"标准模式 · 离线"},{value:"QWEN",label:"Qwen 模式 · 在线"},
          ]}/>
          {mode==="QWEN"&&<div className="assistant-consent">
            <Alert type="info" showIcon title="Qwen 数据告知" description="会发送脱敏后的对话目标、已选目标名称、套件摘要、计划和检测证据；不会发送 Token、Cookie 或保存的凭据。请勿在聊天中粘贴敏感信息。"/>
            <Checkbox checked={consent} onChange={event=>setConsent(event.target.checked)}>我同意发送必要的脱敏摘要给 Qwen</Checkbox>
            <Button block disabled={!consent} loading={create.isPending} onClick={()=>create.mutate()}>使用 Qwen 进入助手</Button>
          </div>}
        </details>
        <Typography.Paragraph className="assistant-form-link"><Link to={`/projects/${projectId}/agent`}>仍想逐项填写？使用原智能测试表单 <ArrowRightOutlined/></Link></Typography.Paragraph>
      </Card>
      <aside className="assistant-safety-card"><SafetyCertificateOutlined/><span className="form-kicker">确认边界</span><h3>聊天不等于授权</h3><p>即使在聊天中输入“确认”“开始”或类似文字，也不会执行检测。计划卡片会固定范围、参数与哈希，并提供独立的确认按钮。</p><div><CheckCircleOutlined/> 先选择登记过的配置</div><div><CheckCircleOutlined/> 再查看冻结计划预览</div><div><CheckCircleOutlined/> 最后明确点击确认执行</div></aside>
    </div>
    <Card title="最近的项目助手会话" className="assistant-history">
      {sessions.isLoading?<Spin/>:sessions.isError?<Alert type="warning" showIcon title="暂时无法加载历史会话" description="不影响创建新会话。"/>:sessions.data?.length?<div className="assistant-session-list" role="list">{sessions.data.slice(0,10).map(item=><div className="assistant-session-list-item" role="listitem" key={item.id}><span className="assistant-list-icon"><MessageOutlined/></span><div className="assistant-session-list-copy"><Space><span>{item.mode==="QWEN"?"Qwen 项目助手":"规则项目助手"}</span><Tag>{item.status||"ACTIVE"}</Tag></Space><small>{item.updatedAt||item.createdAt?new Date(item.updatedAt||item.createdAt||"").toLocaleString("zh-CN"):"已创建"}</small></div><Link to={`/assistant-sessions/${item.id}`}>继续对话</Link></div>)}</div>:<Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="还没有助手会话"/>}
    </Card>
  </div>;
}

export function AssistantSessionPage(){
  const {sessionId=""}=useParams(),{message}=AntApp.useApp(),client=useQueryClient();
  const [content,setContent]=useState("");
  const [goal,setGoal]=useState("");
  const [targetId,setTargetId]=useState<string>();
  const [suiteVersionId,setSuiteVersionId]=useState<string>();
  const [baselineTaskId,setBaselineTaskId]=useState<string>();
  const [receipt,setReceipt]=useState<{taskId?:string;runId?:string}>({});
  const session=useQuery({queryKey:["assistant-session",sessionId],queryFn:()=>request<AssistantSession>(`/assistant-sessions/${sessionId}`),refetchInterval:query=>hasBusyAction(query.state.data)?1500:false});
  const projectId=session.data?.projectId||"";
  const project=useQuery({queryKey:["project",projectId],enabled:!!projectId,queryFn:()=>request<Project>(`/projects/${projectId}`)});
  const recommendation=useQuery({queryKey:["assistant-recommendation",projectId],enabled:!!projectId,
    queryFn:()=>request<AssistantRecommendation>(`/projects/${projectId}/assistant-recommendation`)});
  const targets=useQuery({queryKey:["targets",projectId],enabled:!!projectId,queryFn:()=>request<OnlineTarget[]>(`/projects/${projectId}/targets`)});
  const suites=useQuery({queryKey:["test-suites",projectId],enabled:!!projectId,queryFn:()=>request<TestSuite[]>(`/projects/${projectId}/test-suites`)});
  const baselines=useQuery({queryKey:["agent-baselines",projectId,targetId,suiteVersionId],enabled:!!projectId&&!!targetId&&!!suiteVersionId,
    queryFn:()=>request<BaselineTask[]>(`/projects/${projectId}/agent-tasks/baselines?targetId=${encodeURIComponent(targetId!)}&suiteVersionId=${encodeURIComponent(suiteVersionId!)}`)});
  const [recommendationApplied,setRecommendationApplied]=useState(false);

  useEffect(()=>{
    const value=recommendation.data;if(!value?.ready||recommendationApplied)return;
    if(value.target)setTargetId(value.target.id);if(value.suite)setSuiteVersionId(value.suite.versionId);
    if(!goal.trim())setGoal(value.defaultGoal);
    setRecommendationApplied(true);
  },[goal,recommendation.data,recommendationApplied]);

  useEffect(()=>{
    const value=recommendation.data;
    setBaselineTaskId(value&&value.target?.id===targetId&&value.suite?.versionId===suiteVersionId?value.baseline?.id:undefined);
  },[recommendation.data,targetId,suiteVersionId]);

  const refresh=(response:unknown)=>{
    const record=asRecord(response);
    if(Array.isArray(record.messages)&&Array.isArray(record.actions))client.setQueryData(["assistant-session",sessionId],response);
    else session.refetch();
  };
  const send=useMutation({
    mutationFn:(body:MessageRequest)=>request<unknown>(`/assistant-sessions/${sessionId}/messages`,{method:"POST",headers:{"Idempotency-Key":idempotencyKey()},body:JSON.stringify(body)}),
    onSuccess:response=>{setContent("");refresh(response)},
    onError:error=>message.error((error as Error).message),
  });
  const confirm=useMutation({
    mutationFn:(action:AssistantAction)=>request<unknown>(`/assistant-actions/${action.id}/confirm`,{method:"POST",headers:{"Idempotency-Key":idempotencyKey()},body:JSON.stringify({actionHash:action.actionHash})}),
    onSuccess:response=>{setReceipt(receiptOf(response));message.success("已确认执行，真实任务回执已保存");refresh(response)},
    onError:error=>message.error((error as Error).message),
  });
  const cancel=useMutation({
    mutationFn:(action:AssistantAction)=>request<unknown>(`/assistant-actions/${action.id}/cancel`,{method:"POST",headers:{"Idempotency-Key":idempotencyKey()}}),
    onSuccess:response=>{message.info("已取消这份待确认计划");refresh(response)},
    onError:error=>message.error((error as Error).message),
  });
  const suggestions=useMemo(()=>session.data?.suggestions?.length?session.data.suggestions:defaultSuggestions,[session.data?.suggestions]);
  const actions=session.data?.actions||[];
  const actionReceipts=actions.map(receiptOf).filter(value=>value.taskId||value.runId);
  const currentReceipt=receipt.taskId||receipt.runId?receipt:actionReceipts.at(-1);

  if(session.isLoading)return <Spin/>;
  if(session.isError||!session.data)return <Alert type="error" showIcon title={(session.error as Error)?.message||"无法读取项目助手会话"}/>;
  return <div className="page-content assistant-page">
    <Link className="agent-back" to={projectId?`/projects/${projectId}/quality`:"/"}>← 返回测试中心</Link>
    <div className="assistant-session-head"><div><span className="agent-kicker">PROJECT ASSISTANT / SESSION</span><Typography.Title level={2}>{project.data?.name||"项目"}助手 <Tag color={session.data.mode==="QWEN"?"purple":"green"}>{session.data.mode==="QWEN"?"Qwen":"规则"}</Tag></Typography.Title><Typography.Paragraph>用对话整理需求，用结构化选项固定执行范围。</Typography.Paragraph></div><span><RobotOutlined/></span></div>
    <Alert className="assistant-confirm-warning" type="warning" showIcon title="聊天文字不会执行操作" description="输入“确认”“开始执行”等文字只会成为一条对话消息。真正启动检测必须在计划预览卡片中点击“确认执行”。"/>
    {currentReceipt&&<Alert className="assistant-receipt" type="success" showIcon title="真实任务回执" description={<Space wrap>{currentReceipt.taskId&&<Link to={`/agent-tasks/${currentReceipt.taskId}`}>查看智能体任务 {currentReceipt.taskId}</Link>}{currentReceipt.runId&&<Link to={`/test-runs/${currentReceipt.runId}`}>查看检测运行 {currentReceipt.runId}</Link>}</Space>}/>} 
    <div className="assistant-conversation-grid">
      <section className="assistant-chat-column">
        <Card title="对话记录" className="assistant-chat-card">
          {session.data.messages?.length?<div className="assistant-message-list" role="list">{session.data.messages.map((item,index)=><div className={`assistant-message ${String(item.role||"ASSISTANT").toLowerCase()}`} role="listitem" key={item.id||index}><div><span>{roleNames[String(item.role||"ASSISTANT").toUpperCase()]||item.role||"项目助手"}</span><p>{item.content||""}</p>{item.createdAt&&<small>{new Date(item.createdAt).toLocaleString("zh-CN")}</small>}</div></div>)}</div>:<Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="从下面的推荐语开始，或者直接描述你的目标"/>}
          <div className="assistant-suggestions"><Typography.Text type="secondary">推荐提问</Typography.Text><Space wrap>{suggestions.map(item=><Button key={item} size="small" onClick={()=>{setContent(item);if(!goal)setGoal(item)}}>{item}</Button>)}</Space></div>
          <Input.TextArea aria-label="给项目助手发送消息" value={content} onChange={event=>setContent(event.target.value)} rows={3} maxLength={1000} showCount placeholder="例如：帮我检查页面质量和性能；不要在这里填写 Token 或 Cookie" onPressEnter={event=>{if(!event.shiftKey&&content.trim()){event.preventDefault();send.mutate({content:content.trim()})}}}/>
          <Button type="primary" icon={<SendOutlined/>} disabled={!content.trim()} loading={send.isPending} onClick={()=>send.mutate({content:content.trim()})}>发送消息</Button>
        </Card>
      </section>
      <aside className="assistant-scope-column">
        <Card title="生成计划预览" className="assistant-scope-card">
          {recommendation.isLoading?<div className="assistant-auto-pick"><Spin size="small"/> 正在选择适合的现有配置…</div>:recommendation.data?.ready?<div className="assistant-auto-pick ready"><CheckCircleOutlined/><span><b>已为你准备推荐配置</b><small>{recommendation.data.target?.reason}；{recommendation.data.suite?.reason}{recommendation.data.baseline?`；${recommendation.data.baseline.reason}`:""}</small></span></div>:<Alert type="warning" showIcon title="开始前还需要准备" description={recommendation.data?.warnings.join("；")||"请先登记网站并创建检查方案"} style={{marginBottom:16}}/>}
          <Typography.Paragraph type="secondary">你可以直接使用推荐值，也可以按需更换。系统只会使用已经登记和授权的配置。</Typography.Paragraph>
          <Form layout="vertical">
            <Form.Item label="要检查的网站" required><Select aria-label="助手在线目标" showSearch optionFilterProp="label" value={targetId} onChange={setTargetId} loading={targets.isLoading} placeholder="选择已登记的网站" options={targets.data?.map(item=>({value:item.id,label:`${item.name} · ${item.baseUrl}`}))||[]}/></Form.Item>
            <Form.Item label="检查方案" required><Select aria-label="助手测试套件版本" showSearch optionFilterProp="label" value={suiteVersionId} onChange={setSuiteVersionId} loading={suites.isLoading} placeholder="选择检查方案" options={suites.data?.map(item=>({value:item.currentVersionId,label:`${item.name} · V${item.currentVersion}`}))||[]}/></Form.Item>
            <Form.Item label="与哪次结果比较（可选）"><Select aria-label="助手历史基线" allowClear showSearch optionFilterProp="label" value={baselineTaskId} onChange={setBaselineTaskId} loading={baselines.isLoading} disabled={!targetId||!suiteVersionId} placeholder={!targetId||!suiteVersionId?"先选择网站和方案":"从本次开始建立趋势"} options={baselines.data?.map(item=>({value:item.id,label:`${item.goal} · ${item.status}`}))||[]}/></Form.Item>
            <Form.Item label="这次想检查什么？" required><Input.TextArea aria-label="助手检测目标" value={goal} onChange={event=>setGoal(event.target.value)} rows={4} maxLength={1000} placeholder="例如：做一次日常体检，并告诉我应该先处理什么"/></Form.Item>
            <Button block size="large" type="primary" icon={<RobotOutlined/>} disabled={!targetId||!suiteVersionId||!goal.trim()} loading={send.isPending} onClick={()=>send.mutate(buildPrepareRequest({content,goal,targetId:targetId!,suiteVersionId:suiteVersionId!,baselineTaskId}))}>生成可确认的检查计划</Button>
          </Form>
        </Card>
      </aside>
    </div>
    <div className="assistant-actions">
      {actions.length?<>{actions.map(action=><ActionPreview key={action.id} action={action} confirming={confirm.isPending} cancelling={cancel.isPending} onConfirm={()=>confirm.mutate(action)} onCancel={()=>cancel.mutate(action)}/>)}</>:<Card><Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="生成计划后，这里会出现冻结的执行范围与确认按钮"/></Card>}
    </div>
    {currentReceipt?.taskId&&<SmartTaskWorkspace taskId={currentReceipt.taskId}/>} 
  </div>;
}

function ActionPreview({action,confirming,cancelling,onConfirm,onCancel}:{action:AssistantAction;confirming:boolean;cancelling:boolean;onConfirm:()=>void;onCancel:()=>void}){
  const source=actionSource(action),plan=asRecord(source.plan),scope=Object.keys(plan).length?plan:source;
  const modules=Array.isArray(scope.modules)?scope.modules.map(String):[];
  const receipt=receiptOf(action);
  const status=String(action.status||"PROPOSED").toUpperCase();
  const pending=pendingStatuses.has(status);
  const statusMeta=actionStatusMeta[status]||{label:action.status||"状态未知",color:"default",type:"info" as const,title:"操作状态已更新",description:"请查看真实任务回执或重新加载页面确认最新状态。"};
  return <Card className="assistant-action-card" title={<Space><RobotOutlined/><span>计划预览</span><Tag color={statusMeta.color}>{statusMeta.label}</Tag></Space>} extra={<Typography.Text type="secondary">操作 #{action.id}</Typography.Text>}>
    <Descriptions column={{xs:1,sm:1,md:2}} bordered size="small" items={[
      {key:"goal",label:"检测目标",children:textOf(source.goal)||textOf(scope.goal)||"以对话中的目标为准"},
      {key:"target",label:"在线目标",children:textOf(scope.targetName)||textOf(source.targetName)||textOf(source.targetId)||"已固定目标"},
      {key:"suite",label:"套件版本",children:textOf(scope.suiteVersion)||textOf(source.suiteVersion)||textOf(source.suiteVersionId)||"已固定版本"},
      {key:"baseline",label:"历史基线",children:textOf(scope.baselineTaskId)||textOf(source.baselineTaskId)||"不使用基线"},
      {key:"modules",label:"检测模块",children:modules.length?modules.map(item=>moduleNames[item]||item).join(" → "):"以冻结计划为准"},
      {key:"mode",label:"决策模式",children:textOf(scope.mode)||textOf(source.mode)||"沿用当前会话模式"},
    ]}/>
    {textOf(scope.reason)&&<Typography.Paragraph className="assistant-plan-reason">计划说明：{textOf(scope.reason)}</Typography.Paragraph>}
    <Alert type={statusMeta.type} showIcon title={statusMeta.title} description={statusMeta.description} style={{marginTop:16}}/>
    {receipt.taskId||receipt.runId?<Space className="assistant-action-receipt" wrap>{receipt.taskId&&<Link to={`/agent-tasks/${receipt.taskId}`}>查看真实任务回执</Link>}{receipt.runId&&<Link to={`/test-runs/${receipt.runId}`}>查看检测运行</Link>}</Space>:pending?<Space className="assistant-action-buttons"><Button danger loading={cancelling} onClick={onCancel}>取消计划</Button><Button type="primary" icon={<CheckCircleOutlined/>} loading={confirming} onClick={onConfirm}>确认执行</Button></Space>:<Typography.Paragraph type="secondary" style={{marginTop:16}}>这份操作已结束，不能再次确认。</Typography.Paragraph>}
    {action.actionHash&&<Typography.Text className="assistant-action-hash" type="secondary">操作哈希：{action.actionHash}</Typography.Text>}
  </Card>;
}

function hasBusyAction(value?:AssistantSession){return !!value?.actions?.some(item=>["CONFIRMING","RUNNING"].includes(String(item.status||"").toUpperCase()));}
function parseRecord(value:unknown):Record<string,unknown>{
  if(typeof value==="string")try{return asRecord(JSON.parse(value))}catch{return {}};
  return asRecord(value);
}
function asRecord(value:unknown):Record<string,unknown>{return value&&typeof value==="object"&&!Array.isArray(value)?value as Record<string,unknown>:{};}
function actionSource(action:AssistantAction){
  for(const value of [action.preview,action.payload,action.argument]){const parsed=parseRecord(value);if(Object.keys(parsed).length)return parsed;}
  return action as unknown as Record<string,unknown>;
}
function textOf(value:unknown){return value===null||value===undefined?"":String(value);}
function receiptOf(value:unknown):{taskId?:string;runId?:string}{
  const top=parseRecord(value),result=parseRecord(top.result),receipt=parseRecord(top.receipt),payload=parseRecord(top.payload);
  const taskId=textOf(top.agentTaskId||top.taskId||result.agentTaskId||result.taskId||receipt.agentTaskId||receipt.taskId||receipt.resourceId||payload.agentTaskId||payload.taskId)||undefined;
  const runId=textOf(top.runId||result.runId||receipt.runId||payload.runId)||undefined;
  return {taskId,runId};
}
