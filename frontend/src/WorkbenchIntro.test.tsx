import {act,cleanup,fireEvent,render} from '@testing-library/react'
import {afterEach,beforeEach,expect,it,vi} from 'vitest'
import {WorkbenchIntro} from './WorkbenchIntro'

let app:HTMLDivElement
let reduced=false
let forced=false
let preferenceChanged:()=>void
const effects:Array<{cancel:ReturnType<typeof vi.fn>;onfinish:(()=>void)|null}>=[]
const previousAnimate=Object.getOwnPropertyDescriptor(Element.prototype,'animate')
const previousDecode=Object.getOwnPropertyDescriptor(HTMLImageElement.prototype,'decode')
beforeEach(()=>{
  vi.useFakeTimers()
  reduced=false;forced=false;effects.length=0
  vi.stubGlobal('matchMedia',(query:string)=>({
    matches:query.includes('reduced-motion')?reduced:forced,
    addEventListener:(_:string,callback:()=>void)=>{if(query.includes('reduced-motion'))preferenceChanged=callback},
    removeEventListener:vi.fn(),
  }))
  Object.defineProperty(HTMLImageElement.prototype,'decode',{configurable:true,value:()=>Promise.resolve()})
  Object.defineProperty(Element.prototype,'animate',{configurable:true,value:()=>{
    const effect={cancel:vi.fn(),onfinish:null};effects.push(effect);return effect
  }})
  app=document.createElement('div');app.className='app-shell';document.body.append(app)
})
afterEach(()=>{
  cleanup();app.remove();document.body.style.overflow='';document.body.classList.remove('workbench-intro-active')
  vi.useRealTimers();vi.unstubAllGlobals()
  if(previousAnimate)Object.defineProperty(Element.prototype,'animate',previousAnimate)
  else Reflect.deleteProperty(Element.prototype,'animate')
  if(previousDecode)Object.defineProperty(HTMLImageElement.prototype,'decode',previousDecode)
  else Reflect.deleteProperty(HTMLImageElement.prototype,'decode')
})
it('skips the intro when reduced motion or forced colors is requested',()=>{
  reduced=true
  const first=render(<WorkbenchIntro/>);expect(document.querySelector('.workbench-intro')).toBeNull();first.unmount()
  reduced=false;forced=true
  render(<WorkbenchIntro/>);expect(document.querySelector('.workbench-intro')).toBeNull()
  expect(app.hasAttribute('inert')).toBe(false)
})
it('unlocks the workbench immediately on keyboard input',async()=>{
  render(<WorkbenchIntro/>)
  await act(async()=>{})
  expect(app.getAttribute('data-workbench-intro')).toBe('covering')
  expect(app.hasAttribute('inert')).toBe(true)
  fireEvent.keyDown(window,{key:'Tab'})
  expect(document.querySelector('.workbench-intro')).toBeNull()
  expect(app.hasAttribute('inert')).toBe(false)
  expect(document.body.style.overflow).toBe('')
  expect(effects.every(effect=>effect.cancel.mock.calls.length===1)).toBe(true)
})
it('restores prior attributes and body styles on interrupted navigation',async()=>{
  app.setAttribute('inert','');app.dataset.workbenchIntro='existing';document.body.style.overflow='auto'
  const view=render(<WorkbenchIntro/>);await act(async()=>{});view.unmount()
  expect(app.hasAttribute('inert')).toBe(true)
  expect(app.dataset.workbenchIntro).toBe('existing')
  expect(document.body.style.overflow).toBe('auto')
  expect(vi.getTimerCount()).toBe(0)
})
it('finishes immediately when motion preference changes',async()=>{
  render(<WorkbenchIntro/>);await act(async()=>{})
  act(()=>preferenceChanged())
  expect(document.querySelector('.workbench-intro')).toBeNull()
  expect(app.hasAttribute('data-workbench-intro')).toBe(false)
})
it('shows the workbench after the shutters and does not replay on data updates',async()=>{
  const view=render(<WorkbenchIntro/>);await act(async()=>{})
  act(()=>effects[0].onfinish?.())
  act(()=>vi.advanceTimersByTime(350))
  expect(app.dataset.workbenchIntro).toBe('handoff')
  act(()=>effects.at(-1)?.onfinish?.())
  expect(document.querySelector('.workbench-intro')).toBeNull()
  view.rerender(<WorkbenchIntro/>)
  expect(document.querySelector('.workbench-intro')).toBeNull()
  expect(app.hasAttribute('inert')).toBe(false)
})
it('has a bounded fallback if a browser animation never completes',async()=>{
  render(<WorkbenchIntro/>);await act(async()=>{})
  act(()=>vi.advanceTimersByTime(2200))
  expect(document.querySelector('.workbench-intro')).toBeNull()
  expect(app.hasAttribute('inert')).toBe(false)
})
