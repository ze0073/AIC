import {useEffect} from "react";
import {useMutation,useQuery,useQueryClient} from "@tanstack/react-query";
import {Alert,App as AntApp,Button,Card,Flex,Progress,Space,Spin,Steps,Tag,Typography} from "antd";
import {ArrowRightOutlined,BarChartOutlined,BulbOutlined,CheckCircleFilled,ClockCircleOutlined,SafetyCertificateOutlined} from "@ant-design/icons";
import {Link} from "react-router-dom";
import {request} from "./api";

type TaskStep={action:string;argument?:Record<string,unknown>;result?:Record<string,unknown>;status:string;runId?:string};
type LiveTask={id:string;projectId:string;goal:string;status:string;cancelRequested?:boolean;finishedAt?:string;errorMessage?:string;
  plan:{modules:string[]};summary?:Record<string,unknown>;steps:TaskStep[]};
type TrendPoint={taskId:string;status:string;goal:string;finishedAt:string;issueCount:number;high:number;medium:number;low:number;
  completedModules:string[];p95Ms?:number;errorRate?:number;navigationMs?:number;lcpMs?:number};
type ActionItem={id:string;kind:string;priority:string;title:string;reason:string;module:string;evidenceNote:string;recommendedOwner:string;
  nextStep:string;prevention:string;detailPath:string};
type Insight={schemaVersion:string;taskId:string;projectId:string;status:string;headline:string;summary:string;confidence:"HIGH"|"MEDIUM"|"LIMITED";
  counts:{issues:number;high:number;medium:number;low:number;pendingReview:number;incompleteModules:number};
  actionItems:ActionItem[];trend:{points:TrendPoint[];assessment:{overall:string;issues:string;performance:string;experience:string;message:string}};
  limitations:string[];evidenceLinks:Array<{runId:string;module:string;status:string;label:string;detailPath:string}>;generatedAt:string};

const terminalStatuses=new Set(["COMPLETED","PARTIAL","FAILED","CANCELLED","INTERRUPTED"]);
const moduleNames:Record<string,string>={FRONTEND:"页面体验",SECURITY:"接口与权限",PERFORMANCE:"承载能力"};
const statusNames:Record<string,string>={AWAITING_START:"等待确认",PENDING:"准备中",RUNNING:"检查中",COMPLETED:"已完成",PARTIAL:"部分完成",FAILED:"失败",CANCELLED:"已取消",INTERRUPTED:"已中断"};
const directionNames:Record<string,string>={IMPROVED:"改善",WORSE:"需关注",STABLE:"稳定",NO_DATA:"数据不足",NO_BASELINE:"首个基线",LIMITED:"结果受限"};
const confidenceMeta={HIGH:{label:"证据完整",color:"green"},MEDIUM:{label:"部分证据",color:"gold"},LIMITED:{label:"证据有限",color:"orange"}} as const;

