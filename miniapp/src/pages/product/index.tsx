import { useEffect, useRef, useState } from 'react'
import Taro, { useDidShow, useRouter } from '@tarojs/taro'
import { View, Text, Image } from '@tarojs/components'
import { ApiError, asset, customerToken, get, money, request, requireLogin, specText, type Product, type Sku } from '../../api'
export default function Detail() {
  const { params } = useRouter()
  const [product, setProduct] = useState<Product | null>(null)
  const [selected, setSelected] = useState<Sku | null>(null)
  const [error, setError] = useState('')
  const [adding, setAdding] = useState(false)
  const busy = useRef(false)
  const loginNavigating = useRef(false)
  const pending = useRef(false)
  const resumePending = useRef(params.resume === '1')
  const selectedRef = useRef<Sku | null>(null)
  selectedRef.current = selected
  const add = async () => {
    if (busy.current || loginNavigating.current || !selectedRef.current || !product) return
    if (!customerToken()) { pending.current = true; loginNavigating.current = true; requireLogin(`/pages/product/index?id=${product.id}&skuId=${selectedRef.current.id}&resume=1`); return }
    busy.current = true; setAdding(true)
    try {
      await request('/api/customer/cart', 'POST', { skuId: selectedRef.current.id, quantity: 1 })
      pending.current = false
      await Taro.showToast({ title: '已加入购物车', icon: 'success', duration: 900 })
      await new Promise(resolve => setTimeout(resolve, 900))
      if (Taro.getCurrentPages().length > 1) await Taro.navigateBack()
      else await Taro.switchTab({ url: '/pages/index/index' })
    } catch (e) {
      if (e instanceof ApiError && (e.status === 401 || e.status === 403)) {
        pending.current = true
        loginNavigating.current = true
        requireLogin(`/pages/product/index?id=${product.id}&skuId=${selectedRef.current?.id}&resume=1`)
      } else Taro.showToast({ title: (e as Error).message || '加入购物车失败，请重试', icon: 'none', duration: 3000 })
    } finally { busy.current = false; setAdding(false) }
  }
  useDidShow(() => { loginNavigating.current = false; if (pending.current && customerToken()) { pending.current = false; void add() } })
  useEffect(() => {
    get<Product>(`/api/public/products/${params.id}`).then(p => { setProduct(p); setSelected(p.skus?.find(s => String(s.id) === params.skuId) || p.skus?.[0] || null) })
      .catch(() => { setError('商品不存在或已下架'); Taro.setNavigationBarTitle({ title: '商品详情' }) })
  }, [params.id])
  useEffect(() => { if (resumePending.current && product && selected && customerToken()) { resumePending.current = false; void add() } }, [product, selected])
  if (error) return <View className="page"><Text className="empty">{error}</Text></View>
  if (!product) return <View className="page"><Text className="empty">加载中…</Text></View>
  return <View>
    {product.imageUrl && <Image className="detail-image" mode="aspectFill" src={asset(product.imageUrl)} />}
    <View className="detail-body">
      <Text className="detail-title">{product.title}</Text>
      <Text className="price">{selected ? money(selected.retailPriceFen) : ''}</Text>
      <Text className="detail-desc">{product.description || ''}</Text>
      <Text className="heading">选择规格</Text>
      <View>{product.skus?.map(s => <Text id={`sku-${s.id}`} key={s.id} className={selected?.id === s.id ? 'spec selected' : 'spec'} onClick={() => setSelected(s)}>{specText(s.specJson)}</Text>)}</View>
      <View className="primary-button" style={{ opacity: adding ? 0.6 : 1 }} onClick={add}>{adding ? '加入中…' : '加入购物车'}</View>
    </View>
  </View>
}
