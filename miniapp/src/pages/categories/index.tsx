import { useEffect, useState } from 'react'
import { View, Text } from '@tarojs/components'
import { get, type Category, type Product } from '../../api'
import ProductGrid from '../../components/ProductGrid'
export default function Categories() {
  const [categories, setCategories] = useState<Category[]>([])
  const [selected, setSelected] = useState<number | null>(null)
  const [products, setProducts] = useState<Product[]>([])
  const [error, setError] = useState('')
  useEffect(() => { get<Category[]>('/api/public/categories').then(setCategories).catch(() => setError('分类加载失败')) }, [])
  useEffect(() => { get<Product[]>(`/api/public/products${selected ? '?categoryId=' + selected : ''}`).then(setProducts).catch(() => setError('商品加载失败')) }, [selected])
  return <View className="page">
    <Text className="heading">商品分类</Text>
    <View><Text className={selected === null ? 'category active' : 'category'} onClick={() => setSelected(null)}>全部</Text>
      {categories.map(c => <Text key={c.id} className={selected === c.id ? 'category active' : 'category'} onClick={() => setSelected(c.id)}>{c.name}</Text>)}</View>
    {error ? <Text className="empty">{error}</Text> : <ProductGrid products={products} />}
  </View>
}
