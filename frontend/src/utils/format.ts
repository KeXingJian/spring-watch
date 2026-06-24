export function formatBytes(n: number | null | undefined): string {
  if (n == null || isNaN(n)) return '-'
  const abs = Math.abs(n)
  if (abs < 1024) return n.toFixed(0) + ' B'
  if (abs < 1024 * 1024) return (n / 1024).toFixed(1) + ' KB'
  if (abs < 1024 * 1024 * 1024) return (n / 1024 / 1024).toFixed(1) + ' MB'
  return (n / 1024 / 1024 / 1024).toFixed(2) + ' GB'
}

export function formatPercent(n: number | null | undefined, fixed = 1): string {
  if (n == null || isNaN(n)) return '-'
  return (n * 100).toFixed(fixed) + '%'
}

export function formatNumber(n: number | null | undefined, fixed = 2): string {
  if (n == null || isNaN(n)) return '-'
  return n.toFixed(fixed).replace(/\B(?=(\d{3})+(?!\d))/g, ',')
}

export function formatMs(n: number | null | undefined): string {
  if (n == null || isNaN(n)) return '-'
  if (n < 1) return (n * 1000).toFixed(1) + ' μs'
  if (n < 1000) return n.toFixed(1) + ' ms'
  return (n / 1000).toFixed(2) + ' s'
}

export function formatTime(iso?: string | null): string {
  if (!iso) return '-'
  try {
    return new Date(iso).toLocaleString('zh-CN', { hour12: false })
  } catch {
    return iso
  }
}

export function escapeHtml(s: unknown): string {
  if (s == null) return ''
  return String(s).replace(/[&<>"']/g, (c) =>
    ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]!)
  )
}

export function bytesToMB(n: number | null | undefined): number | null {
  if (n == null || isNaN(n)) return null
  return n / 1048576
}

export function formatMB(n: number | null | undefined, fixed = 1): string {
  const v = bytesToMB(n)
  if (v == null) return '-'
  return v.toFixed(fixed)
}

export function extractTagValue(seriesName: string | null | undefined, tagKey: string): string | null {
  if (!seriesName) return null
  const m = seriesName.match(new RegExp(tagKey + '=([^,}]+)'))
  return m ? m[1] : null
}

export function shortJson(s: string | null | undefined): string {
  if (!s) return ''
  try {
    const o = JSON.parse(s)
    const keys = Object.keys(o)
    if (keys.length === 0) return '{}'
    if (keys.length <= 2) return JSON.stringify(o)
    return keys.slice(0, 2).map((k) => k + '=' + o[k]).join(',') + '…'
  } catch {
    return s.length > 20 ? s.slice(0, 20) + '…' : s
  }
}

export function shortMsg(s: string | null | undefined, n = 100): string {
  if (!s) return ''
  return s.length > n ? s.slice(0, n) + '...' : s
}
