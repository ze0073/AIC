import {useLayoutEffect,useRef} from 'react'
import {claimWorkspaceRailVisit,type RailConfig} from './workspaceRailState'

export function WorkspaceRail({config,userId}:{config:RailConfig;userId:string}) {
  const rootRef=useRef<HTMLDivElement>(null)
  const shouldAnimate=useRef<boolean|undefined>(undefined)
  useLayoutEffect(()=>{
    const root=rootRef.current
    if(!root)return
    // Retain the decision across Strict Mode's effect cleanup/setup cycle.
    if(shouldAnimate.current===undefined)shouldAnimate.current=config.firstVisitAnimation&&claimWorkspaceRailVisit(userId,config.section)
    if(!shouldAnimate.current)return
    const reduced=window.matchMedia('(prefers-reduced-motion: reduce)')
    const forced=window.matchMedia('(forced-colors: active)')
    if(reduced.matches||forced.matches||document.hidden||typeof root.animate!=='function')return
    const top=Math.max(0,Math.min(window.innerHeight,root.closest('.app-shell')?.querySelector('.topbar')?.getBoundingClientRect().bottom??0))
    let animation:Animation|undefined
    const finish=()=>{
      if(animation){animation.onfinish=null;animation.cancel()}
      delete root.dataset.railEntering
    }
    const hidden=()=>{if(document.hidden)finish()}
    root.dataset.railEntering='true'
    try{
      animation=root.animate([
        {clipPath:`inset(${top}px 0px ${window.innerHeight-top}px 0px)`},
        {clipPath:`inset(${top}px 0px 0px 0px)`},
      ],{duration:780,easing:'linear',fill:'both'})
      animation.onfinish=finish
    }catch{finish();return}
    reduced.addEventListener('change',finish)
    forced.addEventListener('change',finish)
    window.addEventListener('resize',finish,{passive:true})
    document.addEventListener('visibilitychange',hidden)
    return ()=>{
      reduced.removeEventListener('change',finish)
      forced.removeEventListener('change',finish)
      window.removeEventListener('resize',finish)
      document.removeEventListener('visibilitychange',hidden)
      finish()
    }
  },[config,userId])
  return <div className="workspace-accent" ref={rootRef} data-side={config.side} data-rail-section={config.section} aria-hidden="true"><img className="workspace-accent-art" src="/images/workspace-inspection.svg" alt=""/><span className="workspace-accent-name">WEB CHECK</span><span className="workspace-accent-caption">网页质检</span><span className="workspace-accent-mark"/></div>
}
