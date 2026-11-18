import {useLayoutEffect, useRef, useState} from 'react'
import {createPortal} from 'react-dom'

/** A short industrial title screen, followed by a mechanical shutter handoff. */
export function WorkbenchIntro() {
  const [visible,setVisible]=useState(()=>!window.matchMedia('(prefers-reduced-motion: reduce)').matches&&!window.matchMedia('(forced-colors: active)').matches)
  const rootRef=useRef<HTMLDivElement>(null)
  const finishRef=useRef<()=>void>(()=>{})
  useLayoutEffect(()=>{
    const root=rootRef.current
    const app=document.querySelector<HTMLElement>('.app-shell')
    if(!visible || !root || !app)return
    const reduced=window.matchMedia('(prefers-reduced-motion: reduce)')
    const forced=window.matchMedia('(forced-colors: active)')
    const animations:Animation[]=[]
    const timers:number[]=[]
    const previous={inert:app.getAttribute('inert'),phase:app.getAttribute('data-workbench-intro'),overflow:document.body.style.overflow,active:document.body.classList.contains('workbench-intro-active')}
    let disposed=false
    let started=false
    const restoreAttribute=(name:string,value:string|null)=>{if(value===null)app.removeAttribute(name);else app.setAttribute(name,value)}
    const restore=()=>{
      restoreAttribute('inert',previous.inert)
      restoreAttribute('data-workbench-intro',previous.phase)
      document.body.style.overflow=previous.overflow
      if(!previous.active)document.body.classList.remove('workbench-intro-active')
    }
    const finish=()=>{
      if(disposed)return
      root.style.display='none'
      restore()
      setVisible(false)
    }
    finishRef.current=finish
    const hide=()=>{if(document.hidden)finish()}
    const cleanup=()=>{
      disposed=true
      timers.forEach(timer=>window.clearTimeout(timer))
      animations.forEach(animation=>{animation.onfinish=null;animation.cancel()})
      window.removeEventListener('keydown',finish,true)
      window.removeEventListener('wheel',finish,true)
      window.removeEventListener('touchstart',finish,true)
      document.removeEventListener('visibilitychange',hide)
      reduced.removeEventListener('change',finish)
      forced.removeEventListener('change',finish)
      restore()
    }
    if(reduced.matches || forced.matches || document.hidden || typeof root.animate!=='function') {
      finish()
      return cleanup
    }
    app.setAttribute('inert','')
    app.dataset.workbenchIntro='covering'
    document.body.style.overflow='hidden'
    document.body.classList.add('workbench-intro-active')
    window.addEventListener('keydown',finish,{capture:true})
    window.addEventListener('wheel',finish,{capture:true,passive:true})
    window.addEventListener('touchstart',finish,{capture:true,passive:true})
    document.addEventListener('visibilitychange',hide)
    reduced.addEventListener('change',finish)
    forced.addEventListener('change',finish)

    const shutters=()=>{
      if(disposed)return
      root.dataset.phase='shutter'
      try {
        const panels=root.querySelectorAll<HTMLElement>('.intro-shutter')
        panels.forEach((panel,index)=>{
          const animation=panel.animate([
            {transform:'translateX(-101%)',offset:0,easing:'cubic-bezier(.2,.7,.25,1)'},
            {transform:'translateX(0)',offset:.38},
            {transform:'translateX(0)',offset:.58,easing:'cubic-bezier(.65,0,.85,.3)'},
            {transform:'translateX(101%)',offset:1},
          ],{duration:720,delay:index*24,fill:'both'})
          animations.push(animation)
          if(index===panels.length-1)animation.onfinish=finish
        })
        // All three shutters overlap here; swap scenes while the view is covered.
        timers.push(window.setTimeout(()=>{
          if(disposed)return
          root.dataset.phase='handoff'
          app.dataset.workbenchIntro='handoff'
          document.body.style.overflow=previous.overflow
        },350))
      }catch{finish()}
    }
    const start=()=>{
      if(disposed || started)return
      started=true
      root.dataset.phase='fill'
      try {
        const bar=root.querySelector<HTMLElement>('.intro-progress')!
        const animation=bar.animate([{transform:'scaleY(0)'},{transform:'scaleY(1)'}],{duration:780,easing:'linear',fill:'forwards'})
        animations.push(animation)
        animation.onfinish=shutters
      }catch{finish()}
    }
    const image=root.querySelector<HTMLImageElement>('.intro-background')!
    if(typeof image.decode==='function')void image.decode().then(start,finish)
    else start()
    timers.push(window.setTimeout(start,200))
    // Background tabs or interrupted browser animations must never trap the page.
    timers.push(window.setTimeout(finish,2200))
    return cleanup
  },[visible])
  if(!visible)return null
  return createPortal(<div className="workbench-intro" ref={rootRef} data-phase="waiting" aria-hidden="true" onClick={event=>{event.preventDefault();event.stopPropagation();finishRef.current()}}>
    <div className="intro-scene">
      <img className="intro-background" src="/images/industrial-outpost.webp" alt="" fetchPriority="high" decoding="async"/>
      <div className="intro-shade"/>
      <div className="intro-headline">看见问题。<br/><em>交付更好的网站。</em></div>
      <div className="intro-progress"/>
    </div>
    <div className="intro-shutters">{[0,1,2].map(index=><div className="intro-shutter" key={index} style={{top:`${index*100/3}%`}}><span className="shutter-calibration"/><span className="shutter-rivet"/><span className="shutter-seam"/></div>)}</div>
  </div>,document.body)
}
