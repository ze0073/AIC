import {idempotencyKey,request} from './api'
import type {GuideContext,GuideGoal,GuideSession} from './guideTypes'

export function createGuide(goal:GuideGoal,context:GuideContext={},assistance:'GUIDED'|'DELEGATED'='GUIDED'){
  return request<GuideSession>('/guide/sessions',{method:'POST',headers:{'Idempotency-Key':idempotencyKey()},body:JSON.stringify({goal,context,assistance,mode:'RULE',aiConsent:false})})
}
export function updateGuide(session:GuideSession,change:Partial<Pick<GuideSession,'context'|'draft'|'assistance'|'mode'|'aiConsent'|'status'>>){
  return request<GuideSession>('/guide/sessions/'+session.id,{method:'PATCH',body:JSON.stringify({revision:session.revision,...change})})
}
export function messageGuide(sessionId:string,content:string,actionType?:string,parameters?:Record<string,unknown>){
  return request<GuideSession>('/guide/sessions/'+sessionId+'/messages',{method:'POST',timeoutMs:60000,headers:{'Idempotency-Key':idempotencyKey()},body:JSON.stringify({content,actionType,parameters})})
}
export function openGuide(goal?:GuideGoal,context:GuideContext={}){
  window.dispatchEvent(new CustomEvent('open-website-guide',{detail:{goal,context}}))
}
export function contextForGoal(context:GuideContext,goal:GuideGoal):GuideContext{
  const common={projectId:context.projectId,siteId:context.siteId}
  if(goal==='DEPLOY')return common
  if(goal==='CHECK')return {...common,targetId:context.targetId,suiteVersionId:context.suiteVersionId}
  if(goal==='REPAIR')return {...common,runId:context.runId,issueId:context.issueId,versionId:context.versionId,repairId:context.repairId}
  if(goal==='REPORT')return {...common,runId:context.runId,snapshotId:context.snapshotId}
  return {}
}
