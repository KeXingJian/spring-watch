export interface ApiResp<T = unknown> {
  code: number
  message?: string
  data: T
}

function buildQs(params?: Record<string, unknown>): string {
  if (!params) return ''
  const pairs: string[] = []
  for (const k in params) {
    const v = params[k]
    if (v === undefined || v === null || v === '') continue
    const s = String(v)
    // 防御: 过滤掉 JS 字符串化的 "undefined" / "null" / "[object Object]"
    if (s === 'undefined' || s === 'null' || s === '[object Object]') continue
    pairs.push(encodeURIComponent(k) + '=' + encodeURIComponent(s))
  }
  return pairs.length ? '?' + pairs.join('&') : ''
}

async function unwrap<T>(resp: Response): Promise<T> {
  const json = (await resp.json()) as ApiResp<T> | T
  if (json && typeof json === 'object' && 'code' in (json as ApiResp)) {
    const r = json as ApiResp<T>
    if (r.code !== 200) {
      throw new Error(r.message || 'HTTP ' + r.code)
    }
    return r.data
  }
  return json as T
}

async function request<T>(
  url: string,
  method: 'GET' | 'POST' | 'PUT' | 'DELETE',
  body?: unknown,
  params?: Record<string, unknown>
): Promise<T> {
  const init: RequestInit = {
    method,
    headers: { Accept: 'application/json' }
  }
  if (body !== undefined) {
    (init.headers as Record<string, string>)['Content-Type'] = 'application/json'
    init.body = JSON.stringify(body)
  }
  const resp = await fetch(url + buildQs(params), init)
  if (!resp.ok) {
    let msg = 'HTTP ' + resp.status + ' ' + resp.statusText
    try {
      const j = await resp.json()
      if (j && j.message) msg = j.message
    } catch {
      /* ignore */
    }
    throw new Error(msg)
  }
  return unwrap<T>(resp)
}

export const api = {
  get<T = unknown>(url: string, params?: Record<string, unknown>): Promise<T> {
    return request<T>(url, 'GET', undefined, params)
  },
  /**
   * 后端分页接口 (Spring Data Page) 自动解包 .content 数组。
   * 失败时回退到空数组,避免 v-for 遍历到 Page 自身属性产生幻影行。
   */
  async page<T = unknown>(url: string, params?: Record<string, unknown>): Promise<T[]> {
    try {
      const r = await request<any>(url, 'GET', undefined, params)
      if (r == null) return []
      if (Array.isArray(r)) return r as T[]
      if (Array.isArray(r.content)) return r.content as T[]
      if (Array.isArray(r.data?.content)) return r.data.content as T[]
      return []
    } catch {
      return []
    }
  },
  post<T = unknown>(url: string, body?: unknown, params?: Record<string, unknown>): Promise<T> {
    return request<T>(url, 'POST', body, params)
  },
  put<T = unknown>(url: string, body?: unknown): Promise<T> {
    return request<T>(url, 'PUT', body)
  },
  del<T = unknown>(url: string): Promise<T> {
    return request<T>(url, 'DELETE')
  }
}
