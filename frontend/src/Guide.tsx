import {useEffect,useRef,useState} from 'react'
import {useQuery,useQueryClient} from '@tanstack/react-query'
import {useLocation,useNavigate} from 'react-router-dom'
import {Alert,App as AntApp,Button,Card,Checkbox,Collapse,Descriptions,Drawer,Input,Segmented,Select,Space,Spin,Tag,Tour} from 'antd'
import {CloseOutlined,CompassOutlined,PauseOutlined,PlayCircleOutlined,SendOutlined,SoundOutlined,StopOutlined} from '@ant-design/icons'
import {ApiError,idempotencyKey,request} from './api'
import {contextForGoal,createGuide,messageGuide,updateGuide} from './guideApi'
import {GuideCharacter} from './GuideCharacter'
import type {CharacterState} from './GuideCharacter'
import {GuideFlows,flowGuidance,guideGoals} from './GuideFlows'
import {speakGuide,stopGuideSpeech} from './guideSpeech'
import type {GuideAction,GuideContext,GuideDraft,GuideGoal,GuidePreferences,GuideSession,GuideStep} from './guideTypes'
import type {Site} from './Websites'
import './guide.css'

const actionNames:Record<string,string>={CREATE_SITE:'创建网站工作区',CONFIGURE_DEPLOY:'部署并自动检测',START_AGENT_TASK:'启动检测计划',PREPARE_REPAIR:'准备修复补丁',APPLY_REPAIR:'应用修复并复测',PUBLISH_SITE:'更新正式网站',CREATE_REPORT_SNAPSHOT:'生成报告快照'}
const statusNames:Record<string,string>={PROPOSED:'等待确认',SUCCEEDED:'已执行',CANCELLED:'已取消',EXPIRED:'已过期',FAILED:'执行失败'}
const labelNames:Record<string,string>={name:'网站名称',purpose:'网站用途',siteName:'网站',siteId:'网站编号',versionId:'源码版本',deploymentId:'部署版本',expectedCurrentId:'当前正式版本',suiteVersionId:'检查方案版本',goal:'检测目标',file:'修改文件',issueTitle:'待处理问题',mode:'修复方式',runStatus:'检测状态',issueCount:'问题或候选数',notice:'执行说明'}

