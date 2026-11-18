// Resume only a recorded, isolated guide acceptance database to verify cleanup retry.
import {readFile,readdir} from 'node:fs/promises';
import {createWriteStream} from 'node:fs';
import {spawn} from 'node:child_process';
import path from 'node:path';
const root=path.resolve(import.meta.dirname,'..'),output=path.resolve(process.argv[2]||'');
if(!output.startsWith(path.join(root,'output','guide')+path.sep))throw new Error('Expected recorded guide QA directory');
const state=JSON.parse(await readFile(path.join(output,'results.json'),'utf8'));if(!/^shijie_guide_qa_\d{17}$/.test(state.schema))throw new Error('Unexpected QA schema');
const base=new URL(state.base);if(base.hostname!=='127.0.0.1'||base.port==='8080')throw new Error('Unexpected QA endpoint');
const env={...process.env};for(const line of (await readFile(path.join(root,'.env'),'utf8')).split(/\r?\n/)){const match=line.match(/^([A-Za-z_][A-Za-z0-9_]*)=(.*)$/);if(match)env[match[1]]=match[2].trim().replace(/^(['"])(.*)\1$/,'$2');}
const docker='C:\\Program Files\\Docker\\Docker\\resources\\bin',pathKey=Object.keys(env).find(key=>key.toLowerCase()==='path'),systemPath=env[pathKey];delete env[pathKey];env.PATH=docker+path.delimiter+systemPath;
Object.assign(env,{SERVER_PORT:base.port,SERVER_BIND_HOST:'127.0.0.1',APP_HOSTING_GATEWAY_PORT:'18090',APP_STORAGE_ROOT:path.join(output,'storage'),SPRING_DATASOURCE_URL:env.SPRING_DATASOURCE_URL.replace(/\/[^/?]+(?=\?|$)/,'/'+state.schema),AI_ENABLED:'false',DOCKER_CONFIG:path.join(root,'.runtime/docker-cli')});
const jdk=(await readdir(path.join(root,'.runtime/jdk-21'))).find(name=>name.startsWith('jdk-'));
const app=spawn(path.join(root,'.runtime/jdk-21',jdk,'bin/java.exe'),['-Xmx512m','-jar',path.join(root,'backend/target-stability/accessibility-0.9.0-SNAPSHOT.jar'),'--app.hosting.worker-enabled=true'],{cwd:root,env,windowsHide:true});
app.stdout.pipe(createWriteStream(path.join(output,'cleanup.stdout.log')));app.stderr.pipe(createWriteStream(path.join(output,'cleanup.stderr.log')));
try{
  const deadline=Date.now()+90000;while(true){if(app.exitCode!==null)throw new Error('QA cleanup app exited');try{if((await fetch(base.origin+'/actuator/health')).ok)break;}catch{}if(Date.now()>deadline)throw new Error('QA cleanup boot timed out');await new Promise(resolve=>setTimeout(resolve,1000));}
  process.env.SHIJIE_QA_BASE=base.origin;await import('./cleanup-isolated-qa.mjs');
}finally{if(app.exitCode===null){app.kill();await new Promise(resolve=>app.once('exit',resolve));}}
