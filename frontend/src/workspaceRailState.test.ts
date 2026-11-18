import {afterEach,expect,it,vi} from 'vitest'
import {claimWorkspaceRailVisit,resetWorkspaceRailVisits,workspaceRailFor} from './workspaceRailState'
afterEach(()=>{vi.restoreAllMocks();resetWorkspaceRailVisits()})
it.each([
  ['/', 'left',false],['/sites','left',false],['/accounts','right',true],['/system','left',true],['/agent','right',true],
])('selects the requested rail and first-visit rule for %s',(path,side,animate)=>{
  const config=workspaceRailFor(path)
  expect(config?.side).toBe(side)
  expect(config?.firstVisitAnimation).toBe(animate)
})
it('keeps nested websites and intelligent-check routes in their parent sections',()=>{
  expect(workspaceRailFor('/sites/site-1')?.section).toBe('sites')
  expect(workspaceRailFor('/projects/p1/assistant')?.section).toBe('agent')
  expect(workspaceRailFor('/agent-tasks/task-1')?.side).toBe('right')
  expect(workspaceRailFor('/accounts-other')).toBeNull()
})
it('tracks each page and user independently',()=>{
  resetWorkspaceRailVisits()
  expect(claimWorkspaceRailVisit('u1','accounts')).toBe(true)
  expect(claimWorkspaceRailVisit('u1','accounts')).toBe(false)
  expect(claimWorkspaceRailVisit('u1','system')).toBe(true)
  expect(claimWorkspaceRailVisit('u2','accounts')).toBe(true)
})
it('keeps visits after module reload and resets them for a new login',async()=>{
  resetWorkspaceRailVisits()
  claimWorkspaceRailVisit('u1','accounts')
  vi.resetModules()
  const restored=await import('./workspaceRailState')
  expect(restored.claimWorkspaceRailVisit('u1','accounts')).toBe(false)
  restored.resetWorkspaceRailVisits()
  expect(restored.claimWorkspaceRailVisit('u1','accounts')).toBe(true)
})
it('uses an in-memory fallback when session storage cannot be written',()=>{
  resetWorkspaceRailVisits()
  vi.spyOn(Storage.prototype,'setItem').mockImplementation(()=>{throw new Error('Storage full')})
  expect(claimWorkspaceRailVisit('u1','agent')).toBe(true)
  expect(claimWorkspaceRailVisit('u1','agent')).toBe(false)
})
