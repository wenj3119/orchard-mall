const base = import.meta.env.VITE_API_BASE || ''
const fieldLabels: Record<string, string> = { regionCode: '地区', firstWeightG: '首重', firstFeeFen: '首费', stepWeightG: '续重单位', stepFeeFen: '续费', freeThresholdFen: '包邮门槛' }
const readable = (value: unknown, fallback: string) => typeof value === 'string' && /[\u4e00-\u9fff]/.test(value) ? value : fallback
export type Row = Record<string, any>
export async function api<T = Row>(path: string, options: RequestInit = {}): Promise<T> {
  const headers = new Headers(options.headers)
  const tokenKey = path.startsWith('/api/supplier/') ? 'supplier_token' : 'admin_token'
  const token = localStorage.getItem(tokenKey)
  if (token) headers.set('Authorization', `Bearer ${token}`)
  else headers.delete('Authorization')
  if (options.body instanceof FormData) headers.delete('Content-Type')
  else if (options.body) headers.set('Content-Type', 'application/json')
  const response = await fetch(base + path, { ...options, headers }).catch(() => { throw new Error('网络连接失败，请检查网络后重试') })
  if (response.status === 401) {
    if (path !== '/api/auth/login' && path !== '/api/supplier/auth/login' && localStorage.getItem(tokenKey) === token)
      localStorage.removeItem(tokenKey)
    const error = await response.json().catch(() => ({}))
    throw new Error(readable(error.message || error.detail, '登录已失效，请重新登录'))
  }
  if (!response.ok) {
    const error = await response.json().catch(() => ({}))
    const fields = Array.isArray(error.fields)
      ? error.fields.filter((field: { field?: unknown; message?: unknown }) =>
          typeof field.field === 'string' && typeof field.message === 'string')
          .map((field: { field: string; message: string }) => `${fieldLabels[field.field] || field.field}：${readable(field.message, '请检查填写内容')}`)
      : []
    const message = readable(error.message || error.detail, response.status === 409 ? '状态已变化，请刷新后重试' : response.status >= 500 ? '服务暂不可用，请稍后重试' : '操作未完成，请检查后重试')
    throw new Error(fields.length ? `${message}（${fields.join('；')}）` : message)
  }
  if (response.status === 204 || response.headers.get('content-length') === '0') return undefined as T
  const text = await response.text()
  return text ? JSON.parse(text) as T : undefined as T
}
export const json = (method: string, body: unknown): RequestInit => ({ method, body: JSON.stringify(body) })
export const imageUrl = (path?: string) => path ? base + path : ''
export const uploadAdminMedia = (file: File) => {
  const body = new FormData()
  body.append('file', file)
  return api<{ id: number; url: string }>('/api/admin/media', { method: 'POST', body })
}
