import {afterEach,expect,it,vi} from 'vitest'
import {prepareBrowserLogin} from './loginBootResources'
afterEach(()=>vi.unstubAllGlobals())
it('counts only completed module and image preparation tasks',async()=>{
  const images:Array<{onload:()=>void}>=[]
  vi.stubGlobal('Image',class {onload=null;onerror=null;src='';removeAttribute(){};constructor(){images.push(this as unknown as {onload:()=>void})}})
  let resolve!:()=>void
  const module=new Promise<void>(done=>{resolve=done})
  const notify=vi.fn()
  const complete=prepareBrowserLogin(()=>module,notify,new AbortController().signal)
  expect(notify).toHaveBeenLastCalledWith(0)
  images[2].onload();await Promise.resolve();await Promise.resolve()
  expect(notify).toHaveBeenLastCalledWith(5)
  resolve();await Promise.resolve();await Promise.resolve()
  expect(notify).toHaveBeenLastCalledWith(45)
  images[1].onload();await Promise.resolve();await Promise.resolve()
  expect(notify).toHaveBeenLastCalledWith(70)
  images[0].onload();await complete
  expect(notify).toHaveBeenLastCalledWith(100)
})
