import {afterEach,expect,it,vi} from 'vitest'
import {uploadWebsiteSource} from './businessApi'
import {request} from './api'

vi.mock('./api',()=>({request:vi.fn(),idempotencyKey:()=> 'upload-test'}))
afterEach(()=>vi.mocked(request).mockReset())

it.each(['123.html','empty.zip'])('rejects an empty %s before sending any request',async name=>{
  const file=new File([],name)
  await expect(uploadWebsiteSource('site',file)).rejects.toThrow('文件内容为空，无法导入')
  expect(request).not.toHaveBeenCalled()
})

it('still sends nonempty website files through the existing upload request',async()=>{
  const file=new File(['<!doctype html><html lang="zh-CN"><title>测试</title></html>'],'123.html',{type:'text/html'})
  vi.mocked(request).mockResolvedValue({id:'site'})
  await expect(uploadWebsiteSource('site',file)).resolves.toEqual({id:'site'})
  expect(request).toHaveBeenCalledOnce()
  const [path,options]=vi.mocked(request).mock.calls[0]
  expect(path).toBe('/sites/site/source')
  expect((options?.body as FormData).get('file')).toBe(file)
})
