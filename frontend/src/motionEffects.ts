const cards = '.ant-card, .recent-run, .home-overview > div, .capability-tabs > button'
const revealTargets = `${cards}, .quality-guide-steps > article, .hero-panel, .agent-landing, .assistant-hero`
const tiltTargets = '.project-card, .hosting-site-card'
const targets = `[data-page-route], [data-motion-scene], ${revealTargets}`

/** One delegated pointer listener and observer manage cards, navigation and scene activity. */
export function installMotionEffects(root: HTMLElement): () => void {
  const reduced = window.matchMedia('(prefers-reduced-motion: reduce)')
  const finePointer = window.matchMedia('(hover: hover) and (pointer: fine)')
  const forced = window.matchMedia('(forced-colors: active)')
  const registered = new WeakSet<HTMLElement>()
  const revealed = new WeakSet<HTMLElement>()
  const routes = new WeakMap<HTMLElement,string>()
  const routeAnimations = new WeakMap<HTMLElement,Animation>()
  const scenes = new Map<HTMLElement,boolean>()
  const animations = new Set<Animation>()
  let active: HTMLElement | null = null
  let bounds: DOMRect | null = null
  let frame = 0
  let point = { x: 0, y: 0 }
  let revealIndex = 0
  const allowed = () => !reduced.matches && !forced.matches && !document.hidden
  const resetPointer = () => {
    if (frame) cancelAnimationFrame(frame)
    frame = 0
    active?.removeAttribute('data-motion-hover')
    for (const name of ['--motion-x', '--motion-y', '--motion-rx', '--motion-ry']) active?.style.removeProperty(name)
    active = null; bounds = null
  }
  const cancelAnimations = () => {
    for (const animation of animations) animation.cancel()
    animations.clear()
    root.querySelectorAll<HTMLElement>('[data-route-entering]').forEach(el=>delete el.dataset.routeEntering)
  }
  const updateScenes = () => {
    for(const [scene,visible] of scenes) {
      if(visible&&!reduced.matches&&!forced.matches&&finePointer.matches&&window.innerWidth>760)scene.dataset.sceneActive='true'
      else delete scene.dataset.sceneActive
    }
  }
  const registerRoute = (element:HTMLElement) => {
    const route=element.dataset.pageRoute
    if(!route||routes.get(element)===route||!element.querySelector('.visual-banner'))return
    routes.set(element,route)
    for(const animation of animations)if((animation.effect as KeyframeEffect|null)?.target===element)animation.cancel()
    delete element.dataset.routeEntering
    if(!allowed()||element.dataset.pageIntroSkip==='true'||element.closest('[data-workbench-intro]')||typeof element.animate!=='function')return
    element.dataset.routeEntering='true'
    try {
      const animation=element.animate([{opacity:.35,translate:'0 12px'},{opacity:1,translate:'0 0'}],{duration:300,easing:'cubic-bezier(.22,1,.36,1)'})
      animations.add(animation)
      routeAnimations.set(element,animation)
      animation.onfinish=animation.oncancel=()=>{
        animations.delete(animation)
        if(routeAnimations.get(element)===animation){routeAnimations.delete(element);delete element.dataset.routeEntering}
      }
    }catch {delete element.dataset.routeEntering}
  }
  const intersections = typeof IntersectionObserver === 'function' ? new IntersectionObserver(entries => {
    let visibleIndex = 0
    for (const entry of entries) {
      const element = entry.target as HTMLElement
      if(element.matches('[data-motion-scene]')) {
        if(root.contains(element))scenes.set(element,entry.isIntersecting)
        continue
      }
      if (!entry.isIntersecting) {
        for(const animation of animations)if((animation.effect as KeyframeEffect|null)?.target===element)animation.cancel()
        continue
      }
      if(revealed.has(element))continue
      revealed.add(element)
      if (!allowed() || !root.contains(element) || typeof element.animate !== 'function' || element.closest('[data-workbench-intro], [data-route-entering]') || element.matches('.visual-banner')) {intersections?.unobserve(element);continue}
      const animation = element.animate([
        { opacity: .35, translate: '0 8px' }, { opacity: 1, translate: '0 0' },
      ], { duration: 320, delay: Math.min(visibleIndex++, 4) * 45, easing: 'cubic-bezier(.22,1,.36,1)' })
      animations.add(animation)
      animation.onfinish = animation.oncancel = () => { animations.delete(animation);intersections?.unobserve(element) }
    }
    updateScenes()
  }, { threshold: .06 }) : null
  const register = (element: HTMLElement) => {
    const page=element.closest<HTMLElement>('[data-page-route]')
    if(page)registerRoute(page)
    if (registered.has(element)) return
    registered.add(element)
    if(element.matches('[data-motion-scene]')) {
      scenes.set(element,false); intersections?.observe(element); return
    }
    if(element.matches('[data-page-route]'))return
    element.dataset.motionOrder = String(revealIndex++ % 5)
    if (element.matches(cards)) {
      const light = document.createElement('span')
      light.className = 'motion-light'; light.setAttribute('aria-hidden', 'true')
      const sweep = document.createElement('span'); sweep.className = 'motion-sweep'
      light.append(sweep); element.append(light)
      if (element.matches(tiltTargets)&&!element.querySelector('form,.ant-table')) element.dataset.motionTilt = 'true'
    }
    intersections?.observe(element)
  }
  const visit = (node: Node, callback: (element: HTMLElement) => void) => {
    if (!(node instanceof HTMLElement)) return
    if (node.matches(targets)) callback(node)
    node.querySelectorAll<HTMLElement>(targets).forEach(callback)
  }
  visit(root, register)
  const mutations = new MutationObserver(records => {
    for (const record of records) {
      if(record.type==='attributes'&&record.target instanceof HTMLElement)visit(record.target,register)
      record.removedNodes.forEach(node => visit(node, element => {intersections?.unobserve(element);scenes.delete(element);delete element.dataset.sceneActive}))
      record.addedNodes.forEach(node => visit(node, register))
    }
    if (active && !root.contains(active)) resetPointer()
    for (const animation of animations) {
      const target = (animation.effect as KeyframeEffect | null)?.target
      if (target instanceof Element && !root.contains(target)) animation.cancel()
    }
  })
  mutations.observe(root, { childList: true, subtree: true,attributes:true,attributeFilter:['data-page-route'] })
  const move = (event: PointerEvent) => {
    if (!allowed() || !finePointer.matches || window.innerWidth<=760 || event.pointerType === 'touch') return
    const target = event.target instanceof Element ? event.target.closest<HTMLElement>(cards) : null
    if (!target || !root.contains(target)) { resetPointer(); return }
    const control=event.target instanceof Element?event.target.closest('button,a,input,select,textarea,[role="button"]'):null
    if(control&&control!==target){resetPointer();return}
    if (target !== active) {
      resetPointer(); active = target; bounds = target.getBoundingClientRect(); target.dataset.motionHover = 'true'
    }
    point = { x: event.clientX, y: event.clientY }
    if (frame) return
    frame = requestAnimationFrame(() => {
      frame = 0
      if (!active || !bounds || !allowed()) return
      const x = Math.max(0, Math.min(bounds.width, point.x - bounds.left))
      const y = Math.max(0, Math.min(bounds.height, point.y - bounds.top))
      active.style.setProperty('--motion-x', `${x.toFixed(1)}px`)
      active.style.setProperty('--motion-y', `${y.toFixed(1)}px`)
      active.style.setProperty('--motion-rx', `${((.5 - y / Math.max(bounds.height, 1)) * 4).toFixed(2)}deg`)
      active.style.setProperty('--motion-ry', `${((x / Math.max(bounds.width, 1) - .5) * 4).toFixed(2)}deg`)
    })
  }
  const preferenceChange = () => { resetPointer(); cancelAnimations();updateScenes() }
  const resize = () => { resetPointer();updateScenes() }
  const visibilityChange = () => {
    root.dataset.motionPaused = String(document.hidden)
    if (document.hidden) preferenceChange()
  }
  visibilityChange()
  root.addEventListener('pointermove', move, { passive: true })
  root.addEventListener('pointerleave', resetPointer)
  window.addEventListener('scroll', resetPointer, { passive: true, capture: true })
  window.addEventListener('resize', resize, { passive: true })
  document.addEventListener('visibilitychange', visibilityChange)
  reduced.addEventListener('change', preferenceChange)
  finePointer.addEventListener('change', preferenceChange)
  forced.addEventListener('change', preferenceChange)
  return () => {
    mutations.disconnect(); intersections?.disconnect(); resetPointer(); cancelAnimations()
    root.removeEventListener('pointermove', move);root.removeEventListener('pointerleave', resetPointer)
    window.removeEventListener('scroll', resetPointer, true);window.removeEventListener('resize', resize)
    document.removeEventListener('visibilitychange', visibilityChange)
    reduced.removeEventListener('change', preferenceChange);finePointer.removeEventListener('change', preferenceChange);forced.removeEventListener('change', preferenceChange)
    scenes.forEach((_,scene)=>delete scene.dataset.sceneActive);scenes.clear()
    root.querySelectorAll('.motion-light').forEach(element => element.remove())
    root.querySelectorAll<HTMLElement>('[data-motion-order]').forEach(element => {delete element.dataset.motionOrder;delete element.dataset.motionTilt})
    delete root.dataset.motionPaused
  }
}
