// Reusable acceptance client. An explicit isolated loopback endpoint is required.
import {randomUUID} from 'node:crypto';
import {readFile} from 'node:fs/promises';
export class HostingClient {
  constructor(base){const url=new URL(base);if(url.hostname!=='127.0.0.1'||url.protocol!=='http:'||!url.port||url.port==='8080')throw new Error('Use an isolated loopback QA endpoint');this.base=url.origin;}
  cookies=new Map();csrf=null;
  async request(path,{method='GET',body,key=randomUUID(),raw=false}={}){
    if(!['GET','HEAD'].includes(method)&&!this.csrf)this.csrf=await this.request('/auth/csrf');
    const headers={Cookie:[...this.cookies].map(([k,v])=>k+'='+v).join('; ')};
    if(this.csrf)headers[this.csrf.headerName]=this.csrf.token;if(key)headers['Idempotency-Key']=key;
    if(body!==undefined&&!(body instanceof FormData)){headers['Content-Type']='application/json';body=JSON.stringify(body)}
    const response=await fetch(this.base+'/api'+path,{method,headers,body,signal:AbortSignal.timeout(65000)});
    for(const cookie of response.headers.getSetCookie()){const pair=cookie.split(';')[0],i=pair.indexOf('=');this.cookies.set(pair.slice(0,i),pair.slice(i+1));}
    if(!response.ok){const error=new Error(method+' '+path+' HTTP '+response.status);error.status=response.status;throw error;}
    if(response.status===204)return;if(raw)return new Uint8Array(await response.arrayBuffer());return response.json();
  }
  async login(username,password){const user=await this.request('/auth/login',{method:'POST',body:{username,password}});this.csrf=null;return user;}
  async upload(id,file){const form=new FormData();form.append('file',new Blob([await readFile(file)],{type:'application/zip'}),'source.zip');return this.request('/sites/'+id+'/source',{method:'POST',body:form});}
}
