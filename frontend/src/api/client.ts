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
    pairs.push(encodeURIComponent(k) + '=' + encodeURIComponent(String(v)))
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
