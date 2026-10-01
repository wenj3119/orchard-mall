import { useState } from 'react'
import Taro, { useDidShow } from '@tarojs/taro'
import { Text, View } from '@tarojs/components'
import { get, money, requireLogin, type OrderSummary } from '../../api'

const status = (o: OrderSummary) => o.status === 'PAID'
  ? o.completionStatus === 'COMPLETED' ? '已收货'
    : ({ PENDING: '待发货', PARTIALLY_SHIPPED: '部分发货', SHIPPED: '全部发货' } as Record<string, string>)[o.fulfillmentStatus] || '已支付'
  : ({ PENDING_PAYMENT: '待支付', CLOSE_PENDING: '关单确认中', PAYMENT_EXCEPTION: '收款异常处理中', CANCELLED: '已取消', CLOSED: '已超时关闭' } as Record<string, string>)[o.status] || o.status

export default function Orders() {
  const [items, setItems] = useState<OrderSummary[]>([])
  useDidShow(() => {
    if (requireLogin('/pages/orders/index'))
      get<OrderSummary[]>('/api/customer/orders').then(setItems)
        .catch(e => Taro.showToast({ title: e.message, icon: 'none' }))
  })
  return <View className="page">
    <Text className="heading">我的订单</Text>
    {!items.length && <Text className="empty">暂无订单</Text>}
    {items.map(o => <View className="panel" key={o.id}
      onClick={() => Taro.navigateTo({ url: `/pages/order-detail/index?id=${o.id}` })}>
      <Text className="order-status">{status(o)}</Text>
      <Text className="order-no">{o.orderNo}</Text>
      <Text className="total">{money(o.payableAmountFen)}</Text>
      <Text className="subtle">{o.createdAt}</Text>
    </View>)}
  </View>
}
