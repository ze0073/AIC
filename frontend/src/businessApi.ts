import {idempotencyKey, request} from './api'
import type {Deployment, RuntimeConfig, Site} from './Websites'
import type {Repair} from './IssueRepair'

// Shared by the original pages and the guide; secrets stay in dedicated form requests.
export async function uploadWebsiteSource(siteId:string,file:File,key=idempotencyKey()){
  if(file.size===0)throw new Error('文件内容为空，无法导入。请先保存网页内容或重新生成压缩包，再上传。')
  const body=new FormData();body.append('file',file)
  return request<Site>('/sites/'+siteId+'/source',{method:'POST',headers:{'Idempotency-Key':key},body,timeoutMs:60000})
}
export function importWebsiteRepository(siteId:string,values:Record<string,string>,key=idempotencyKey()){
  return request<Site>('/sites/'+siteId+'/repository',{method:'POST',headers:{'Idempotency-Key':key},body:JSON.stringify(values)})
}
export async function configureAndDeployWebsite(siteId:string,values:RuntimeConfig&{write?:boolean;active?:boolean;load?:boolean;suiteVersionId?:string},environmentText:string){
  let environment:Record<string,string>|undefined
  if(environmentText.trim()){
    environment={}
    for(const line of environmentText.split('\n').filter(line=>line.trim())){
      const equal=line.indexOf('=');if(equal<1)throw new Error('请按 名称=值 填写运行设置，每行一项')
      environment[line.slice(0,equal).trim()]=line.slice(equal+1)
    }
  }
  const {write,active,load,suiteVersionId,...config}=values
  await request('/sites/'+siteId+'/configuration',{method:'POST',body:JSON.stringify({config,environment,suiteVersionId,testPolicy:{write:!!write,active:!!active,load:!!load}})})
  const deployment=await request<Deployment>('/sites/'+siteId+'/deployments',{method:'POST',headers:{'Idempotency-Key':idempotencyKey()},body:'{}'})
  window.dispatchEvent(new CustomEvent('guide-business-result',{detail:{siteId,deploymentId:deployment.id,versionId:deployment.sourceVersionId}}))
  return deployment
}
export async function applyIssueRepair(repairId:string,reviewAccepted:boolean){
  const repair=await request<Repair>('/issue-repairs/'+repairId+'/apply',{method:'POST',body:JSON.stringify({reviewAccepted})})
  window.dispatchEvent(new CustomEvent('guide-business-result',{detail:{issueId:repair.issueId,repairId,versionId:repair.newVersionId}}))
  return repair
}
