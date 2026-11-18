import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { installMotionEffects } from './motionEffects'

let root: HTMLElement
let card: HTMLElement
let stop: (() => void) | undefined
let intersection: IntersectionObserverCallback
let reduced = false
let fine = true
let preferenceChanged: () => void
let frameIndex = 0
let frames: Map<number, FrameRequestCallback>
const observe = vi.fn(), unobserve = vi.fn(), disconnect = vi.fn()
const cancel = vi.fn()
const originalAnimate = Object.getOwnPropertyDescriptor(Element.prototype, 'animate')
const animate = vi.fn(function(this: Element,_frames?:Keyframe[],_options?:KeyframeAnimationOptions) {
  return { effect: { target: this }, cancel, onfinish: null, oncancel: null } as unknown as Animation
})

beforeEach(() => {
  vi.clearAllMocks()
  reduced = false
  fine = true
  frames = new Map()
  vi.stubGlobal('matchMedia', (query: string) => ({
    get matches() { return query.includes('reduced-motion') ? reduced : query.includes('forced-colors')?false:fine },
    addEventListener: (_: string, callback: () => void) => { if (query.includes('reduced-motion')) preferenceChanged = callback },
    removeEventListener: vi.fn(),
  }))
  vi.stubGlobal('IntersectionObserver', class {
    constructor(callback: IntersectionObserverCallback) { intersection = callback }
    observe = observe
    unobserve = unobserve
    disconnect = disconnect
  })
  vi.stubGlobal('requestAnimationFrame', (callback: FrameRequestCallback) => { frames.set(++frameIndex, callback); return frameIndex })
  vi.stubGlobal('cancelAnimationFrame', (id: number) => frames.delete(id))
  Object.defineProperty(Element.prototype, 'animate', { configurable: true, value: animate })
  root = document.createElement('div')
  root.id = 'root'
  card = document.createElement('div')
  card.className = 'ant-card project-card'
  card.textContent = 'Readable project title'
  card.getBoundingClientRect = () => ({ left: 0, top: 0, width: 400, height: 250 } as DOMRect)
  root.append(card)
  document.body.append(root)
})