export function Guide({userId}:{userId:string}){
  const cache=useQueryClient(),nav=useNavigate(),location=useLocation(),{message}=AntApp.useApp()
  const [open,setOpen]=useState(false),[activeId,setActiveId]=useState<string>(),[draft,setDraft]=useState<GuideDraft>({})
  const [busy,setBusy]=useState(false),[content,setContent]=useState(''),[error,setError]=useState(''),[voiceMessage,setVoiceMessage]=useState('')
  const [consent,setConsent]=useState(false),[tour,setTour]=useState<GuideStep>(),[tourTarget,setTourTarget]=useState<HTMLElement|null>(null),[tourReady,setTourReady]=useState(false)
  const [playing,setPlaying]=useState(false),[paused,setPaused]=useState(false)
  const [speechLoading,setSpeechLoading]=useState(false),speechRequest=useRef(0)
  const stopSpeech=()=>{speechRequest.current++;stopGuideSpeech();setSpeechLoading(false);setPlaying(false);setPaused(false)}
  const [mobile,setMobile]=useState(()=>window.matchMedia('(max-width:600px)').matches)
  useEffect(()=>{const media=window.matchMedia('(max-width:600px)');const change=()=>setMobile(media.matches);media.addEventListener('change',change);return()=>media.removeEventListener('change',change)},[])
  const prefs=useQuery({queryKey:['guide-preferences',userId],queryFn:()=>request<GuidePreferences>('/guide/preferences'),retry:false})
  const sessions=useQuery({queryKey:['guide-sessions',userId],queryFn:()=>request<GuideSession[]>('/guide/sessions'),retry:false})
  const current=useQuery({queryKey:['guide-session',userId,activeId],queryFn:()=>request<GuideSession>('/guide/sessions/'+activeId),enabled:!!activeId,refetchInterval:open?3000:false,retry:false})
  const session=current.data,sessionRef=useRef<GuideSession|undefined>(session),draftRef=useRef(draft),selectedRef=useRef(activeId),queue=useRef<Promise<void>>(Promise.resolve()),pending=useRef(0),launcher=useRef<HTMLButtonElement>(null)
  sessionRef.current=session;draftRef.current=draft;selectedRef.current=activeId
  const welcomed=useRef(false),restored=useRef(false)
  useEffect(()=>{if(prefs.data&&!prefs.data.welcomeSeen&&!welcomed.current){welcomed.current=true;setOpen(true)}},[prefs.data])
  useEffect(()=>{if(!restored.current&&sessions.data){restored.current=true;const saved=window.sessionStorage.getItem('guide-active:'+userId);const candidate=sessions.data.find(s=>s.id===saved)||sessions.data.find(s=>s.status==='ACTIVE');if(candidate)setActiveId(candidate.id)}},[sessions.data,userId])
  useEffect(()=>{if(session){setDraft(session.draft);setConsent(session.aiConsent)}},[session?.id])
  useEffect(()=>{if(activeId)window.sessionStorage.setItem('guide-active:'+userId,activeId)},[activeId,userId])
  useEffect(()=>()=>{speechRequest.current++;stopGuideSpeech()},[])
  const accept=(s:GuideSession)=>{
    cache.setQueryData(['guide-session',userId,s.id],s)
    if(selectedRef.current===s.id)sessionRef.current=s
    cache.invalidateQueries({queryKey:['guide-sessions',userId]})
    for(const key of ['sites','guide-sites','site','projects','guide-snapshots','test-runs'])cache.invalidateQueries({queryKey:[key]})
  }
  const work=(fn:()=>Promise<unknown>):Promise<void>=>{
    pending.current++;setBusy(true);setError('')
    const task=queue.current.then(async()=>{try{const result=await fn();if(result&&typeof result==='object'&&'revision' in result)accept(result as GuideSession)}
      catch(e){setError((e as Error).message||'操作未完成，请重试');if(e instanceof ApiError&&e.code==='GUIDE_SESSION_CHANGED')await current.refetch()}
      finally{pending.current--;if(!pending.current)setBusy(false)}})
    queue.current=task;return task
  }
  const preference=async(change:Partial<GuidePreferences>)=>{const value=await request<GuidePreferences>('/guide/preferences',{method:'PUT',body:JSON.stringify(change)});cache.setQueryData(['guide-preferences',userId],value)}
  const flushDraft=async()=>{
    const s=sessionRef.current;if(!s||JSON.stringify(draftRef.current)===JSON.stringify(s.draft))return s
    const saved=await updateGuide(s,{draft:draftRef.current});accept(saved);return saved
  }
  useEffect(()=>{
    if(!session||busy||JSON.stringify(draft)===JSON.stringify(session.draft))return
    const timer=setTimeout(()=>{work(()=>flushDraft())},700)
    return ()=>clearTimeout(timer)
  },[draft,session?.draft,busy,session?.id])
  const begin=(goal:GuideGoal,context?:GuideContext)=>{
    work(async()=>{await flushDraft();const s=await createGuide(goal,context??contextForGoal(sessionRef.current?.context||{},goal),sessionRef.current?.assistance||'GUIDED');setActiveId(s.id);selectedRef.current=s.id;sessionRef.current=s;accept(s);setDraft(s.draft);setContent('');setOpen(true);await preference({welcomeSeen:true})})
  }
  useEffect(()=>{
    const listener=(event:Event)=>{const detail=(event as CustomEvent<{goal?:GuideGoal;context?:GuideContext}>).detail;setOpen(true);if(detail?.goal)begin(detail.goal,detail.context)}
    window.addEventListener('open-website-guide',listener);return()=>window.removeEventListener('open-website-guide',listener)
  },[session?.id])
  const patchContext=(context:GuideContext)=>work(async()=>{const s=await flushDraft();if(s)return updateGuide(s,{context})})
  // When users follow a tour in the original page, recover the resource created there.
  useEffect(()=>{
    if(!session||busy)return
    const siteMatch=location.pathname.match(/^\/sites\/([0-9A-Z]{26})$/)
    if(siteMatch&&session.goal==='DEPLOY'&&!session.context.siteId){
      work(async()=>{const site=await request<Site>('/sites/'+siteMatch[1]);const s=sessionRef.current;if(s)return updateGuide(s,{context:{siteId:site.id,projectId:site.projectId}})})
    }
    const repairMatch=location.pathname.match(/^\/issues\/([0-9A-Z]{26})\/repair$/)
    const repairId=new URLSearchParams(location.search).get('repairId')
    if(repairMatch&&repairId&&session.goal==='REPAIR'&&session.context.issueId===repairMatch[1]&&session.context.repairId!==repairId){
      patchContext({...session.context,repairId})
    }
  },[location.pathname,location.search,session?.id,busy])
  useEffect(()=>{
    const listener=(event:Event)=>{const detail=(event as CustomEvent<GuideContext>).detail;const s=sessionRef.current;if(!s||!detail)return
      if((detail.siteId&&detail.siteId===s.context.siteId)||(detail.issueId&&detail.issueId===s.context.issueId)||(detail.runId&&detail.runId===s.context.runId)||(detail.taskId&&detail.projectId===s.context.projectId&&s.goal==='CHECK')){
        patchContext({...s.context,...detail})
      }
    }
    window.addEventListener('guide-business-result',listener);return()=>window.removeEventListener('guide-business-result',listener)
  },[session?.id])
  useEffect(()=>{
    if(!activeId||!open)return
    const ids=new Set<string>(),s=session
    if(s?.resources.deployment)ids.add('/api/deployments/'+s.resources.deployment.id+'/events')
    if(s?.resources.task)ids.add('/api/agent-tasks/'+s.resources.task.id+'/events')
    if(s?.resources.run&&!s.context.snapshotId)ids.add('/api/test-runs/'+s.resources.run.id+'/events')
    const connections=[...ids].map(url=>{const source=new EventSource(url);const refresh=()=>cache.invalidateQueries({queryKey:['guide-session',userId,activeId]});for(const event of ['deployment','agent-task','test-run'])source.addEventListener(event,refresh);source.onmessage=refresh;return source})
    return ()=>connections.forEach(source=>source.close())
  },[activeId,open,session?.resources.deployment?.id,session?.resources.task?.id,session?.resources.run?.id])
  const startTour=(step:GuideStep)=>{
    stopSpeech();setOpen(false);setTour(step);setTourReady(false);setTourTarget(null)
    if(step.path)nav(step.path,{state:{guideContext:sessionRef.current?.context,guideGoal:draftRef.current.goal}})
  }
  useEffect(()=>{
    if(!tour)return
    let stopped=false,attempts=0
    const find=()=>{if(stopped)return;const node=tour.anchor?document.querySelector<HTMLElement>('[data-guide="'+tour.anchor+'"]'):null
      if(node||attempts++>=25){setTourTarget(node);setTourReady(true);return}timer=setTimeout(find,120)}
    let timer=setTimeout(find,100);return()=>{stopped=true;clearTimeout(timer)}
  },[tour,location.pathname])
  const close=()=>{work(async()=>{await flushDraft();await preference({welcomeSeen:true})});setOpen(false);stopSpeech();setTimeout(()=>launcher.current?.focus(),0)}
  const send=()=>{const text=content.trim();if(!text)return
    if(!session){work(async()=>{const s=await createGuide('HELP');setActiveId(s.id);selectedRef.current=s.id;accept(s);const replied=await messageGuide(s.id,text);accept(replied);setContent('')});return}
    work(async()=>{const s=await flushDraft();if(!s)return;const result=await messageGuide(s.id,text);setContent('');return result})
  }
  const prepare=(type:string,parameters?:Record<string,unknown>)=>work(async()=>{const s=await flushDraft();if(!s)return;return messageGuide(s.id,'请准备：'+actionNames[type],type,parameters)})
  const confirm=(action:GuideAction,accepted:boolean)=>work(()=>request<GuideSession>('/guide/actions/'+action.id+'/confirm',{method:'POST',headers:{'Idempotency-Key':idempotencyKey()},body:JSON.stringify({actionHash:action.actionHash,reviewAccepted:accepted})}))
  const cancel=(action:GuideAction)=>work(()=>request<GuideSession>('/guide/actions/'+action.id+'/cancel',{method:'POST',headers:{'Idempotency-Key':idempotencyKey()}}))
  const lastReply=session?.messages.filter(m=>m.role==='ASSISTANT').at(-1)?.content||'你好，我是视界向导。今天想做什么？'
  const speak=async()=>{
    const id=++speechRequest.current
    setVoiceMessage('');setSpeechLoading(true);setPlaying(false);setPaused(false)
    const result=await speakGuide(lastReply)
    if(id!==speechRequest.current)return
    setSpeechLoading(false)
    if(result.status==='unavailable')setVoiceMessage(result.message)
    if(result.status==='started'){setPlaying(true);work(()=>preference({voiceEnabled:true}))}
  }
  const failed=!!error||!!current.error||['FAILED','CANCELLED','PARTIAL'].includes(session?.resources.deployment?.status||session?.resources.task?.status||'')
  const state:CharacterState=failed?'error':busy?'working':session?.status==='COMPLETED'?'complete':open?'reply':'idle'
  const continuation=sessions.data?.find(s=>s.status==='ACTIVE')
  return <>
    <Button ref={launcher} style={{visibility:open?'hidden':undefined}} className={'guide-launcher '+(!prefs.data?.characterVisible?'text-only':'')} aria-label="打开视界向导" onClick={()=>setOpen(true)} icon={!prefs.data?.characterVisible?<CompassOutlined/>:undefined}>
      {prefs.data?.characterVisible!==false&&<GuideCharacter state={state} small/>}<span><b>视界向导</b><small>{continuation?'继续上次操作':'今天想做什么？'}</small></span>
    </Button>
    <Drawer className="guide-drawer" title={<span className="guide-drawer-title"><CompassOutlined/> 视界向导 <Tag>全程陪你操作</Tag></span>} placement={mobile?'bottom':'right'} open={open} onClose={close} size={mobile?'90vh':440} mask={false} destroyOnHidden={false}>
      <div className="guide-welcome"><GuideCharacter state={state}/><div><span>SHIJIE / YOUR GUIDE</span><h2>{session?'我们继续。':'今天想做什么？'}</h2><p>{session?.resources.site?.name||'选择目标，我会带你完成每一步。'}</p></div></div>
      {!prefs.data?.welcomeSeen&&<Button className="guide-skip" onClick={close}>先自己操作</Button>}
      <div className="guide-goals" aria-label="选择向导目标">{guideGoals.map(goal=><button type="button" key={goal.key} disabled={busy} aria-pressed={session?.goal===goal.key} onClick={()=>begin(goal.key)}><span>{goal.number}</span><div><b>{goal.title}</b><small>{goal.description}</small></div></button>)}</div>
      {(sessions.isLoading||prefs.isLoading||current.isLoading)&&<Spin/>}
      <ResourceError error={prefs.error||sessions.error||current.error} retry={()=>{prefs.refetch();sessions.refetch();current.refetch()}}/>
      {error&&<Alert type="warning" showIcon title="这次操作未完成" description={error} closable onClose={()=>setError('')}/>}
      {!!sessions.data?.length&&<div className="guide-history"><label>继续已有流程</label><Select aria-label="选择向导历史流程" value={activeId} disabled={busy} options={sessions.data.map(s=>({value:s.id,label:guideGoals.find(g=>g.key===s.goal)?.title+' · '+new Date(s.updatedAt).toLocaleString('zh-CN')}))} onChange={id=>work(async()=>{await flushDraft();setActiveId(id);setDraft({})})}/></div>}
      {session&&<>
        <Segmented block aria-label="向导操作方式" value={session.assistance} disabled={busy} options={[{value:'GUIDED',label:'带我操作'},{value:'DELEGATED',label:'帮我办理'}]} onChange={assistance=>work(async()=>{const s=await flushDraft();if(s)return updateGuide(s,{assistance:assistance as GuideSession['assistance']})})}/>
        <p className="guide-mode-note">{session.assistance==='GUIDED'?'我会打开页面并标出当前需要操作的位置。':'我会准备具体操作，由你确认执行。'}</p>
        <GuideFlows session={session} draft={draft} busy={busy} onDraft={setDraft} onContext={patchContext} onGoal={begin} prepare={prepare} operate={fn=>work(async()=>{const value=await fn();await current.refetch();return value})} refresh={()=>current.refetch()} guide={startTour}/>
        {!!session.actions.length&&<section className="guide-actions" aria-label="待确认操作">{session.actions.map(action=><GuideActionCard key={action.id} action={action} busy={busy} onConfirm={accepted=>confirm(action,accepted)} onCancel={()=>cancel(action)}/>)}</section>}
        {session.status==='ACTIVE'&&<Button disabled={busy} onClick={()=>work(async()=>{const s=await flushDraft();if(s)return updateGuide(s,{status:'COMPLETED'})})}>结束本次引导</Button>}
        {session.status==='COMPLETED'&&<Alert type="success" title="本次引导已结束" description="业务任务仍按实际进度运行；可以选择新目标或继续历史流程。"/>}
        <Collapse className="guide-ai-settings" items={[{key:'qwen',label:session.mode==='QWEN'?'Qwen 增强已开启':'可选：Qwen 理解与解释',children:<><p>仅发送脱敏需求、当前目标和真实任务状态摘要。文件、数据库、凭据和日志不进入向导模型请求。</p>
          <Checkbox checked={consent} disabled={busy} onChange={e=>{setConsent(e.target.checked);if(!e.target.checked&&session.mode==='QWEN')work(()=>updateGuide(sessionRef.current!,{mode:'RULE',aiConsent:false}))}}>同意发送必要的脱敏摘要</Checkbox>
          {!prefs.data?.qwenAvailable&&<p>模型尚未配置，规则模式可继续使用全部流程。</p>}
          <Space><Button disabled={busy||!consent||!prefs.data?.qwenAvailable} onClick={()=>work(()=>updateGuide(sessionRef.current!,{mode:'QWEN',aiConsent:true}))}>开启 Qwen 增强</Button><Button disabled={busy} onClick={()=>work(()=>updateGuide(sessionRef.current!,{mode:'RULE',aiConsent:false}))}>使用规则模式</Button></Space>
        </>}]}/>
        <div className="guide-messages" role="log" aria-label="向导对话记录" aria-live="polite">{session.messages.slice(-10).map(item=><article key={item.id} className={'guide-message '+item.role.toLowerCase()}><small>{item.role==='USER'?'你':item.role==='SYSTEM'?'流程回执':'视界向导'}</small><p>{item.content}</p></article>)}</div>
      </>}
      <div className="guide-compose"><label htmlFor="guide-content">说出你的需求</label><Input.TextArea id="guide-content" maxLength={1000} rows={2} value={content} placeholder="例如：帮我检查刚上传的网站" onChange={e=>setContent(e.target.value)} onPressEnter={e=>{if(!e.shiftKey){e.preventDefault();if(!busy)send()}}}/><Button type="primary" icon={<SendOutlined/>} disabled={busy||!content.trim()} onClick={send}>发送需求</Button></div>
      <div className="guide-controls"><Space wrap><Button icon={<SoundOutlined/>} loading={speechLoading} onClick={speak}>{speechLoading?'正在加载中文声音':playing?'重新播报':'播报回复'}</Button>
        {playing&&<Button icon={paused?<PlayCircleOutlined/>:<PauseOutlined/>} onClick={()=>{if(paused)window.speechSynthesis.resume();else window.speechSynthesis.pause();setPaused(!paused)}}>{paused?'继续播报':'暂停播报'}</Button>}
        {(playing||speechLoading)&&<Button icon={<StopOutlined/>} onClick={stopSpeech}>停止播报</Button>}
        <Checkbox checked={prefs.data?.characterVisible!==false} onChange={e=>work(()=>preference({characterVisible:e.target.checked}))}>显示角色</Checkbox>
      </Space>{voiceMessage&&<p role="status">{voiceMessage}</p>}</div>
    </Drawer>
    {tour&&tourReady&&<Tour open steps={[{title:tour.title,description:<><p>{tour.description}</p>{!tourTarget&&<p>请按文字步骤操作；完成后重新打开向导继续。</p>}</>,target:tourTarget?()=>tourTarget:null,nextButtonProps:{children:'知道了'},prevButtonProps:{children:'上一步'}}]} onClose={()=>{setTour(undefined);launcher.current?.focus()}} onFinish={()=>{setTour(undefined);launcher.current?.focus()}}/>}
    <span className="guide-sr-status" role="status" aria-live="polite">{busy?'向导正在准备操作':error?'操作未完成':session?flowGuidance(session).title:''}</span>
  </>
}
function ResourceError({error,retry}:{error:unknown;retry:()=>void}){return error?<Alert type="warning" title="向导暂时无法读取数据" description={(error as Error).message} action={<Button onClick={retry}>重试</Button>}/>:null}

