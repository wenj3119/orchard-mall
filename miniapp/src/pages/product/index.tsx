import { useEffect, useState } from 'react'
import Taro, { useRouter } from '@tarojs/taro'
import { View, Text, Image } from '@tarojs/components'
import { asset, get, money, request, requireLogin, specText, type Product, type Sku } from '../../api'
export default function Detail() {
  const { params } = useRouter()
  const [product, setProduct] = useState<Product | null>(null)
  const [selected, setSelected] = useState<Sku | null>(null)
  const [error, setError] = useState('')
  useEffect(() => {
    get<Product>(`/api/public/products/${params.id}`).then(p => { setProduct(p); setSelected(p.skus?.[0] || null) })
      .catch(() => { setError('商品不存在或已下架'); Taro.setNavigationBarTitle({ title: '商品详情' }) })
  }, [params.id])
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
      <View className="primary-button" onClick={async () => {
        if (!selected || !requireLogin(`/pages/product/index?id=${product.id}`)) return
        try { await request('/api/customer/cart', 'POST', { skuId: selected.id, quantity: 1 }); Taro.showToast({ title: '已加入购物车', icon: 'success' }) }
        catch (e) { Taro.showToast({ title: (e as Error).message, icon: 'none' }) }
      }}>加入购物车</View>
    </View>
  </View>
}
