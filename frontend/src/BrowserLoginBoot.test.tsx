import {act,cleanup,render,screen} from '@testing-library/react'
import {afterEach,beforeEach,expect,it,vi} from 'vitest'
import {BrowserLoginBoot} from './BrowserLoginBoot'
import {loginRingGeometry,type BootPreparer} from './loginBootResources'
let frames:Map<number,FrameRequestCallback>,id:number,clock:number
beforeEach(()=>{
  vi.useFakeTimers();frames=new Map();id=0;clock=performance.now()
  vi.stubGlobal('matchMedia',()=>({matches:false}))
  vi.stubGlobal('requestAnimationFrame',(callback:FrameRequestCallback)=>{frames.set(++id,callback);return id})
  vi.stubGlobal('cancelAnimationFrame',(key:number)=>frames.delete(key))
})
afterEach(()=>{cleanup();vi.useRealTimers();vi.unstubAllGlobals()})
function step(){clock+=64;const callbacks=[...frames.values()];frames.clear();act(()=>callbacks.forEach(callback=>callback(clock)))}
it('keeps the displayed ring at actual readiness and only finishes once all resources are ready',async()=>{
  let notify!:(progress:number)=>void,resolve!:()=>void
  const prepare:BootPreparer=callback=>{notify=callback;return new Promise(done=>{resolve=done})}
  const ready=vi.fn()
  render(<BrowserLoginBoot prepare={prepare} onReady={ready}/>)
  notify(50);for(let n=0;n<16;n++)step()
  expect(screen.getByRole('progressbar').getAttribute('aria-valuenow')).toBe('50')
  expect(document.querySelector('.boot-ring-progress')?.getAttribute('stroke-dashoffset')).toBe('50')
  expect(ready).not.toHaveBeenCalled()
  await act(async()=>resolve());for(let n=0;n<16;n++)step()
  expect(screen.getByRole('progressbar').getAttribute('aria-valuenow')).toBe('100')
  act(()=>vi.advanceTimersByTime(100))
  expect(ready).toHaveBeenCalledTimes(1)
})
it('aborts resource preparation and animation frames on unmount',()=>{
  let signal!:AbortSignal
  const prepare:BootPreparer=(_,value)=>{signal=value;return new Promise(()=>{})}
  const view=render(<BrowserLoginBoot prepare={prepare} onReady={vi.fn()}/>)
  view.unmount()
  expect(signal.aborted).toBe(true)
  expect(frames.size).toBe(0)
})
it.each([[0,128,22,100],[25,234,128,75],[50,128,234,50],[75,22,128,25],[100,128,22,0]])('places the clockwise point and ring length at %s percent',(percent,x,y,offset)=>{
  const ring=loginRingGeometry(percent)
  expect(ring.x).toBeCloseTo(x)
  expect(ring.y).toBeCloseTo(y)
  expect(ring.offset).toBe(offset)
})