export function SmartTaskWorkspace({taskId,showProgress=true}:{taskId:string;showProgress?:boolean}){
  const client=useQueryClient(),{message}=AntApp.useApp();
  const task=useQuery({queryKey:["agent-task",taskId],queryFn:()=>request<LiveTask>(`/agent-tasks/${taskId}`),
    refetchInterval:query=>terminalStatuses.has(query.state.data?.status||"")?false:2000});
  const terminal=terminalStatuses.has(task.data?.status||"");
  const insights=useQuery({queryKey:["agent-insights",taskId],enabled:terminal,queryFn:()=>request<Insight>(`/agent-tasks/${taskId}/insights`)});
  const cancel=useMutation({mutationFn:()=>request<LiveTask>(`/agent-tasks/${taskId}/cancel`,{method:"POST"}),
    onSuccess:value=>{client.setQueryData(["agent-task",taskId],value);message.info("正在安全停止后续检查")},onError:error=>message.error((error as Error).message)});
  useEffect(()=>{
    if(!task.data||terminal||typeof EventSource==="undefined")return;
    const stream=new EventSource(`/api/agent-tasks/${taskId}/events`);
    stream.addEventListener("agent-task",event=>{try{client.setQueryData(["agent-task",taskId],JSON.parse((event as MessageEvent<string>).data) as LiveTask)}catch{/* 轮询继续兜底 */}});
    return()=>stream.close();
  },[client,task.data,taskId,terminal]);
  if(task.isLoading)return <Card className="smart-loading"><Spin/> 正在读取检查任务…</Card>;
  if(task.isError||!task.data)return <Alert type="error" showIcon title="暂时无法读取检查进度" description={(task.error as Error)?.message}/>;
  const states=moduleStates(task.data),finished=states.filter(item=>["finish","error"].includes(item.status)).length;
  const active=states.find(item=>item.status==="process"),percent=terminal?100:Math.round(((finished+(active?.status==="process"?0.45:0))/Math.max(states.length,1))*100);
  return <section className="smart-task-workspace" aria-label="智能检查工作区">
    {showProgress&&<Card className="smart-live-card" title={<Space>{task.data.status==="RUNNING"&&<span className="agent-live-dot"/>}<span>实时检查进度</span><Tag color={task.data.status==="COMPLETED"?"success":task.data.status==="FAILED"?"error":"processing"}>{statusNames[task.data.status]||task.data.status}</Tag></Space>}
      extra={task.data.status==="RUNNING"?<Button danger size="small" loading={cancel.isPending} disabled={task.data.cancelRequested} onClick={()=>cancel.mutate()}>{task.data.cancelRequested?"正在停止":"停止检查"}</Button>:undefined}>
      <Progress percent={percent} status={task.data.status==="FAILED"?"exception":task.data.status==="COMPLETED"?"success":"active"}/>
      <Flex justify="space-between" gap={12} wrap><Typography.Text>{active?`正在检查：${moduleNames[active.module]||active.module}`:terminal?String(task.data.summary?.message||task.data.errorMessage||"检查已结束"):"正在准备已确认的检查步骤"}</Typography.Text><Typography.Text type="secondary">页面会自动更新，可以稍后回来继续查看</Typography.Text></Flex>
      <Steps responsive className="smart-module-steps" items={states.map(item=>({title:moduleNames[item.module]||item.module,status:item.status,content:item.status==="process"?"正在采集证据":item.status==="finish"?"已保存结果":item.status==="error"?"未完整完成":"等待中"}))}/>
    </Card>}
    {terminal&&(insights.isLoading?<Card className="smart-loading"><Spin/> 正在整理结论与行动清单…</Card>:insights.isError||!insights.data?<Alert type="warning" showIcon title="检查已结束，智能结论暂时无法加载" description="原始运行和证据仍已保存，可从任务详情继续查看。"/>:<SmartOutcome insight={insights.data}/>) }
  </section>;
}

function SmartOutcome({insight}:{insight:Insight}){
  const confidence=confidenceMeta[insight.confidence]||confidenceMeta.LIMITED,assessment=insight.trend.assessment;
  return <div className="smart-outcome">
    <Card className="smart-outcome-hero">
      <div className="smart-outcome-heading"><span><CheckCircleFilled/></span><div><Space wrap><Typography.Text className="smart-kicker">本次智能结论</Typography.Text><Tag color={confidence.color}>{confidence.label}</Tag></Space><Typography.Title level={2}>{insight.headline}</Typography.Title><Typography.Paragraph>{insight.summary}</Typography.Paragraph></div></div>
      <div className="smart-count-grid" aria-label="结果数字概览">
        <Metric label="待处理事项" value={insight.counts.issues}/><Metric label="高优先级" value={insight.counts.high}/><Metric label="等待审阅" value={insight.counts.pendingReview}/><Metric label="未完整模块" value={insight.counts.incompleteModules}/>
      </div>
    </Card>
    <div className="smart-insight-grid">
      <Card className="smart-trend-card" title={<Space><BarChartOutlined/>历史变化</Space>} extra={<Tag color={assessment.overall==="IMPROVED"?"green":assessment.overall==="WORSE"?"red":"default"}>{directionNames[assessment.overall]||assessment.overall}</Tag>}>
        <Typography.Paragraph>{assessment.message}</Typography.Paragraph>
        <div className="trend-assessment"><span>问题数 <b>{directionNames[assessment.issues]||assessment.issues}</b></span><span>页面体验 <b>{directionNames[assessment.experience]||assessment.experience}</b></span><span>承载能力 <b>{directionNames[assessment.performance]||assessment.performance}</b></span></div>
        <div className="smart-chart-grid"><MiniTrend points={insight.trend.points} field="issueCount" label="待处理事项" unit="项"/><MiniTrend points={insight.trend.points} field="p95Ms" label="P95 响应耗时" unit="ms"/></div>
      </Card>
      <Card className="smart-boundary-card" title={<Space><SafetyCertificateOutlined/>如何理解结论</Space>}>
        {insight.limitations.map((item,index)=><div className="smart-limitation" key={`${index}-${item}`}><span>{index+1}</span><p>{item}</p></div>)}
      </Card>
    </div>
    <Card className="smart-actions-card" title={<Space><BulbOutlined/>下一步行动清单</Space>} extra={<Typography.Text type="secondary">按优先级排序</Typography.Text>}>
      <div className="smart-action-list">{insight.actionItems.map(item=><article key={item.id}>
        <header><Tag color={item.priority==="P0"?"red":item.priority==="P1"?"orange":item.priority==="P2"?"blue":"default"}>{item.priority}</Tag><Tag>{moduleNames[item.module]||item.module}</Tag><span>{item.recommendedOwner}</span></header>
        <h3>{item.title}</h3><p>{item.reason}</p>
        <div className="smart-evidence-note"><SafetyCertificateOutlined/><span><b>证据说明</b>{item.evidenceNote}</span></div>
        <dl><div><dt>建议怎么做</dt><dd>{item.nextStep}</dd></div><div><dt>以后怎么避免</dt><dd>{item.prevention}</dd></div></dl>
        {item.kind==='ISSUE'&&<Link className="smart-repair-link" to={`/issues/${item.id}/repair`}><Button type="primary">开始修复</Button></Link>}{' '}
        <Link to={item.detailPath}>查看对应证据与处理入口 <ArrowRightOutlined/></Link>
      </article>)}</div>
    </Card>
    <Card size="small" className="smart-evidence-links" title="本次证据入口"><Space wrap>{insight.evidenceLinks.map(item=><Link key={`${item.runId}-${item.module}`} to={item.detailPath}><Button size="small">{item.label} · {statusNames[item.status]||item.status}</Button></Link>)}</Space></Card>
  </div>;
}

