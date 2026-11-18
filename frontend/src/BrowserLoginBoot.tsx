import {useEffect,useState} from 'react'
import {loginRingGeometry,type BootPreparer} from './loginBootResources'
import {BrandLogo} from './BrandLogo'

export function BrowserLoginBoot({prepare,onReady}:{prepare:BootPreparer;onReady:()=>void}) {
  const [progress,setProgress]=useState(0)
  useEffect(()=>{
    const controller=new AbortController()
    const reduced=window.matchMedia('(prefers-reduced-motion: reduce)')
    let target=0,shown=0,ready=false,finished=false,frame=0
    let last=performance.now()
    let hold:ReturnType<typeof setTimeout>|undefined
    const complete=()=>{
      if(finished)return
      finished=true
      hold=setTimeout(()=>{if(!controller.signal.aborted)onReady()},reduced.matches?0:100)
    }
    const tick=(now:number)=>{
      if(controller.signal.aborted)return
      const delta=Math.min(64,Math.max(0,now-last));last=now
      shown=reduced.matches?target:Math.min(target,shown+delta*.16)
      setProgress(Math.floor(shown))
      if(ready&&shown>=100){complete();return}
      frame=requestAnimationFrame(tick)
    }
    frame=requestAnimationFrame(tick)
    void prepare(value=>{target=Math.max(target,Math.min(100,value))},controller.signal).then(()=>{ready=true;target=100},()=>{
      // Let the existing page boundary surface critical module failures.
      if(!controller.signal.aborted){finished=true;cancelAnimationFrame(frame);onReady()}
    })
    return ()=>{controller.abort();cancelAnimationFrame(frame);clearTimeout(hold)}
  },[prepare,onReady])
  const ring=loginRingGeometry(progress)
  return <main className="browser-login-boot" aria-busy="true">
    <img className="login-boot-background" src="/images/login-loading-grid.webp" alt="" fetchPriority="high" decoding="async"/>
    <div className="login-boot-shade" aria-hidden="true"/>
    <div className="brand login-boot-brand brand-integrated" role="img" aria-label="视界，AI 网站质量测试平台"><BrandLogo/></div>
    <section className="login-boot-center">
      <div className="login-boot-meter" role="progressbar" aria-label="登录页面准备进度" aria-valuemin={0} aria-valuemax={100} aria-valuenow={progress}>
        <svg viewBox="0 0 256 256" aria-hidden="true">
          <circle className="boot-ring-ticks" cx="128" cy="128" r="121"/>
          <circle className="boot-ring-track" cx="128" cy="128" r="106"/>
          <circle className="boot-ring-progress" cx="128" cy="128" r="106" pathLength="100" strokeDasharray="100" strokeDashoffset={ring.offset} transform="rotate(-90 128 128)"/>
          <circle className="boot-ring-point" cx={ring.x} cy={ring.y} r="5"/>
        </svg>
        <div className="login-boot-percent" aria-hidden="true"><strong>{progress}</strong><span>%</span></div>
      </div>
      <p>正在准备登录页面</p>
    </section>
  </main>
}
