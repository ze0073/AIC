export type RailSection='home'|'sites'|'accounts'|'system'|'agent'
export type RailConfig={section:RailSection;side:'left'|'right';firstVisitAnimation:boolean}
const rails:Record<RailSection,RailConfig>={
  home:{section:'home',side:'left',firstVisitAnimation:false},
  sites:{section:'sites',side:'left',firstVisitAnimation:false},
  accounts:{section:'accounts',side:'right',firstVisitAnimation:true},
  system:{section:'system',side:'left',firstVisitAnimation:true},
  agent:{section:'agent',side:'right',firstVisitAnimation:true},
}
export function workspaceRailFor(path:string):RailConfig|null {
  if(path==='/')return rails.home
  if(path==='/sites'||path.startsWith('/sites/'))return rails.sites
  if(path==='/accounts')return rails.accounts
  if(path==='/system')return rails.system
  if(path==='/agent'||path.startsWith('/agent-tasks/')||path.startsWith('/assistant-sessions/')||/^\/projects\/[^/]+\/(agent|assistant)$/.test(path))return rails.agent
  return null
}
const storageKey='shijie:workspace-rail-visits:v1'
const fallbackVisits=new Set<string>()
export function resetWorkspaceRailVisits() {
  fallbackVisits.clear()
  try{window.sessionStorage.removeItem(storageKey)}catch{/* Private storage may be unavailable. */}
}
/** Claim a section once per login, including reloads of the current browser tab. */
export function claimWorkspaceRailVisit(userId:string,section:RailSection):boolean {
  const key=`${userId}:${section}`
  let visits:Set<string>
  try{
    const stored:unknown=JSON.parse(window.sessionStorage.getItem(storageKey)||'[]')
    visits=new Set([...fallbackVisits,...(Array.isArray(stored)?stored.filter((value):value is string=>typeof value==='string'):[])])
  }catch{visits=new Set(fallbackVisits)}
  if(visits.has(key))return false
  visits.add(key)
  fallbackVisits.add(key)
  try{window.sessionStorage.setItem(storageKey,JSON.stringify([...visits]))}catch{/* Keep the same rule in memory when storage is blocked. */}
  return true
}
