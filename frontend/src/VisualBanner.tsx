import type {ReactNode} from 'react'

export type VisualTheme='workbench'|'sites'|'accounts'|'system'|'agent'
const themes:Record<VisualTheme,{number:string;label:string}>={
  workbench:{number:'01',label:'QUALITY WORKSPACE'},
  sites:{number:'02',label:'WEBSITE DEPLOYMENT'},
  accounts:{number:'03',label:'IDENTITY MANAGEMENT'},
  system:{number:'04',label:'SYSTEM DIAGNOSTICS'},
  agent:{number:'05',label:'WEB INSPECTION'},
}

/** Decorative imagery is isolated from readable copy and interactive controls. */
export function VisualBanner({theme,title,description,actions,children,className='',compact=false}:{
  theme:VisualTheme;title?:ReactNode;description?:ReactNode;actions?:ReactNode;children?:ReactNode;className?:string;compact?:boolean
}) {
  const info=themes[theme]
  return <section className={`visual-banner ${compact?'visual-banner-compact':''} ${className}`} data-visual-theme={theme} data-motion-scene="true">
    <img className="visual-banner-image" src={`/images/visual-${theme}.webp`} width="1600" height="900" alt="" decoding="async" fetchPriority={compact?'auto':'high'}/>
    <div className="visual-banner-shade" aria-hidden="true"/>
    <div className="visual-banner-details" aria-hidden="true"><span>{info.number} / {info.label}</span><i className="visual-banner-ticks"/><i className="visual-banner-trace"/></div>
    {children??<><div className="visual-banner-copy"><span className="visual-banner-kicker">SHIJIE / {info.label}</span><h1>{title}</h1><p>{description}</p></div>{actions&&<div className="visual-banner-actions">{actions}</div>}</>}
  </section>
}

/** A stable wrapper: query updates do not remount or replay navigation motion. */
export function PrimaryPage({route,skipEntrance=false,children}:{route:string;skipEntrance?:boolean;children:ReactNode}) {
  return <div className="primary-page" data-page-route={route} data-page-intro-skip={skipEntrance?'true':undefined}>{children}</div>
}
