import { useRef, useState } from 'react'
import Taro, { useDidHide, useDidShow, useRouter } from '@tarojs/taro'
import { Picker, Text, View } from '@tarojs/components'
import { displayAddress } from '../../addressDisplay'
import { createLatestAsync } from '../../latestAsync'
import { get, money, newCheckoutAddressKey, request, type Address, type CartItem } from '../../api'

type Quote = { quoteHash: string; itemAmountFen: number; shippingAmountFen: number; payableAmountFen: number; purchasable: boolean; reasons: string[]; items: { productTitle: string; quantity: number; lineAmountFen: number; reason?: string }[]; groups: { supplierName: string; originLabel: string; billableWeightG: number; shippingFeeFen?: number; reason?: string }[] }
const readable = (error: unknown, fallback: string) => /[\u4e00-\u9fff]/.test((error as Error)?.message || '') ? (error as Error).message : fallback
const validQuote = (quote?: Quote) => Boolean(quote?.purchasable && !quote.reasons.length && quote.groups.every(g => g.shippingFeeFen != null && !g.reason))

export default function Checkout() {
  const { params } = useRouter()
  const ids = (params.ids || '').split(',').map(Number).filter(Boolean)
  const idsKey = ids.join(',')
  const [addresses, setAddresses] = useState<Address[]>([])
  const [addressId, setAddressId] = useState<number>()
  const [cart, setCart] = useState<CartItem[]>([])
  const [quote, setQuote] = useState<Quote>()
  const [quoteError, setQuoteError] = useState('')
  const [submitError, setSubmitError] = useState('')
  const [loading, setLoading] = useState(true)
  const [submitting, setSubmitting] = useState(false)
  const selectedAddress = useRef<number>()
  const activeQuote = useRef<Quote>()
  const quoteGate = useRef(createLatestAsync())
  const loadGate = useRef(createLatestAsync())
  const busy = useRef(false)
  const attempt = useRef<{ fingerprint: string; key: string }>()
  const refreshQuote = (id?: number) => {
    const sequence = quoteGate.current.begin()
    activeQuote.current = undefined
    setQuote(undefined)
    setQuoteError('')
    setSubmitError('')
    setLoading(Boolean(id))
    if (!id) return
    request<Quote>('/api/customer/checkout/quote', 'POST', { addressId: id, cartItemIds: ids })
      .then(result => { if (quoteGate.current.isCurrent(sequence)) { activeQuote.current = result; setQuote(result) } })
      .catch(error => { if (quoteGate.current.isCurrent(sequence)) setQuoteError(readable(error, '运费试算失败，请重试')) })
      .finally(() => { if (quoteGate.current.isCurrent(sequence)) setLoading(false) })
  }
  useDidHide(() => { quoteGate.current.invalidate(); loadGate.current.invalidate(); activeQuote.current = undefined; setQuote(undefined) })
  useDidShow(() => {
    quoteGate.current.invalidate(); const loadSequence = loadGate.current.begin(); activeQuote.current = undefined; setQuote(undefined); setQuoteError(''); setSubmitError(''); setAddresses([]); setCart([]); setLoading(true)
    Promise.all([get<Address[]>('/api/customer/addresses'), get<CartItem[]>('/api/customer/cart')])
      .then(([nextAddresses, nextCart]) => {
        if (!loadGate.current.isCurrent(loadSequence)) return
        setAddresses(nextAddresses); setCart(nextCart)
        const created = Number(Taro.getStorageSync(newCheckoutAddressKey))
        Taro.removeStorageSync(newCheckoutAddressKey)
        const next = nextAddresses.some(a => a.id === created) ? created : nextAddresses.some(a => a.id === selectedAddress.current) ? selectedAddress.current : (nextAddresses.find(a => a.isDefault) || nextAddresses[0])?.id
        selectedAddress.current = next; setAddressId(next); refreshQuote(next)
      })
      .catch(error => { if (loadGate.current.isCurrent(loadSequence)) { setQuoteError(readable(error, '地址或购物车加载失败，请重试')); setLoading(false) } })
  })
  const subtotal = quote?.itemAmountFen ?? cart.filter(item => ids.includes(item.id)).reduce((sum, item) => sum + item.unitPriceFen * item.quantity, 0)
  const canSubmit = validQuote(quote) && !loading && !submitting && Boolean(addressId)
  const submit = async () => {
    if (busy.current || !canSubmit || !addressId || !activeQuote.current) return
    busy.current = true; setSubmitting(true)
    setSubmitError('')
    const current = activeQuote.current
    const fingerprint = `${addressId}|${idsKey}|${current.quoteHash}`
    if (attempt.current?.fingerprint !== fingerprint) attempt.current = { fingerprint, key: `mini_${Date.now()}_${Math.random().toString(36).slice(2, 10)}` }
    try {
      const result = await request<{ order: { id: number } }>('/api/customer/orders', 'POST', { addressId, cartItemIds: ids, quoteHash: current.quoteHash, idempotencyKey: attempt.current.key })
      await Taro.redirectTo({ url: `/pages/order-detail/index?id=${result.order.id}` })
    } catch (error) {
      if (/报价|商品|库存|地址|运费|changed|quote/i.test((error as Error)?.message || '')) refreshQuote(addressId)
      setSubmitError(readable(error, '下单结果未确认，请核对订单列表后重试；重试将沿用同一请求号'))
    } finally { busy.current = false; setSubmitting(false) }
  }
  if (!addresses.length && !loading && !quoteError) return <View className="page"><Text className="empty">请先添加收货地址</Text><View className="primary-button" onClick={() => Taro.navigateTo({ url: '/pages/addresses/index?from=checkout' })}>新增地址</View></View>
  return <View className="page"><Text className="heading">确认订单</Text>
    {!!addresses.length && <><Picker mode="selector" range={addresses.map(a => `${a.recipient} ${displayAddress(a)}`)} onChange={e => { const next = addresses[Number(e.detail.value)]?.id; selectedAddress.current = next; setAddressId(next); refreshQuote(next) }}><View className="panel">收货地址：{addresses.find(a => a.id === addressId)?.recipient}　{addresses.find(a => a.id === addressId) && displayAddress(addresses.find(a => a.id === addressId)!)}</View></Picker><View className="secondary-button" onClick={() => Taro.navigateTo({ url: '/pages/addresses/index?from=checkout' })}>新增收货地址</View></>}
    {quote?.items.map((item, index) => <View className="line" key={index}><Text>{item.productTitle} × {item.quantity}</Text><Text>{money(item.lineAmountFen)}</Text></View>)}
    {quote?.groups.map((group, index) => <View className="panel" key={index}><Text>{group.supplierName} · {group.originLabel}</Text><Text className="subtle">计费重量 {group.billableWeightG} 克，运费 {group.shippingFeeFen == null ? '不可配送' : group.shippingFeeFen === 0 ? '包邮' : money(group.shippingFeeFen)}</Text>{group.reason && <Text className="error">{group.reason}</Text>}</View>)}
    {quote?.reasons.map((reason, index) => <Text className="error" key={index}>{reason}</Text>)}
    {quoteError && <View><Text className="error">{quoteError}</Text><View className="secondary-button" onClick={() => { if (!addresses.length) Taro.reLaunch({ url: '/pages/checkout/index?ids=' + idsKey }); else refreshQuote(addressId) }}>重试运费试算</View></View>}
    {loading && <Text className="subtle">运费试算中…</Text>}
    {submitError && <Text className="error">{submitError}</Text>}
    <View className="line"><Text>商品小计</Text><Text>{money(subtotal)}</Text></View>
    <View className="line"><Text>运费</Text><Text>{validQuote(quote) ? quote!.shippingAmountFen === 0 ? '包邮' : money(quote!.shippingAmountFen) : quote?.groups.some(group => group.shippingFeeFen == null) ? '不可配送' : '无法计算'}</Text></View>
    <View className="line"><Text>应付</Text><Text className={canSubmit ? 'total' : 'subtle'}>{canSubmit ? money(quote!.payableAmountFen) : '暂不可计算'}</Text></View>
    <Text className="note">提交后只创建待支付订单并预占库存。真实支付尚未接入。</Text>
    <View className={canSubmit ? 'primary-button' : 'secondary-button'} style={{ opacity: submitting ? 0.6 : 1, pointerEvents: canSubmit ? 'auto' : 'none' }} onClick={submit}>{submitting ? '创建中…' : '创建待支付订单'}</View>
  </View>
}
