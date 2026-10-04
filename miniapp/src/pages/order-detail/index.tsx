import { useRef, useState } from 'react'
import Taro, { useDidHide, useDidShow, useRouter, useUnload } from '@tarojs/taro'
import { Text, View } from '@tarojs/components'
import { displayAddress } from '../../addressDisplay'
import { createOrderCancellation } from '../../orderCancellation'
import { get, money, request, specText, type Address, type OrderSummary } from '../../api'

type Parcel = { id: number; carrierName: string; trackingNo: string; receivedAt?: string; items: { productTitle: string; skuCode: string; quantity: number }[] }
type Detail = { order: OrderSummary; address: Address; items: { id: number; productTitle: string; specJson: string; quantity: number; lineAmountFen: number }[] }
const name = (value: string, names: Record<string, string>) => names[value] || '状态待确认'
const statusName = (value: string) => name(value, { PENDING_PAYMENT: '待支付', CLOSE_PENDING: '关单确认中', PAYMENT_EXCEPTION: '收款异常处理中', PAID: '已支付', CANCELLED: '已取消', CLOSED: '已关闭' })
const readable = (error: unknown, fallback: string) => /[\u4e00-\u9fff]/.test((error as Error)?.message || '') ? (error as Error).message : fallback

export default function OrderDetail() {
  const { params } = useRouter()
  const [detail, setDetail] = useState<Detail>()
  const [parcels, setParcels] = useState<Parcel[]>([])
  const [notice, setNotice] = useState('')
  const [cancelBusy, setCancelBusy] = useState(false)
  const [receiptBusy, setReceiptBusy] = useState<number>()
  const [paymentBusy, setPaymentBusy] = useState(false)
  const cancelFlow = useRef(createOrderCancellation())
  const receiptLock = useRef(false)
  const paymentLock = useRef(false)
  const timer = useRef<ReturnType<typeof setTimeout>>()
  const visible = useRef(true)
  const clearPoll = () => { if (timer.current) clearTimeout(timer.current); timer.current = undefined }
  const load = async () => {
    const [order, nextParcels] = await Promise.all([get<Detail>(`/api/customer/orders/${params.id}`), get<Parcel[]>(`/api/customer/orders/${params.id}/parcels`)])
    if (visible.current) { setDetail(order); setParcels(nextParcels) }
    return order
  }
  const openList = async (status: string) => {
    clearPoll()
    await Taro.showToast({ title: status === 'CLOSED' ? '订单已关闭' : '订单已取消', icon: 'none' })
    await Taro.switchTab({ url: '/pages/orders/index' })
  }
  const reconcile = async (remaining = 3): Promise<void> => {
    if (!visible.current) return
    try {
      const current = await load()
      const status = current.order.status
      if (status === 'CANCELLED' || status === 'CLOSED') return openList(status)
      if (status === 'PAID') { setNotice('订单已支付，无法按待支付订单取消；请查看售后入口'); return }
      if (status === 'PAYMENT_EXCEPTION') { setNotice('收款异常处理中，请稍后刷新订单状态'); return }
      if (status === 'CLOSE_PENDING') {
        setNotice('订单取消处理中，请稍后刷新')
        if (remaining > 0) timer.current = setTimeout(() => { reconcile(remaining - 1) }, 2000)
        return
      }
      setNotice('订单仍待支付。如刚才请求超时，请先刷新状态，再决定是否重试取消。')
    } catch { setNotice('取消结果尚未确认，请点击刷新订单核对状态') }
  }
  useDidShow(() => { visible.current = true; load().catch(error => setNotice(readable(error, '订单加载失败，请重试'))) })
  useDidHide(() => { visible.current = false; clearPoll() })
  useUnload(() => { visible.current = false; clearPoll() })
  const cancel = async () => {
    if (cancelBusy || detail?.order.status !== 'PENDING_PAYMENT') return
    setCancelBusy(true)
    try {
      const outcome = await cancelFlow.current.run(
        async () => (await Taro.showModal({ title: '确认取消订单？', content: '取消后将无法继续支付此订单，如需购买请重新下单。', cancelText: '暂不取消', confirmText: '确认取消' })).confirm,
        async () => { setNotice('正在确认取消结果…'); const result = await request<Detail>(`/api/customer/orders/${detail.order.id}/cancel`, 'POST'); setDetail(result); return result.order.status },
        async () => (await load()).order.status
      )
      if (!outcome || outcome.dismissed) return
      if (outcome.status === 'CANCELLED' || outcome.status === 'CLOSED') await openList(outcome.status)
      else if (outcome.status === 'CLOSE_PENDING') { setNotice('订单取消处理中，请稍后刷新'); timer.current = setTimeout(() => { reconcile(2) }, 2000) }
      else if (outcome.status === 'PAID') setNotice('订单已支付，无法按待支付订单取消；请查看售后入口')
      else if (outcome.status === 'PAYMENT_EXCEPTION') setNotice('收款异常处理中，请稍后刷新订单状态')
      else if (outcome.status === 'UNKNOWN') setNotice('取消结果尚未确认，请点击刷新订单核对状态')
      else setNotice(outcome.requestError ? `${readable(outcome.requestError, '请求超时')}。订单仍待支付，请先刷新状态再决定是否重试。` : '订单仍待支付，请刷新状态后再试')
    } catch (error) { setNotice(readable(error, '取消操作失败，请刷新订单状态')) }
    finally { setCancelBusy(false) }
  }
  const confirmParcel = async (parcel: Parcel) => {
    if (receiptLock.current) return
    receiptLock.current = true; setReceiptBusy(parcel.id)
    try {
      const lines = parcel.items.map(item => `${item.productTitle} × ${item.quantity}`).join('、')
      const confirmation = await Taro.showModal({ title: '确认收到此包裹？', content: `${parcel.carrierName} ${parcel.trackingNo}：${lines}。确认后此包裹将标记为已收货。`, cancelText: '暂不确认', confirmText: '确认收货' })
      if (!confirmation.confirm) return
      await request(`/api/customer/parcels/${parcel.id}/confirm-received`, 'POST')
      await load()
      Taro.showToast({ title: '此包裹已确认收货', icon: 'success' })
    } catch (error) {
      try {
        const current = await get<Parcel[]>(`/api/customer/orders/${params.id}/parcels`)
        setParcels(current)
        if (current.find(item => item.id === parcel.id)?.receivedAt) Taro.showToast({ title: '此包裹已确认收货', icon: 'success' })
        else setNotice(readable(error, '确认收货失败，请刷新后重试'))
      } catch { setNotice('收货结果未确认，请刷新订单后核对此包裹') }
    }
    finally { receiptLock.current = false; setReceiptBusy(undefined) }
  }
  const simulatePayment = async () => {
    if (!DEV_PAYMENT_ENABLED || paymentLock.current || detail?.order.status !== 'PENDING_PAYMENT') return
    paymentLock.current = true; setPaymentBusy(true)
    try {
      const answer = await Taro.showModal({ title: '开发模拟支付成功？', content: `仅在隔离开发环境模拟 ${money(detail.order.payableAmountFen)} 的支付结果，不调用真实资金接口。`, cancelText: '暂不模拟', confirmText: '确认模拟' })
      if (!answer.confirm) return
      const result = await request<{ attemptNo: string }>(`/api/customer/orders/${detail.order.id}/payments`, 'POST', { channel: 'DEV_SIMULATOR' })
      await request(`/api/customer/payment-attempts/${result.attemptNo}/simulate`, 'POST', { result: 'SUCCESS' })
      await load()
    } catch (error) { setNotice(readable(error, '模拟支付失败，请刷新订单状态')) }
    finally { paymentLock.current = false; setPaymentBusy(false) }
  }
  if (!detail) return <View className="page"><Text className="empty">加载中…</Text>{notice && <Text className="error">{notice}</Text>}<View className="secondary-button" onClick={() => load().catch(() => setNotice('刷新失败，请稍后重试'))}>刷新订单</View></View>
  const order = detail.order
  return <View className="page">
    <View className="panel"><Text className="line-title">{order.orderNo}</Text><Text className="subtle">订单：{statusName(order.status)} · 支付：{name(order.paymentStatus, { UNPAID: '未支付', PAID: '已支付', SUCCESS: '已支付' })} · 履约：{name(order.fulfillmentStatus, { NOT_STARTED: '未开始', PENDING: '待发货', PARTIALLY_SHIPPED: '部分发货', SHIPPED: '已发货' })} · 收货：{name(order.completionStatus, { NOT_COMPLETED: '未收货', COMPLETED: '已收货' })}</Text></View>
    <View className="panel"><Text>{detail.address.recipient}　{detail.address.mobile}</Text><Text className="subtle">{displayAddress(detail.address)}</Text></View>
    {detail.items.map(item => <View className="panel" key={item.id}><View className="line"><Text>{item.productTitle} {specText(item.specJson)} × {item.quantity}</Text><Text>{money(item.lineAmountFen)}</Text></View>{order.status === 'PAID' && <View className="small-button" onClick={() => Taro.navigateTo({ url: `/pages/after-sales/index?orderId=${order.id}&orderItemId=${item.id}` })}>申请退款 / 补发</View>}</View>)}
    <View className="line"><Text>商品金额</Text><Text>{money(order.itemAmountFen)}</Text></View><View className="line"><Text>运费</Text><Text>{money(order.shippingAmountFen)}</Text></View><View className="line"><Text>合计</Text><Text className="total">{money(order.payableAmountFen)}</Text></View>
    {DEV_PAYMENT_ENABLED && order.status === 'PENDING_PAYMENT' && <><Text className="note">开发模拟支付仅供隔离环境使用，不会调用微信或支付宝资金接口</Text><View className="primary-button" onClick={simulatePayment}>{paymentBusy ? '处理中…' : '模拟支付成功（仅开发）'}</View></>}
    {!DEV_PAYMENT_ENABLED && order.status === 'PENDING_PAYMENT' && <Text className="note">真实支付尚未接入；关闭付款窗口不会取消订单。</Text>}
    {parcels.map(parcel => <View className="panel" key={parcel.id}><Text className="line-title">{parcel.carrierName} · {parcel.trackingNo}</Text>{parcel.items.map((item, index) => <Text className="subtle" key={index}>{item.productTitle} {item.skuCode} × {item.quantity}</Text>)}<Text className="note">尚未接入物流轨迹服务</Text>{parcel.receivedAt ? <Text className="subtle">已确认收货：{parcel.receivedAt}</Text> : <View className="primary-button" onClick={() => confirmParcel(parcel)}>{receiptBusy === parcel.id ? '处理中…' : '确认收到此包裹'}</View>}</View>)}
    {order.status === 'PAID' && <View className="secondary-button" onClick={() => Taro.navigateTo({ url: '/pages/after-sales/index' })}>查看售后进度</View>}
    {notice && <Text className="error">{notice}</Text>}
    <View className="secondary-button" onClick={() => { clearPoll(); reconcile(0) }}>刷新订单状态</View>
    {order.status === 'PENDING_PAYMENT' && <View className="danger-button" onClick={cancel}>{cancelBusy ? '取消处理中…' : '取消订单'}</View>}
  </View>
}