export function GuideActionCard({action,busy,onConfirm,onCancel}:{action:GuideAction;busy:boolean;onConfirm:(accepted:boolean)=>void;onCancel:()=>void}){
  const [review,setReview]=useState(false),preview=action.preview
  const pending=action.status==='PROPOSED'&&new Date(action.expiresAt).getTime()>Date.now()
  const items=Object.entries(preview).filter(([key,value])=>labelNames[key]&&value!==undefined).map(([key,value])=>({key,label:labelNames[key],children:value===null?'暂无':key==='purpose'?(value==='HOST'?'长期托管':'检测修复'):String(value)}))
  const config=preview.config as RuntimeConfigView|undefined,plan=preview.plan as {targetName?:string;targetUrl?:string;modules?:string[];pages?:string[];readOnlyApiCases?:Array<{method:string;path:string}>;performance?:Record<string,unknown>;flowSteps?:Array<{action:string;selector:string}>}|undefined
  return <Card size="small" className="guide-action-card" title={actionNames[action.actionType]||'待确认操作'} extra={<Tag>{pending?'等待确认':statusNames[action.status]||'已过期'}</Tag>}>
    <Descriptions column={1} size="small" items={items}/>
    {config&&<p>运行方式：{config.kind} · 数据库：{config.database} · 端口：{config.port}<br/>网站目录：{config.root||'根目录'}</p>}
    {config&&<details><summary>完整运行设置与检测配置</summary><dl>{Object.entries(config).map(([key,value])=><div key={key}><dt>{({kind:'网站类型',database:'数据库',port:'端口',root:'网站目录',buildCommand:'构建命令',startCommand:'启动命令',outputDirectory:'前端输出目录',frontendRoot:'独立前端目录',healthPath:'健康检查路径',apiPrefix:'接口前缀'} as Record<string,string>)[key]||key}</dt><dd>{String(value||'未设置')}</dd></div>)}</dl>{Boolean((preview.testPolicy as {load?:boolean})?.load)&&<p>包括既有方案的小规模压力检查。</p>}</details>}
    {plan&&<div className="guide-plan-preview"><p>网站：{plan.targetName} {plan.targetUrl}</p><p>检查模块：{plan.modules?.map(x=>({FRONTEND:'页面体验',SECURITY:'接口与权限',PERFORMANCE:'承载能力'} as Record<string,string>)[x]).join('、')}</p><p>页面：{plan.pages?.join('、')}</p>
      {!!plan.readOnlyApiCases?.length&&<ul>{plan.readOnlyApiCases.map((x,i)=><li key={i}>{x.method} {x.path}</li>)}</ul>}
      {plan.modules?.includes('PERFORMANCE')&&<dl>{Object.entries(plan.performance||{}).map(([key,value])=><div key={key}><dt>{({vus:'虚拟用户数',stageDurationSeconds:'阶段时长（秒）',p95Ms:'P95 阈值（毫秒）',errorRate:'错误率阈值',ratePerSecond:'每秒速率',path:'负载路径'} as Record<string,string>)[key]||key}</dt><dd>{Array.isArray(value)?value.join('、'):String(value)}</dd></div>)}</dl>}
      {!!plan.flowSteps?.length&&<ul>{plan.flowSteps.map((x,i)=><li key={i}>{x.action} · {x.selector}</li>)}</ul>}
    </div>}
    {typeof preview.diffText==='string'&&<><pre className="guide-diff" aria-label="向导修复差异">{preview.diffText}</pre>{pending&&<Checkbox checked={review} onChange={e=>setReview(e.target.checked)}>我已审阅差异，同意应用到新源码版本</Checkbox>}</>}
    {action.errorMessage&&<Alert type="warning" title="操作未完成" description={action.errorMessage}/>}
    {pending?<Space wrap><Button disabled={busy} onClick={onCancel}>取消操作</Button><Button type="primary" disabled={busy||(action.actionType==='APPLY_REPAIR'&&!review)} onClick={()=>onConfirm(review)}>确认执行</Button></Space>:action.status==='SUCCEEDED'?<p>真实业务回执已保存，可在当前流程查看结果。</p>:<p>此操作已经结束，继续操作请重新准备。</p>}
  </Card>
}
type RuntimeConfigView={kind:string;database:string;port:number;root:string;[key:string]:unknown}