function Metric({label,value}:{label:string;value:number}){return <div><small>{label}</small><strong>{value}</strong></div>}

function MiniTrend({points,field,label,unit}:{points:TrendPoint[];field:keyof TrendPoint;label:string;unit:string}){
  const samples=points.map((point,index)=>({point,index,value:typeof point[field]==="number"?point[field] as number:undefined})).filter(item=>item.value!==undefined) as Array<{point:TrendPoint;index:number;value:number}>;
  if(!samples.length)return <div className="mini-trend empty"><ClockCircleOutlined/><b>{label}</b><span>暂无可用趋势数据</span></div>;
  const width=420,height=150,padX=28,padY=24,max=Math.max(...samples.map(item=>item.value),1),min=Math.min(...samples.map(item=>item.value),0),range=Math.max(max-min,1);
  const x=(index:number)=>points.length===1?width/2:padX+(index/(points.length-1))*(width-padX*2);
  const y=(value:number)=>height-padY-((value-min)/range)*(height-padY*2);
  const coordinates=samples.map(item=>`${x(item.index)},${y(item.value)}`).join(" ");
  return <figure className="mini-trend"><figcaption><b>{label}</b><span>{samples.at(-1)?.value.toFixed(field==="issueCount"?0:1)} {unit}</span></figcaption><svg viewBox={`0 0 ${width} ${height}`} role="img" aria-label={`${label}最近 ${samples.length} 次变化`}><path d={`M${padX} ${height-padY}H${width-padX}`} className="trend-axis"/><polyline points={coordinates} className="trend-line"/>{samples.map(item=><g key={item.point.taskId}><circle cx={x(item.index)} cy={y(item.value)} r="5"/><title>{new Date(item.point.finishedAt).toLocaleDateString("zh-CN")}：{item.value.toFixed(field==="issueCount"?0:1)} {unit}</title></g>)}</svg><div className="trend-point-labels">{samples.map(item=><span key={item.point.taskId}>{new Date(item.point.finishedAt).toLocaleDateString("zh-CN",{month:"numeric",day:"numeric"})}</span>)}</div></figure>;
}

function moduleStates(task:LiveTask){return task.plan.modules.map(module=>{
  const step=[...(task.steps||[])].reverse().find(item=>item.action==="RUN_MODULE"&&item.argument?.module===module);
  const effective=String(step?.result?.status||step?.status||"PENDING");
  const status=effective==="COMPLETED"?"finish":effective==="RUNNING"?"process":step&&["PARTIAL","FAILED","CANCELLED","INTERRUPTED"].includes(effective)?"error":"wait";
  return {module,effective,status:status as "finish"|"process"|"error"|"wait"};
})}
