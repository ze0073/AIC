import {HostingClient} from './hosting-client.mjs';
import {writeFile} from 'node:fs/promises';
const client=new HostingClient(process.env.SHIJIE_QA_BASE||'');await client.login('guide_qa_admin','guide-qa-password-2026');
const sites=await client.request('/sites'),results=[];
for(const site of sites){
  await client.request('/sites/'+site.id,{method:'DELETE'});
  const deadline=Date.now()+120000;
  while(true){
    try{const current=await client.request('/sites/'+site.id);if(current.deletionStatus==='DELETE_FAILED')throw new Error('QA cleanup failed');}
    catch(error){if(error.status===404)break;throw error;}
    if(Date.now()>deadline)throw new Error('QA cleanup timed out');await new Promise(resolve=>setTimeout(resolve,1000));
  }
  results.push({siteId:site.id,removed:true});
}
await writeFile(new URL('../output/stability/qa-cleanup.json',import.meta.url),JSON.stringify(results,null,2));console.log(JSON.stringify(results));
