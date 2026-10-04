import Taro from '@tarojs/taro'
export type Store = { name: string; logoUrl?: string; themeColor: string; description?: string }
export type Category = { id: number; name: string; sortOrder: number }
export type Product = { id: number; categoryId: number; title: string; description?: string; imageUrl?: string; minPriceFen: number; images?: { url: string }[]; skus?: Sku[] }
export type Sku = { id: number; code: string; specJson: string; retailPriceFen: number }
export type Address = { id: number; recipient: string; mobile: string; provinceCode: string; provinceName: string; cityCode: string; cityName: string; districtCode: string; districtName: string; detail: string; isDefault: boolean }
export type Region = { code: string; name: string; children: Region[] }
export const newCheckoutAddressKey = 'new_checkout_address_id'
export type CartItem = { id: number; skuId: number; productId: number; productTitle: string; skuCode: string; specJson: string; unitPriceFen: number; quantity: number; selected: boolean; rowVersion: number; imageUrl?: string; netWeightG: number; billableWeightG: number; availableQty: number; unavailableReason?: string }
export type OrderSummary = { id: number; orderNo: string; status: 'PENDING_PAYMENT' | 'CLOSE_PENDING' | 'PAYMENT_EXCEPTION' | 'PAID' | 'CANCELLED' | 'CLOSED'; paymentStatus: string; fulfillmentStatus: 'NOT_STARTED' | 'PENDING' | 'PARTIALLY_SHIPPED' | 'SHIPPED'; completionStatus: 'NOT_COMPLETED' | 'COMPLETED'; afterSalesStatus: string; itemAmountFen: number; shippingAmountFen: number; payableAmountFen: number; createdAt: string; expiresAt: string }
export const buildDiagnostic = { apiOrigin: API_BASE, buildId: BUILD_ID }
export function apiUrl(path: string) {
  const relative = path.startsWith(`${API_BASE}/`) ? path.slice(API_BASE.length) : path
  if (!/^\/api\/(?!api(?:\/|$))/.test(relative)) throw new Error('接口路径必须以 /api/ 开头且不能重复拼接 /api')
  return API_BASE + relative
}
export const asset = (path?: string) => path ? apiUrl(path) : ''
const customerTokenKey = () => DEV_LOGIN_ENABLED ? 'customer_token' : 'customer_token_wechat'
export const customerToken = () => Taro.getStorageSync<string>(customerTokenKey())
export const saveCustomerToken = (token: string) => Taro.setStorageSync(customerTokenKey(), token)
export class ApiError extends Error {
  constructor(public status: number, message: string) { super(message) }
}
function readableError(status: number, detail?: string, code?: string) {
  if (code === 'QUOTE_CHANGED' || /Cart changed|Amount or fulfillment terms changed/i.test(detail || '')) return '商品或报价已变化，请重新试算后提交'
  if (detail && /[\u4e00-\u9fff]/.test(detail)) return detail
  if (status === 404) return '内容不存在或无权访问，请刷新后重试'
  if (status === 409) return '状态已变化，请刷新后重试'
  if (status >= 500) return '服务暂不可用，请稍后重试'
  return '请求未完成，请检查填写内容后重试'
}
export async function request<T>(path: string, method: 'GET' | 'POST' | 'PUT' | 'DELETE' = 'GET', data?: unknown): Promise<T> {
  const token = customerToken()
  const response = await Taro.request<T & { detail?: string; message?: string; code?: string; fields?: { field: string; message: string }[] }>({
    url: apiUrl(path), method, data,
    header: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) }
  }).catch(() => { throw new Error('网络连接失败，请检查网络后重试') })
  if (response.statusCode === 401 || response.statusCode === 403) {
    Taro.removeStorageSync(customerTokenKey())
    throw new ApiError(response.statusCode, '请先登录')
  }
  if (response.statusCode < 200 || response.statusCode >= 300) {
    const labels: Record<string, string> = { recipient: '收货人', mobile: '手机号', provinceCode: '所在地区', cityCode: '所在地区', districtCode: '所在地区', detail: '详细地址' }
    const fields = response.data?.fields?.map(field => `${labels[field.field] || field.field}：${/[\u4e00-\u9fff]/.test(field.message) ? field.message : '请检查填写内容'}`).join('；')
    throw new ApiError(response.statusCode, fields || readableError(response.statusCode, response.data?.detail || response.data?.message, response.data?.code))
  }
  return response.data
}
export const get = <T,>(path: string) => request<T>(path)
export async function upload(path:string,filePath:string){const token=customerToken();const response=await Taro.uploadFile({url:apiUrl(path),filePath,name:'file',header:token?{Authorization:`Bearer ${token}`}:{}}).catch(()=>{throw new Error('上传失败，请检查网络后重试')});if(response.statusCode<200||response.statusCode>=300){let detail='上传失败，请重试';try{detail=readableError(response.statusCode,JSON.parse(response.data).detail)}catch{}throw new Error(detail)}return JSON.parse(response.data)}
export async function download(path:string){const token=customerToken();const response=await Taro.downloadFile({url:apiUrl(path),header:token?{Authorization:`Bearer ${token}`}:{}}).catch(()=>{throw new Error('下载失败，请检查网络后重试')});if(response.statusCode<200||response.statusCode>=300)throw new Error('下载失败，请稍后重试');return response}
export function requireLogin(returnUrl?: string) {
  if (customerToken()) return true
  Taro.navigateTo({ url: `/pages/login/index${returnUrl ? `?returnUrl=${encodeURIComponent(returnUrl)}` : ''}` })
  return false
}
export const money = (fen: number) => `¥${(fen / 100).toFixed(2)}`
export function specText(json: string) {
  try { return Object.entries(JSON.parse(json) as Record<string, string>).map(([key, value]) => `${key}: ${value}`).join(' · ') }
  catch { return json }
}
