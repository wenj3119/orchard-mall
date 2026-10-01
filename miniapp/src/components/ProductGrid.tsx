import { View, Image, Text } from '@tarojs/components'
import Taro from '@tarojs/taro'
import { asset, money, type Product } from '../api'
export default function ProductGrid({ products }: { products: Product[] }) {
  if (!products.length) return <Text className="empty">当前没有已上架商品</Text>
  return <View className="grid">{products.map(product => <View key={product.id} className="product-card" onClick={() => Taro.navigateTo({ url: `/pages/product/index?id=${product.id}` })}>
    {product.imageUrl ? <Image className="product-image" mode="aspectFill" src={asset(product.imageUrl)} /> : <View className="product-placeholder">暂无图片</View>}
    <View className="product-info"><Text className="product-title">{product.title}</Text><Text className="price">{money(product.minPriceFen)} 起</Text></View>
  </View>)}</View>
}
