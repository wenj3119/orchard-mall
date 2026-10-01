import { useEffect, useState } from 'react'
import { View, Text, Image } from '@tarojs/components'
import { asset, get, type Product, type Store } from '../../api'
import ProductGrid from '../../components/ProductGrid'
export default function Home() {
  const [store, setStore] = useState<Store | null>(null)
  const [products, setProducts] = useState<Product[]>([])
  const [error, setError] = useState('')
  useEffect(() => { Promise.all([get<Store>('/api/public/store'), get<Product[]>('/api/public/products')]).then(([s,p]) => { setStore(s); setProducts(p) }).catch(() => setError('暂时无法加载商品，请稍后重试')) }, [])
  return <View className="page">
    <View className="hero" style={{ backgroundColor: store?.themeColor || '#c54535' }}>
      {store?.logoUrl && <Image className="store-logo" mode="aspectFit" src={asset(store.logoUrl)} />}
      <Text className="hero-title">{store?.name || '果园好物'}</Text><Text className="hero-sub">{store?.description || '产地好物，安心选购'}</Text>
    </View>
    <Text className="heading">精选商品</Text>
    {error ? <Text className="empty">{error}</Text> : <ProductGrid products={products} />}
    <Text className="note">商品、价格、库存与运费均以提交订单时服务端复核结果为准。</Text>
  </View>
}
