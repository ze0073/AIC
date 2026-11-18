export type BootPreparer=(notify:(progress:number)=>void,signal:AbortSignal)=>Promise<void>
export function preloadLoginImage(url:string,signal:AbortSignal):Promise<void> {
  return new Promise((resolve,reject)=>{
    const image=new Image()
    let timer:ReturnType<typeof setTimeout>
    const clear=()=>{clearTimeout(timer);image.onload=null;image.onerror=null;signal.removeEventListener('abort',abort)}
    const complete=()=>{clear();resolve()}
    const abort=()=>{clear();image.removeAttribute('src');reject(new DOMException('Aborted','AbortError'))}
    image.onload=()=>{if(typeof image.decode==='function')void image.decode().then(complete,complete);else complete()}
    image.onerror=complete // Decorative images can use their CSS background fallback.
    signal.addEventListener('abort',abort,{once:true})
    if(signal.aborted){abort();return}
    timer=setTimeout(complete,4000)
    image.src=url
  })
}
/** Percent of required login preparation completed; never advances on a timer. */
export async function prepareBrowserLogin(loadModule:()=>Promise<unknown>,notify:(progress:number)=>void,signal:AbortSignal) {
  const tasks=[
    {weight:40,run:loadModule},
    {weight:30,run:()=>preloadLoginImage('/images/login-loading-grid.webp',signal)},
    {weight:25,run:()=>preloadLoginImage('/images/industrial-outpost.webp',signal)},
    {weight:5,run:()=>preloadLoginImage('/favicon.svg',signal)},
  ]
  let completed=0
  notify(0)
  await Promise.all(tasks.map(async task=>{
    await task.run()
    if(signal.aborted)return
    completed+=task.weight
    notify(completed)
  }))
}
export function loginRingGeometry(progress:number) {
  const percent=Math.max(0,Math.min(100,progress))
  const angle=-Math.PI/2+Math.PI*2*percent/100
  return {percent,offset:100-percent,x:128+106*Math.cos(angle),y:128+106*Math.sin(angle)}
}