afterEach(() => {
  stop?.()
  stop = undefined
  root?.remove()
  if (originalAnimate) Object.defineProperty(Element.prototype, 'animate', originalAnimate)
  else Reflect.deleteProperty(Element.prototype, 'animate')
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

function pointer(x: number, y: number, pointerType = 'mouse') {
  const event = new MouseEvent('pointermove', { bubbles: true, clientX: x, clientY: y })
  Object.defineProperty(event, 'pointerType', { value: pointerType })
  card.dispatchEvent(event)
}
function enter() {
  intersection([{
    target: card, isIntersecting: true, intersectionRatio: 1, time: 0,
    boundingClientRect: card.getBoundingClientRect(), intersectionRect: card.getBoundingClientRect(), rootBounds: null,
  }], {} as IntersectionObserver)
}
function flush() { const pending = [...frames.values()]; frames.clear(); pending.forEach(callback => callback(0)) }

it('keeps content readable before entering the viewport and hides decoration from assistive technology', () => {
  stop = installMotionEffects(root)
  expect(card.style.opacity).toBe('')
  expect(card.textContent).toContain('Readable project title')
  expect(card.querySelector('.motion-light')?.getAttribute('aria-hidden')).toBe('true')
  enter()
  expect(animate).toHaveBeenCalledTimes(1)
  animate.mock.results[0].value.onfinish?.(new Event('finish'))
  expect(unobserve).toHaveBeenCalledWith(card)
})

it('does not add card entrances behind the workbench intro',()=>{
  root.dataset.workbenchIntro='covering'
  stop=installMotionEffects(root)
  enter()
  expect(animate).not.toHaveBeenCalled()
  expect(card.querySelector('.motion-light')).not.toBeNull()
})

it('coalesces pointer updates into one frame and resets on scroll', () => {
  stop = installMotionEffects(root)
  pointer(30, 40)
  pointer(350, 150)
  expect(frames.size).toBe(1)
  flush()
  expect(card.style.getPropertyValue('--motion-x')).toBe('350.0px')
  expect(card.dataset.motionHover).toBe('true')
  window.dispatchEvent(new Event('scroll'))
  expect(card.hasAttribute('data-motion-hover')).toBe(false)
  expect(card.style.getPropertyValue('--motion-rx')).toBe('')
})

it('does not animate or tilt when reduced motion is enabled', () => {
  reduced = true
  stop = installMotionEffects(root)
  pointer(50, 80)
  enter()
  expect(frames.size).toBe(0)
  expect(animate).not.toHaveBeenCalled()
  expect(card.hasAttribute('data-motion-hover')).toBe(false)
})

it('immediately cancels running effects when the motion preference changes', () => {
  stop = installMotionEffects(root)
  enter()
  pointer(50, 80)
  reduced = true
  preferenceChanged()
  expect(cancel).toHaveBeenCalledTimes(1)
  expect(frames.size).toBe(0)
  expect(card.hasAttribute('data-motion-hover')).toBe(false)
})

it('ignores touch input and devices without a fine pointer', () => {
  stop = installMotionEffects(root)
  pointer(50, 80, 'touch')
  fine = false
  pointer(50, 80)
  expect(frames.size).toBe(0)
  expect(card.hasAttribute('data-motion-hover')).toBe(false)
})

it('registers asynchronously added cards without duplicate overlays and unobserves removed cards', async () => {
  stop = installMotionEffects(root)
  const added = document.createElement('div')
  added.className = 'ant-card'
  root.append(added)
  await new Promise(resolve => setTimeout(resolve, 0))
  expect(added.querySelectorAll('.motion-light')).toHaveLength(1)
  added.append(document.createElement('span'))
  await new Promise(resolve => setTimeout(resolve, 0))
  expect(added.querySelectorAll('.motion-light')).toHaveLength(1)
  added.remove()
  await new Promise(resolve => setTimeout(resolve, 0))
  expect(unobserve).toHaveBeenCalledWith(added)
})

it('cleans up listeners, overlays and pending frames before remounting', () => {
  stop = installMotionEffects(root)
  pointer(50, 80)
  stop()
  expect(frames.size).toBe(0)
  expect(root.querySelector('.motion-light')).toBeNull()
  expect(disconnect).toHaveBeenCalled()
  pointer(50, 80)
  expect(frames.size).toBe(0)
  stop = installMotionEffects(root)
  expect(card.querySelectorAll('.motion-light')).toHaveLength(1)
})

function primary(skip=false) {
  const page=document.createElement('div');page.dataset.pageRoute='/sites'
  if(skip)page.dataset.pageIntroSkip='true'
  const scene=document.createElement('section');scene.className='visual-banner';scene.dataset.motionScene='true'
  page.append(scene,card);root.append(page)
  return {page,scene}
}
it('runs navigation once per route, suppresses overlapping first-screen card entrances and ignores data updates',async()=>{
  const {page}=primary();stop=installMotionEffects(root)
  expect(animate).toHaveBeenCalledTimes(1)
  expect(animate.mock.calls[0][1]).toMatchObject({duration:300})
  enter();expect(animate).toHaveBeenCalledTimes(1)
  card.append(document.createElement('span'));await new Promise(resolve=>setTimeout(resolve,0))
  expect(animate).toHaveBeenCalledTimes(1)
  page.dataset.pageRoute='/accounts';await new Promise(resolve=>setTimeout(resolve,0))
  expect(animate).toHaveBeenCalledTimes(2)
})
it('does not add a navigation entrance during the login-success handoff',()=>{
  primary(true);stop=installMotionEffects(root)
  expect(animate).not.toHaveBeenCalled()
})
it('activates scenes only in the viewport and clears activity under reduced motion or removal',async()=>{
  const {scene}=primary(true);stop=installMotionEffects(root)
  const show=(visible:boolean)=>intersection([{target:scene,isIntersecting:visible,intersectionRatio:visible?1:0,time:0,boundingClientRect:scene.getBoundingClientRect(),intersectionRect:scene.getBoundingClientRect(),rootBounds:null}],{} as IntersectionObserver)
  show(true);expect(scene.dataset.sceneActive).toBe('true')
  show(false);expect(scene.dataset.sceneActive).toBeUndefined()
  show(true);reduced=true;preferenceChanged();expect(scene.dataset.sceneActive).toBeUndefined()
  reduced=false;show(true);scene.remove();await new Promise(resolve=>setTimeout(resolve,0))
  expect(scene.dataset.sceneActive).toBeUndefined()
})
it('leaves nested controls stable and never tilts table or form cards',()=>{
  const button=document.createElement('button');card.append(button)
  const table=document.createElement('div');table.className='ant-table';card.append(table)
  stop=installMotionEffects(root)
  pointer(40,80);flush()
  button.dispatchEvent(new MouseEvent('pointermove',{bubbles:true,clientX:40,clientY:80}))
  expect(card.dataset.motionHover).toBeUndefined()
  expect(card.dataset.motionTilt).toBeUndefined()
})

it('cancels a running card entrance when it leaves the viewport without replaying it',()=>{
  stop=installMotionEffects(root);enter()
  intersection([{target:card,isIntersecting:false} as unknown as IntersectionObserverEntry],{} as IntersectionObserver)
  expect(cancel).toHaveBeenCalledTimes(1)
  enter();expect(animate).toHaveBeenCalledTimes(1)
})
