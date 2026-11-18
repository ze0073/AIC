import {StrictMode} from 'react'
import {cleanup,render} from '@testing-library/react'
import {afterEach,beforeEach,expect,it,vi} from 'vitest'
import {WorkspaceRail} from './WorkspaceRail'
import {resetWorkspaceRailVisits,workspaceRailFor} from './workspaceRailState'
const previousAnimate=Object.getOwnPropertyDescriptor(Element.prototype,'animate')
const cancel=vi.fn()
const animate=vi.fn(()=>({cancel,onfinish:null}))
let reduced=false
let app:HTMLDivElement
beforeEach(()=>{
  resetWorkspaceRailVisits();reduced=false;vi.clearAllMocks()
  vi.stubGlobal('matchMedia',(query:string)=>({matches:query.includes('reduced-motion')&&reduced,addEventListener:vi.fn(),removeEventListener:vi.fn()}))
  Object.defineProperty(Element.prototype,'animate',{configurable:true,value:animate})
  app=document.createElement('div');app.className='app-shell';document.body.append(app)
})
afterEach(()=>{
  cleanup();app.remove();resetWorkspaceRailVisits();vi.unstubAllGlobals()
  if(previousAnimate)Object.defineProperty(Element.prototype,'animate',previousAnimate)
  else Reflect.deleteProperty(Element.prototype,'animate')
})
it('keeps the websites rail static on its first visit',()=>{
  render(<WorkspaceRail userId="u1" config={workspaceRailFor('/sites')!}/>,{container:app})
  expect(animate).not.toHaveBeenCalled()
  expect(app.querySelector('.workspace-accent')?.getAttribute('data-side')).toBe('left')
})
it('plays a page entrance once and keeps subsequent visits static',()=>{
  const first=render(<WorkspaceRail userId="u1" config={workspaceRailFor('/accounts')!}/>,{container:app})
  expect(animate).toHaveBeenCalledTimes(1)
  first.unmount()
  const next=document.createElement('div');next.className='app-shell';document.body.append(next)
  render(<WorkspaceRail userId="u1" config={workspaceRailFor('/accounts')!}/>,{container:next})
  expect(animate).toHaveBeenCalledTimes(1)
  next.remove()
})
it('does not lose the first entrance during Strict Mode setup',()=>{
  render(<StrictMode><WorkspaceRail userId="u1" config={workspaceRailFor('/system')!}/></StrictMode>,{container:app})
  expect(app.querySelector('.workspace-accent')?.getAttribute('data-rail-entering')).toBe('true')
})
it('does not animate under reduced motion',()=>{
  reduced=true
  render(<WorkspaceRail userId="u1" config={workspaceRailFor('/agent')!}/>,{container:app})
  expect(animate).not.toHaveBeenCalled()
})
it('cancels and clears clipping state when navigating away',()=>{
  const view=render(<WorkspaceRail userId="u1" config={workspaceRailFor('/accounts')!}/>,{container:app})
  const rail=app.querySelector('.workspace-accent')
  view.unmount()
  expect(cancel).toHaveBeenCalled()
  expect(rail?.hasAttribute('data-rail-entering')).toBe(false)
})
