import {readFile,mkdir,writeFile} from 'node:fs/promises';
import {gzipSync} from 'node:zlib';
import path from 'node:path';
const root=path.resolve(import.meta.dirname,'..'),dist=path.join(root,'frontend/dist');
const manifest=JSON.parse(await readFile(path.join(dist,'.vite/manifest.json'),'utf8'));
const entry=Object.keys(manifest).find(key=>manifest[key].isEntry),login=Object.keys(manifest).find(key=>manifest[key].name==='PublicLogin');
if(!entry||!login)throw new Error('Missing entry or login chunk');
const visited=new Set();function include(key){if(visited.has(key))return;visited.add(key);for(const dependency of manifest[key].imports||[])include(dependency);}include(entry);include(login);
const files=[...new Set([...visited].map(key=>manifest[key].file).filter(file=>file.endsWith('.js')))];let bytes=0,gzipBytes=0;
for(const file of files){const content=await readFile(path.join(dist,file));bytes+=content.length;gzipBytes+=gzipSync(content).length;}
const baselineGzipBytes=523540,result={screen:'unauthenticated login',files,bytes,gzipBytes,baselineGzipBytes,reductionPercent:Number((100*(1-gzipBytes/baselineGzipBytes)).toFixed(2))};
if(result.reductionPercent<30)throw new Error('Initial JS reduction is below the planned 30%');
await mkdir(path.join(root,'output/stability'),{recursive:true});await writeFile(path.join(root,'output/stability/frontend-size.json'),JSON.stringify(result,null,2));console.log(JSON.stringify(result,null,2));
