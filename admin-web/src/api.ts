const base = import.meta.env.VITE_API_BASE || ''
export type Row = Record<string, any>
export async function api<T = Row>(path: string, options: RequestInit = {}): Promise<T> {
  const headers: Record<string, string> = { ...(options.headers as Record<string, string> || {}) }
  const token = path.startsWith('/api/supplier/') ? localStorage.getItem('supplier_token') : localStorage.getItem('admin_token')
  if (token) headers.Authorization = `Bearer ${token}`
  if (options.body && !(options.body instanceof FormData)) headers['Content-Type'] = 'application/json'
  const response = await fetch(base + path, { ...options, headers })
  if (response.status === 401) {
    if (path.startsWith('/api/supplier/')) localStorage.removeItem('supplier_token')
    else if (path !== '/api/auth/login') localStorage.removeItem('admin_token')
    const error = await response.json().catch(() => ({}))
    throw new Error(error.message || error.detail || '登录已失效，请重新登录')
  }
  if (!response.ok) {
    const error = await response.json().catch(() => ({}))
    const fields = Array.isArray(error.fields)
      ? error.fields.filter((field: { field?: unknown; message?: unknown }) =>
          typeof field.field === 'string' && typeof field.message === 'string')
          .map((field: { field: string; message: string }) => `${field.field}：${field.message}`)
      : []
    const message = error.message || error.detail || `请求失败 (${response.status})`
    throw new Error(fields.length ? `${message}（${fields.join('；')}）` : message)
  }
  if (response.status === 204 || response.headers.get('content-length') === '0') return undefined as T
  const text = await response.text()
  return text ? JSON.parse(text) as T : undefined as T
}
export const json = (method: string, body: unknown): RequestInit => ({ method, body: JSON.stringify(body) })
export const imageUrl = (path?: string) => path ? base + path : ''
