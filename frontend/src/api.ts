const API = '/api'
let csrf: Promise<{ headerName:string; token:string }> | undefined

export class ApiError extends Error {
  constructor(public code: string, message: string, public traceId?: string) { super(message) }
}

type ApiRequestInit = RequestInit & { timeoutMs?: number }

export async function request<T>(path: string, init?: ApiRequestInit): Promise<T> {
  const { timeoutMs, ...options } = init || {}
  if (timeoutMs === undefined) return performRequest<T>(path, options)
  const controller = new AbortController()
  let timedOut = false
  const abort = () => controller.abort()
  options.signal?.addEventListener('abort', abort, { once:true })
  if (options.signal?.aborted) abort()
  const timer = setTimeout(() => { timedOut = true; controller.abort() }, timeoutMs)
  try {
    return await abortable(performRequest<T>(path, { ...options, signal:controller.signal }), controller.signal)
  } catch (error) {
    if (controller.signal.aborted) {
      csrf=undefined
      throw new ApiError(timedOut ? 'REQUEST_TIMEOUT' : 'REQUEST_CANCELLED', timedOut ? '请求超时，请稍后重试。' : '已取消等待')
    }
    throw error
  } finally {
    clearTimeout(timer)
    options.signal?.removeEventListener('abort', abort)
  }
}

function abortable<T>(promise: Promise<T>, signal: AbortSignal): Promise<T> {
  return new Promise((resolve, reject) => {
    const abort = () => reject(new DOMException('Request aborted', 'AbortError'))
    signal.addEventListener('abort', abort, { once:true })
    if (signal.aborted) abort()
    promise.then(resolve, reject).finally(() => signal.removeEventListener('abort', abort))
  })
}

async function performRequest<T>(path: string, init: RequestInit): Promise<T> {
  const headers = new Headers(init?.headers)
  if (init?.body && !(init.body instanceof FormData)) headers.set('Content-Type', 'application/json')
  const method=(init?.method||'GET').toUpperCase()
  if (!['GET','HEAD','OPTIONS'].includes(method)) {
    csrf ||= fetch(`${API}/auth/csrf`,{credentials:'same-origin'}).then(async response=>{
      if(!response.ok)throw new ApiError('CSRF_UNAVAILABLE','无法建立安全会话')
      return response.json()
    }).catch(error => { csrf=undefined; throw error })
    const token=await csrf
    headers.set(token.headerName,token.token)
  }
  const response = await fetch(`${API}${path}`, { ...init, headers, credentials:'same-origin' })
  if (!response.ok) {
    const error = await response.json().catch(() => ({ code: 'HTTP_ERROR', message: `请求失败（${response.status}）` }))
    if(response.status===401)window.dispatchEvent(new Event('auth-required'))
    throw new ApiError(error.code, error.message, error.traceId)
  }
  if (response.status === 204) return undefined as T
  return response.json() as Promise<T>
}

export const idempotencyKey = () => crypto.randomUUID()

export async function downloadFile(path:string, filename:string):Promise<void> {
  const controller=new AbortController()
  const timer=setTimeout(()=>controller.abort(),60_000)
  try {
    const response=await abortable(fetch(`${API}${path}`,{credentials:'same-origin',signal:controller.signal}),controller.signal)
    if(!response.ok){
      const error=await abortable(response.json().catch(()=>({code:'DOWNLOAD_FAILED',message:'下载暂时没有完成，请重试'})),controller.signal)
      if(response.status===401)window.dispatchEvent(new Event('auth-required'))
      throw new ApiError(error.code,error.message,error.traceId)
    }
    const blob=await abortable(response.blob(),controller.signal)
    if(!blob.size)throw new ApiError('DOWNLOAD_EMPTY','源码包为空，请重试')
    const url=URL.createObjectURL(blob),link=document.createElement('a')
    link.href=url;link.download=filename;document.body.appendChild(link);link.click();link.remove()
    setTimeout(()=>URL.revokeObjectURL(url),1000)
  }catch(error){
    if(controller.signal.aborted)throw new ApiError('DOWNLOAD_TIMEOUT','下载等待超时，请稍后重试')
    throw error
  }finally{clearTimeout(timer)}
}

export function resetCsrf(){ csrf=undefined }
