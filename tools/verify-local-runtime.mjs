// Read-only health/diagnostic acceptance; --model explicitly sends one consented help question.
import {readFile,writeFile,mkdir} from 'node:fs/promises';
import path from 'node:path';
const root=path.resolve(import.meta.dirname,'..'),config={};
for(const line of (await readFile(path.join(root,'.env'),'utf8')).split(/\r?\n/)){const match=line.match(/^([A-Za-z_][A-Za-z0-9_]*)=(.*)$/);if(match)config[match[1]]=match[2].trim().replace(/^(['"])(.*)\1$/,'$2');}
const base='http://127.0.0.1:8080',cookies=new Map();let csrf;
async function request(url,method='GET',body){
  if(method!=='GET'&&!csrf)csrf=await request('/api/auth/csrf');
  const headers={Cookie:[...cookies].map(([k,v])=>k+'='+v).join('; ')};if(csrf)headers[csrf.headerName]=csrf.token;
  if(body!==undefined)headers['Content-Type']='application/json';
  const response=await fetch(base+url,{method,headers,body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(65000)});
  for(const cookie of response.headers.getSetCookie()){const pair=cookie.split(';')[0],i=pair.indexOf('=');cookies.set(pair.slice(0,i),pair.slice(i+1));}
  if(!response.ok)throw new Error(url+' HTTP '+response.status);return response.json();
}
const result={health:await request('/actuator/health'),workerHealth:await(await fetch('http://127.0.0.1:18091/actuator/health')).json()};
await request('/api/auth/login','POST',{username:config.APP_INITIAL_ADMIN_USERNAME||'admin',password:config.APP_INITIAL_ADMIN_PASSWORD||'change-me-on-first-run'});csrf=null;
result.diagnostics=await request('/api/admin/system/status');
if(process.argv.includes('--model'))result.realModel=await request('/api/admin/system/model-check','POST',{aiConsent:true});
await mkdir(path.join(root,'output/stability'),{recursive:true});await writeFile(path.join(root,'output/stability/local-runtime.json'),JSON.stringify(result,null,2));
console.log(JSON.stringify({health:result.health.status,worker:result.workerHealth.status,dependencies:result.diagnostics.checks.map(({id,status})=>({id,status})),gateway:result.diagnostics.gatewayBindHost,model:result.realModel?.status},null,2));
