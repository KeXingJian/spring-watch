export interface ApiResp<T = unknown> {
  code: number
  message?: string
  data: T
}

export interface PageResult<T> {
  items: T[]
  total: number
  page: number
  size: number
  totalPages: number
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
  /**
   * 分页接口,返回完整分页元数据(items / total / page / size / totalPages)。
   * 兼容后端 Spring Data Web 三种响应形态:
   *  1) 平铺 Page(默认模式): { content, totalElements, totalPages, number, size }
   *  2) VIA_DTO 模式(Spring Data 3.x+): { content, page: { totalElements, totalPages, number, size } }
   *  3) ApiResponse 包裹以上任一: { code, data: <Page|PagedModel> }
   * 失败时回退到空分页。
   */
  async pageFull<T = unknown>(
    url: string,
    params?: Record<string, unknown>
  ): Promise<PageResult<T>> {
    const empty: PageResult<T> = { items: [], total: 0, page: 0, size: 0, totalPages: 0 }
    try {
      const r = await request<any>(url, 'GET', undefined, params)
      if (r == null) return empty
      const unwrapped = Array.isArray(r?.content)
        ? r
        : Array.isArray(r?.data?.content)
        ? r.data
        : null
      if (!unwrapped || !Array.isArray(unwrapped.content)) return empty
      const meta = unwrapped.page ?? unwrapped
      return {
        items: (unwrapped.content ?? []) as T[],
        total: Number(meta.totalElements ?? meta.total ?? 0),
        page: Number(meta.number ?? meta.page ?? 0),
        size: Number(meta.size ?? 0),
        totalPages: Number(meta.totalPages ?? 0)
      }
    } catch {
      return empty
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
