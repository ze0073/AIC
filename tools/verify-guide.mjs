// HTTP acceptance in a generated database and storage directory. Never uses the user's projects.
import assert from 'node:assert/strict';
import {spawn,execFileSync} from 'node:child_process';
import {createServer} from 'node:http';
import {connect} from 'node:net';
import {createWriteStream} from 'node:fs';
import {mkdir,readFile,writeFile,readdir} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import path from 'node:path';
import {randomUUID} from 'node:crypto';

const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const stamp=new Date().toISOString().replace(/\D/g,'').slice(0,17);
const schema='shijie_guide_qa_'+stamp,output=path.join(root,'output','guide',stamp);
assert.match(schema,/^shijie_guide_qa_\d{17}$/);await mkdir(output,{recursive:true});
const env={...process.env};
for(const line of (await readFile(path.join(root,'.env'),'utf8')).split(/\r?\n/)){
  const m=line.match(/^([A-Za-z_][A-Za-z0-9_]*)=(.*)$/);if(m)env[m[1]]=m[2].trim().replace(/^(['"])(.*)\1$/,'$2');
}
const docker=process.platform==='win32'?'C:\\Program Files\\Docker\\Docker\\resources\\bin\\docker.exe':'docker';
env.DOCKER_CONFIG=path.join(root,'.runtime','docker-cli');const pathKey=Object.keys(env).find(k=>k.toLowerCase()==='path'),systemPath=env[pathKey];delete env[pathKey];env.PATH=path.dirname(docker)+path.delimiter+systemPath;
const jdk=(await readdir(path.join(root,'.runtime','jdk-21'))).filter(x=>x.startsWith('jdk-')).sort().at(-1);
const java=path.join(root,'.runtime','jdk-21',jdk,'bin','java.exe');
const port=Number(process.env.GUIDE_QA_PORT||18082),gateway=Number(process.env.GUIDE_QA_GATEWAY||18090),base='http://127.0.0.1:'+port;
const cases=[],modelRequests=[],deploymentIds=[];
let app,invalidModel=false,fixture;
const sleep=ms=>new Promise(resolve=>setTimeout(resolve,ms));
async function wait(fn,predicate,timeout=180000){const until=Date.now()+timeout;let value;do{value=await fn();if(predicate(value))return value;await sleep(1500)}while(Date.now()<until);throw new Error('Timed out: '+JSON.stringify(value).slice(0,500))}
function sql(command){return execFileSync(docker,['exec','shijie-mysql','sh','-c','MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -N -e "$1"','guide-qa',command],{env,encoding:'utf8',windowsHide:true}).trim()}
async function check(name,fn){const started=Date.now();try{const evidence=await fn();cases.push({name,passed:true,durationMs:Date.now()-started,...evidence});console.log('PASS '+name)}catch(e){cases.push({name,passed:false,error:e.message});throw e}}
class Client {
  cookie='';csrf=null;jar=new Map();
  async request(method,url,body,key=randomUUID()){
    if(!['GET','HEAD'].includes(method)&&!this.csrf)this.csrf=await this.request('GET','/api/auth/csrf');
    const headers={cookie:this.cookie};if(this.csrf)headers[this.csrf.headerName]=this.csrf.token;
    if(key)headers['Idempotency-Key']=key;
    let payload;if(body instanceof FormData)payload=body;else if(body!==undefined){headers['content-type']='application/json';payload=JSON.stringify(body)}
    const response=await fetch(base+url,{method,headers,body:payload,signal:AbortSignal.timeout(65000)});
    for(const cookie of response.headers.getSetCookie()){const pair=cookie.split(';')[0],equal=pair.indexOf('=');this.jar.set(pair.slice(0,equal),pair)}
    this.cookie=[...this.jar.values()].join('; ');
    if(!response.ok){const text=await response.text();const e=new Error(method+' '+url+' '+response.status+' '+text.slice(0,500));e.status=response.status;throw e}
    if(response.status===204)return;
    if(response.headers.get('content-type')?.includes('json'))return response.json();
    return new Uint8Array(await response.arrayBuffer());
  }
  async login(username){const user=await this.request('POST','/api/auth/login',{username,password:'guide-qa-password-2026'});this.csrf=null;return user}
}
const admin=new Client(),other=new Client();
async function create(goal,context={},assistance='DELEGATED'){return admin.request('POST','/api/guide/sessions',{goal,context,assistance,mode:'RULE',aiConsent:false})}
async function patch(s,change){return admin.request('PATCH','/api/guide/sessions/'+s.id,{revision:s.revision,...change})}
async function prepare(s,type,parameters={}){const result=await admin.request('POST','/api/guide/sessions/'+s.id+'/messages',{content:'请准备 '+type,actionType:type,parameters});return [result,result.actions.find(a=>a.status==='PROPOSED')]}
async function confirm(a,reviewAccepted=false,key=randomUUID()){return admin.request('POST','/api/guide/actions/'+a.id+'/confirm',{actionHash:a.actionHash,reviewAccepted},key)}
async function expectError(fn,status){await assert.rejects(fn,e=>e.status===status)}
function actionId(id){assert.match(id,/^[0-9A-Z]{26}$/);return id}
function removeQaNetwork(id){assert.match(id,/^[0-9A-Z]{26}$/);const name='sj-net-'+id.toLowerCase();try{const rows=JSON.parse(execFileSync(docker,['network','inspect',name],{env,encoding:'utf8',windowsHide:true,stdio:['ignore','pipe','ignore']}));if(!Object.keys(rows[0].Containers||{}).length)execFileSync(docker,['network','rm',name],{env,windowsHide:true,stdio:'ignore'})}catch{}}
async function stop(){if(app&&app.exitCode===null){for(const id of deploymentIds){try{await admin.request('POST','/api/deployments/'+id+'/stop');removeQaNetwork(id)}catch{}}}if(app&&app.exitCode===null){app.kill();await new Promise(resolve=>app.once('exit',resolve))}if(fixture)await new Promise(resolve=>fixture.close(resolve))}

try {
  for(const candidate of [port,gateway]){
    const occupied=await new Promise(resolve=>{const socket=connect({host:'127.0.0.1',port:candidate});socket.once('connect',()=>{socket.destroy();resolve(true)});socket.once('error',()=>resolve(false))});assert.ok(!occupied,'QA port is occupied');
  }
  const dbuser=env.SPRING_DATASOURCE_USERNAME||'shijie';assert.match(dbuser,/^[a-zA-Z0-9_]+$/);
  sql("CREATE DATABASE "+schema+"; GRANT ALL ON "+schema+".* TO '"+dbuser+"'@'%';");
  fixture=createServer(async(req,res)=>{let raw='';for await(const chunk of req)raw+=chunk;const body=JSON.parse(raw);const context=JSON.parse(body.messages[1].content);modelRequests.push(context);res.writeHead(200,{'content-type':'application/json'});res.end(JSON.stringify({choices:[{message:{content:JSON.stringify({goal:invalidModel?'DELETE_SITE':'HELP',advice:'请根据真实任务状态继续处理。',evidenceRefs:context.evidenceIds||[]})}}]}))});
  await new Promise(resolve=>fixture.listen(0,'127.0.0.1',resolve));
  const childEnv={...env,SERVER_PORT:String(port),SERVER_BIND_HOST:'127.0.0.1',APP_HOSTING_GATEWAY_PORT:String(gateway),
    SPRING_DATASOURCE_URL:env.SPRING_DATASOURCE_URL.replace(/\/[^/?]+(?=\?|$)/,'/'+schema),APP_STORAGE_ROOT:path.join(output,'storage'),
    APP_INITIAL_ADMIN_USERNAME:'guide_qa_admin',APP_INITIAL_ADMIN_PASSWORD:'guide-qa-password-2026',
    AI_ENABLED:'true',AI_PROVIDER:'qwen',AI_BASE_URL:'http://127.0.0.1:'+fixture.address().port,AI_API_KEY:'qa-local-fixture',AI_MODEL:'local-fixture',AI_MAX_RETRIES:'0',GIT_SSL_CAINFO:path.join(root,'.runtime','git-system-ca.pem')};
  app=spawn(java,['-Xmx768m','-jar',process.env.GUIDE_QA_JAR||path.join(root,'backend','target','accessibility-0.9.0-SNAPSHOT.jar'),'--app.hosting.worker-enabled=true'],{cwd:root,env:childEnv,windowsHide:true});
  app.stdout.pipe(createWriteStream(path.join(output,'app.stdout.log')));app.stderr.pipe(createWriteStream(path.join(output,'app.stderr.log')));
  await wait(async()=>{if(app.exitCode!==null)throw new Error('QA app exited');try{return(await fetch(base+'/actuator/health')).ok}catch{return false}},Boolean,90000);
  const user=await admin.login('guide_qa_admin');
  const otherUser=await admin.request('POST','/api/users',{username:'guide_qa_other',displayName:'Other QA',password:'guide-qa-password-2026'});await other.login('guide_qa_other');
  await admin.request('POST','/api/users/'+user.id+'/quota',{projectLimit:20,environmentLimit:8,buildLimit:2});
  await writeFile(path.join(root,'output','guide','latest.json'),JSON.stringify({base,output,schema,pid:app.pid},null,2));
  await check('V18 migration and per-user preferences',async()=>{
    assert.equal(sql("SELECT version FROM "+schema+".flyway_schema_history ORDER BY installed_rank DESC LIMIT 1"),'18');
    assert.equal((await admin.request('GET','/api/guide/preferences')).welcomeSeen,false);
    await admin.request('PUT','/api/guide/preferences',{welcomeSeen:true,voiceEnabled:false});assert.equal((await other.request('GET','/api/guide/preferences')).welcomeSeen,false);
  });
  await check('scoped concurrent idempotency rejects changed payloads',async()=>{
    const key=randomUUID(),body={name:'Stability QA project',description:'isolated'};
    const [first,second]=await Promise.all([admin.request('POST','/api/projects',body,key),admin.request('POST','/api/projects',body,key)]);assert.equal(first.id,second.id);
    await expectError(()=>admin.request('POST','/api/projects',{...body,name:'changed'},key),409);
    const separate=await other.request('POST','/api/projects',body,key);assert.notEqual(first.id,separate.id);
    await other.request('POST','/api/projects/'+separate.id+'/members',{userId:user.id,role:'ADMIN'});
    await admin.request('POST','/api/projects/'+first.id+'/members',{userId:otherUser.id,role:'MEMBER'});
    await expectError(()=>admin.request('PATCH','/api/projects/'+first.id+'/members/'+user.id,{role:'VIEWER'}),409);
    const notification=randomUUID().replaceAll('-','').slice(0,26).toUpperCase();
    sql("INSERT INTO "+schema+".notifications(id,project_id,user_id,type,title,payload,created_at) VALUES('"+notification+"','"+actionId(first.id)+"',NULL,'QA','QA shared notification','{}',NOW())");
    await admin.request('POST','/api/projects/'+first.id+'/notifications/'+notification+'/read');
    assert.ok((await admin.request('GET','/api/projects/'+first.id+'/notifications'))[0].readAt);
    assert.ok(!(await other.request('GET','/api/projects/'+first.id+'/notifications'))[0].readAt);
    await admin.request('DELETE','/api/projects/'+first.id+'/members/'+otherUser.id);
    await expectError(()=>other.request('GET','/api/projects/'+first.id),403);
    const job=await admin.request('DELETE','/api/projects/'+first.id);
    assert.equal((await wait(()=>admin.request('GET','/api/projects/cleanup/'+job.id),v=>['COMPLETED','FAILED'].includes(v.status))).status,'COMPLETED');
    return {projectId:first.id,cleanupId:job.id};
  });
  await check('account disable and reset revoke existing sessions',async()=>{
    await expectError(()=>admin.request('POST','/api/users/'+user.id+'/status',{enabled:false}),409);
    await admin.request('POST','/api/users/'+otherUser.id+'/status',{enabled:false});
    await expectError(()=>other.request('GET','/api/auth/me'),401);
    await admin.request('POST','/api/users/'+otherUser.id+'/status',{enabled:true});other.csrf=null;await other.login('guide_qa_other');
    await admin.request('POST','/api/users/'+otherUser.id+'/password',{newPassword:'guide-qa-password-2026'});
    await expectError(()=>other.request('GET','/api/auth/me'),401);other.csrf=null;await other.login('guide_qa_other');
  });
  let deploySession=await create('DEPLOY'),site,sourceVersion,deployed,run;
  await check('personal session isolation and idempotent create confirmation',async()=>{
    let a;[deploySession,a]=await prepare(deploySession,'CREATE_SITE',{name:'Guide QA website',purpose:'HOST'});
    await expectError(()=>other.request('GET','/api/guide/sessions/'+deploySession.id),404);
    await expectError(()=>other.request('POST','/api/guide/actions/'+a.id+'/confirm',{actionHash:a.actionHash,reviewAccepted:false}),404);
    deploySession=await confirm(a);site=deploySession.resources.site;
    const again=await confirm(a);assert.equal(again.context.siteId,site.id);
    assert.equal((await admin.request('GET','/api/sites')).length,1);
  });
  await check('draft persistence, mode switching and chat confirmation boundary',async()=>{
    deploySession=await patch(deploySession,{draft:{name:'preserved',purpose:'HOST'},assistance:'GUIDED'});
    assert.equal((await admin.request('GET','/api/guide/sessions/'+deploySession.id)).draft.name,'preserved');
    deploySession=await patch(deploySession,{assistance:'DELEGATED'});assert.equal(deploySession.draft.name,'preserved');
    const before=(await admin.request('GET','/api/sites/'+site.id)).deployments.length;
    deploySession=await admin.request('POST','/api/guide/sessions/'+deploySession.id+'/messages',{content:'确认'});
    assert.equal((await admin.request('GET','/api/sites/'+site.id)).deployments.length,before);
    await expectError(()=>patch({...deploySession,revision:0},{assistance:'GUIDED'}),409);
  });
  const zip=path.join(output,'source.zip');
  execFileSync('python',['-c',"import zipfile,sys; z=zipfile.ZipFile(sys.argv[1],'w'); z.writestr('index.html','<!doctype html><html><head><meta charset=\"UTF-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>Guide QA</title></head><body><main><h1>Guide QA website</h1><p>Deployment and repair acceptance fixture.</p></main></body></html>'); z.close()",zip],{windowsHide:true});
  await check('multipart replay, queued scan cancellation and cleanup',async()=>{
    const project=await admin.request('POST','/api/projects',{name:'Queued scan QA'}),key=randomUUID();
    const upload=()=>{const form=new FormData();form.append('file',new Blob([execFileSync('python',['-c','import sys;sys.stdout.buffer.write(open(sys.argv[1],"rb").read())',zip])],{type:'application/zip'}),'source.zip');return form};
    const first=await admin.request('POST','/api/projects/'+project.id+'/upload',upload(),key);
    assert.equal((await admin.request('POST','/api/projects/'+project.id+'/upload',upload(),key)).id,first.id);
    const body={pages:[],enableAi:false,aiConsent:false,enableKeyboardTest:false,rules:['R004']},scanKey=randomUUID();
    const scan=await admin.request('POST','/api/projects/'+project.id+'/scans',body,scanKey);
    assert.equal((await admin.request('POST','/api/projects/'+project.id+'/scans',body,scanKey)).id,scan.id);
    await admin.request('POST','/api/scans/'+scan.id+'/cancel');
    assert.equal((await wait(()=>admin.request('GET','/api/scans/'+scan.id),v=>['CANCELLED','COMPLETED','FAILED'].includes(v.status))).status,'CANCELLED');
    const job=await admin.request('DELETE','/api/projects/'+project.id);
    assert.equal((await wait(()=>admin.request('GET','/api/projects/cleanup/'+job.id),v=>['COMPLETED','FAILED'].includes(v.status))).status,'COMPLETED');
  });
  await check('ZIP import and expired preview',async()=>{
    const form=new FormData();form.append('file',new Blob([await readFile(zip)],{type:'application/zip'}),'source.zip');
    site=await admin.request('POST','/api/sites/'+site.id+'/source',form);sourceVersion=site.sourceVersionId;
    let a;[deploySession,a]=await prepare(deploySession,'CONFIGURE_DEPLOY');
    sql("UPDATE "+schema+".guide_actions SET expires_at=NOW()-INTERVAL 1 MINUTE WHERE id='"+actionId(a.id)+"'");
    const expired=await confirm(a);assert.equal(expired.actions.find(x=>x.id===a.id).status,'EXPIRED');
    assert.equal(expired.resources.site.deployments.length,0);
  });
  await check('stale source settings prevent executing a frozen preview',async()=>{
    let a;[deploySession,a]=await prepare(await admin.request('GET','/api/guide/sessions/'+deploySession.id),'CONFIGURE_DEPLOY');
    const config={...site.config,healthPath:'/changed'};await admin.request('POST','/api/sites/'+site.id+'/configuration',{config,testPolicy:{write:false,active:false,load:false}});
    await expectError(()=>confirm(a),409);
    await admin.request('POST','/api/sites/'+site.id+'/configuration',{config:site.config,testPolicy:{write:false,active:false,load:false}});
  });
  await check('real deploy automatically checks once and restores progress',async()=>{
    let a;[deploySession,a]=await prepare(await admin.request('GET','/api/guide/sessions/'+deploySession.id),'CONFIGURE_DEPLOY');
    deploySession=await confirm(a);deployed=deploySession.resources.deployment;deploymentIds.push(deployed.id);
    const completed=await wait(()=>admin.request('GET','/api/guide/sessions/'+deploySession.id),s=>['RUNNING','FAILED'].includes(s.resources.deployment?.status),300000);
    assert.equal(completed.resources.deployment.status,'RUNNING',completed.resources.deployment.errorMessage);
    assert.ok(completed.resources.run);run=completed.resources.run;assert.equal(run.status,'COMPLETED');
    const again=await confirm(a);assert.equal(again.resources.deployment.runId,run.id);
    assert.equal(again.resources.site.deployments.length,1);deploySession=completed;
  });
  await check('full backup contains validated source and SHA256 manifest',async()=>{
    const file=path.join(output,'full-backup.zip');await writeFile(file,await admin.request('GET','/api/sites/'+site.id+'/full-backup?deploymentId='+deployed.id));
    const proof=JSON.parse(execFileSync('python',[path.join(root,'tools','restore-backup.py'),'inspect',file],{encoding:'utf8',windowsHide:true}));
    assert.equal(proof.integrity,'SHA256_VERIFIED');assert.equal(proof.hasSource,true);return proof;
  });
  await check('fixed CHECK adapter, replay and task result restoration',async()=>{
    let s=await create('CHECK',{projectId:run.projectId,targetId:run.targetId,suiteVersionId:run.suiteVersionId}),a;
    [s,a]=await prepare(s,'START_AGENT_TASK',{goal:'检查页面质量'});s=await confirm(a);const taskId=s.context.taskId;
    s=await wait(()=>admin.request('GET','/api/guide/sessions/'+s.id),v=>['COMPLETED','FAILED','PARTIAL','CANCELLED','INTERRUPTED'].includes(v.resources.task.status));
    assert.equal(s.resources.task.status,'COMPLETED');assert.ok(s.resources.taskRuns.length);
    assert.equal((await confirm(a)).context.taskId,taskId);
  });
  let issue,repairSession,repair;
  await check('rule repair uses source diff and auto-deploys a single retest',async()=>{
    const candidates=run.issues.filter(x=>x.source==='RULE');
    for(const candidate of candidates){const file=await admin.request('GET','/api/issues/'+candidate.id+'/repair-file?versionId='+sourceVersion+'&path=index.html');if(file.templateRule==='R004'){issue=candidate;break}}
    assert.ok(issue,'Expected a language rule finding');
    repairSession=await create('REPAIR',{siteId:site.id,projectId:site.projectId,runId:run.id,issueId:issue.id,versionId:sourceVersion});
    let a;[repairSession,a]=await prepare(repairSession,'PREPARE_REPAIR',{path:'index.html',mode:'TEMPLATE',language:'en'});
    repairSession=await confirm(a);repair=repairSession.resources.repair;assert.equal(repair.status,'PENDING');
    [repairSession,a]=await prepare(repairSession,'APPLY_REPAIR');assert.ok(a.preview.diffText.includes('lang="en"'));
    await expectError(()=>confirm(a,false),400);
    repairSession=await confirm(a,true);assert.ok(repairSession.resources.repair.newVersionId);deploymentIds.push(repairSession.resources.deployment.id);
    repairSession=await wait(()=>admin.request('GET','/api/guide/sessions/'+repairSession.id),s=>s.resources.repair?.status==='VERIFIED'||s.resources.deployment?.status==='FAILED',300000);
    assert.equal(repairSession.resources.repair.status,'VERIFIED',JSON.stringify(repairSession.resources.deployment));
    const d=repairSession.resources.deployment;const duplicate=await confirm(a,true);
    assert.equal(duplicate.resources.deployment.id,d.id);assert.equal(duplicate.resources.site.deployments.filter(x=>x.repairId===repair.id).length,1);await admin.request('POST','/api/deployments/'+deployed.id+'/stop');removeQaNetwork(deployed.id);
  });
  await check('formal update uses verified candidate and its own confirmation',async()=>{
    const d=repairSession.resources.deployment;
    let publish=repairSession,a;
    [publish,a]=await prepare(publish,'PUBLISH_SITE',{deploymentId:d.id});publish=await confirm(a);deploymentIds.push(publish.resources.deployment.id);
    publish=await wait(()=>admin.request('GET','/api/guide/sessions/'+publish.id),s=>s.resources.deployment?.published||s.resources.deployment?.status==='FAILED',300000);
    assert.equal(publish.resources.deployment.published,true,publish.resources.deployment.errorMessage);
  });
  await check('report interpretation and both exports use one frozen snapshot',async()=>{
    let report=await create('REPORT',{projectId:site.projectId,runId:run.id}),a;[report,a]=await prepare(report,'CREATE_REPORT_SNAPSHOT');report=await confirm(a);
    const snapshotId=report.context.snapshotId;assert.ok(snapshotId);const frozen=await admin.request('GET','/api/test-run-report-snapshots/'+snapshotId);
    assert.deepEqual(report.resources.run,JSON.parse(frozen.payload).run);
    const json=await admin.request('GET','/api/test-run-report-snapshots/'+snapshotId+'/export?format=json');
    assert.deepEqual(json,JSON.parse(frozen.payload));
    const pdf=await admin.request('GET','/api/test-run-report-snapshots/'+snapshotId+'/export?format=pdf');assert.ok(pdf.length>1000);
  });
  await check('optional Qwen validates output and falls back without losing settings',async()=>{
    let s=await create('HELP');s=await patch(s,{mode:'QWEN',aiConsent:true,draft:{name:'preserved'}});
    invalidModel=true;s=await admin.request('POST','/api/guide/sessions/'+s.id+'/messages',{content:'帮我了解平台'});
    assert.ok(s.messages.some(m=>m.content.includes('规则流程')));assert.equal(s.draft.name,'preserved');
    const count=modelRequests.length;s=await admin.request('POST','/api/guide/sessions/'+s.id+'/messages',{content:'Token=private-canary'});
    assert.equal(modelRequests.length,count);assert.ok(!JSON.stringify(s).includes('private-canary'));invalidModel=false;
  });
  await check('repository import uses dedicated form and records a pinned commit',async()=>{
    let s=await create('DEPLOY'),a;[s,a]=await prepare(s,'CREATE_SITE',{name:'Guide repository QA',purpose:'TEST'});s=await confirm(a);
    const repoSite=s.context.siteId;await admin.request('POST','/api/sites/'+repoSite+'/repository',{url:'https://github.com/octocat/Hello-World',branch:'master',username:'git'});
    const imported=await wait(()=>admin.request('GET','/api/guide/sessions/'+s.id),v=>['COMPLETED','FAILED'].includes(v.resources.site?.sourceJob?.status),240000);
    assert.equal(imported.resources.site.sourceJob.status,'COMPLETED',imported.resources.site.sourceJob.errorMessage);
    assert.ok(imported.resources.site.repositoryCommit);assert.ok(imported.resources.site.sourceVersionId);
  });
  await check('legacy project assistant remains usable after migration',async()=>{
    const old=await admin.request('POST','/api/projects/'+site.projectId+'/assistant-sessions',{mode:'RULE',aiConsent:false});assert.ok(old.id);
    const oldRead=await admin.request('GET','/api/assistant-sessions/'+old.id);assert.equal(oldRead.projectId,site.projectId);
  });
  await check('restart resumes persisted queue and marks interrupted modules',async()=>{
    const currentSite=await admin.request('GET','/api/sites/'+site.id),current=await admin.request('GET','/api/deployments/'+currentSite.currentDeploymentId);
    const original=actionId(current.runId),pending=randomUUID().replaceAll('-','').slice(0,26).toUpperCase(),interrupted=randomUUID().replaceAll('-','').slice(0,26).toUpperCase();
    app.kill();await new Promise(resolve=>app.once('exit',resolve));
    for(const [id,status] of [[pending,'PENDING'],[interrupted,'RUNNING']]){
      sql("INSERT INTO "+schema+".test_runs(id,project_id,target_id,suite_version_id,status,cancel_requested,config_snapshot,tool_snapshot,created_by,created_at,started_at) SELECT '"+id+"',project_id,target_id,suite_version_id,'"+status+"',FALSE,config_snapshot,tool_snapshot,created_by,NOW(),"+(status==='RUNNING'?'NOW()':'NULL')+" FROM "+schema+".test_runs WHERE id='"+original+"'");
      sql("INSERT INTO "+schema+".test_run_modules(id,run_id,module_type,status,started_at) VALUES('"+randomUUID().replaceAll('-','').slice(0,26).toUpperCase()+"','"+id+"','FRONTEND','"+status+"',"+(status==='RUNNING'?'NOW()':'NULL')+")");
    }
    app=spawn(java,['-Xmx768m','-jar',process.env.GUIDE_QA_JAR||path.join(root,'backend','target','accessibility-0.9.0-SNAPSHOT.jar'),'--app.hosting.worker-enabled=true'],{cwd:root,env:childEnv,windowsHide:true});
    app.stdout.pipe(createWriteStream(path.join(output,'app.stdout.log'),{flags:'a'}));app.stderr.pipe(createWriteStream(path.join(output,'app.stderr.log'),{flags:'a'}));
    await wait(async()=>{try{return(await fetch(base+'/actuator/health')).ok}catch{return false}},Boolean,90000);
    admin.cookie='';admin.jar.clear();admin.csrf=null;await admin.login('guide_qa_admin');
    const resumed=await wait(()=>admin.request('GET','/api/test-runs/'+pending),value=>['COMPLETED','PARTIAL','FAILED','CANCELLED'].includes(value.status));assert.equal(resumed.status,'COMPLETED');
    const recovered=await admin.request('GET','/api/test-runs/'+interrupted);assert.equal(recovered.status,'PARTIAL');assert.equal(recovered.modules[0].status,'FAILED');
    await writeFile(path.join(root,'output','guide','latest.json'),JSON.stringify({base,output,schema,pid:app.pid},null,2));return {resumed:pending,interrupted};
  });
  await writeFile(path.join(output,'results.json'),JSON.stringify({schema,base,cases,modelFixture:true},null,2));
  console.log('GUIDE_QA_READY '+base+' output='+output);
  if(process.env.GUIDE_QA_HOLD==='1')await new Promise(resolve=>{process.once('SIGTERM',resolve);process.once('SIGINT',resolve)});
}catch(e){await writeFile(path.join(output,'results.json'),JSON.stringify({schema,base,cases,error:e.message},null,2));console.error(e.stack);process.exitCode=1}
finally {await stop()}
