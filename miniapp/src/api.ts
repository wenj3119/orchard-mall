import Taro from '@tarojs/taro'
export type Store = { name: string; logoUrl?: string; themeColor: string; description?: string }
export type Category = { id: number; name: string; sortOrder: number }
export type Product = { id: number; categoryId: number; title: string; description?: string; imageUrl?: string; minPriceFen: number; images?: { url: string }[]; skus?: Sku[] }
export type Sku = { id: number; code: string; specJson: string; retailPriceFen: number }
export type Address = { id: number; recipient: string; mobile: string; provinceCode: string; provinceName: string; cityCode: string; cityName: string; districtCode: string; districtName: string; detail: string; isDefault: boolean }
export type Region = { code: string; name: string; children: Region[] }
export const newCheckoutAddressKey = 'new_checkout_address_id'
export type CartItem = { id: number; skuId: number; productId: number; productTitle: string; skuCode: string; specJson: string; unitPriceFen: number; quantity: number; selected: boolean; rowVersion: number; imageUrl?: string; netWeightG: number; billableWeightG: number; availableQty: number; unavailableReason?: string }
export type OrderSummary = { id: number; orderNo: string; status: 'PENDING_PAYMENT' | 'CLOSE_PENDING' | 'PAID' | 'CANCELLED' | 'CLOSED'; paymentStatus: string; fulfillmentStatus: 'NOT_STARTED' | 'PENDING' | 'PARTIALLY_SHIPPED' | 'SHIPPED'; completionStatus: 'NOT_COMPLETED' | 'COMPLETED'; afterSalesStatus: string; itemAmountFen: number; shippingAmountFen: number; payableAmountFen: number; createdAt: string; expiresAt: string }
export const asset = (path?: string) => path ? (/^https?:\/\//.test(path) ? path : API_BASE + path) : ''
const customerTokenKey = () => DEV_LOGIN_ENABLED ? 'customer_token' : 'customer_token_wechat'
export const customerToken = () => Taro.getStorageSync<string>(customerTokenKey())
export const saveCustomerToken = (token: string) => Taro.setStorageSync(customerTokenKey(), token)
export class ApiError extends Error {
  constructor(public status: number, message: string) { super(message) }
}
export async function request<T>(path: string, method: 'GET' | 'POST' | 'PUT' | 'DELETE' = 'GET', data?: unknown): Promise<T> {
  const token = customerToken()
  const response = await Taro.request<T & { detail?: string; message?: string }>({
    url: API_BASE + path, method, data,
    header: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) }
  })
  if (response.statusCode === 401 || response.statusCode === 403) {
    Taro.removeStorageSync(customerTokenKey())
    throw new ApiError(response.statusCode, '请先登录')
  }
  if (response.statusCode < 200 || response.statusCode >= 300)
    throw new ApiError(response.statusCode, response.data?.detail || response.data?.message || `请求失败 (${response.statusCode})`)
  return response.data
}
export const get = <T,>(path: string) => request<T>(path)
export async function upload(path:string,filePath:string){const token=customerToken();const response=await Taro.uploadFile({url:API_BASE+path,filePath,name:'file',header:token?{Authorization:`Bearer ${token}`}:{}});if(response.statusCode<200||response.statusCode>=300){let detail='上传失败';try{detail=JSON.parse(response.data).detail||detail}catch{}throw new Error(detail)}return JSON.parse(response.data)}
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
