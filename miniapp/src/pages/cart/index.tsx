import { useDidShow } from '@tarojs/taro'
import Taro from '@tarojs/taro'
import { useRef, useState } from 'react'
import { Checkbox, Text, View } from '@tarojs/components'
import { get, money, request, requireLogin, specText, type CartItem } from '../../api'
export default function Cart() {
  const [items,setItems]=useState<CartItem[]>([])
  const busy=useRef(false)
  const load=()=>{if(requireLogin('/pages/cart/index'))get<CartItem[]>('/api/customer/cart').then(setItems).catch(e=>Taro.showToast({title:e.message,icon:'none'}))}
  useDidShow(load)
  const change=async(i:CartItem,quantity=i.quantity,selected=i.selected)=>{if(busy.current)return;busy.current=true;try{setItems(await request(`/api/customer/cart/${i.id}`,'PUT',{quantity,selected}))}catch(e){Taro.showToast({title:(e as Error).message,icon:'none'})}finally{busy.current=false}}
  const selected=items.filter(i=>i.selected&&!i.unavailableReason)
  return <View className="page"><Text className="heading">购物车</Text>{!items.length&&<Text className="empty">购物车为空</Text>}{items.map(i=><View className="panel" key={i.id}>
    <View className="line"><Checkbox value={String(i.id)} checked={i.selected} onClick={()=>change(i,i.quantity,!i.selected)} /><Text className="line-title">{i.productTitle}</Text><Text>{money(i.unitPriceFen)}</Text></View><Text className="subtle">{specText(i.specJson)}</Text>{i.unavailableReason&&<Text className="error">{i.unavailableReason}</Text>}
    <View className="toolbar"><Text className="small-button" onClick={()=>i.quantity>1&&change(i,i.quantity-1)}>−</Text><Text>{i.quantity}</Text><Text className="small-button" onClick={()=>change(i,i.quantity+1)}>＋</Text><Text className="small-button" onClick={async()=>{if(busy.current)return;busy.current=true;try{const answer=await Taro.showModal({title:'从购物车移除商品？',content:`将移除「${i.productTitle}」× ${i.quantity}，不会影响已有订单。`,cancelText:'保留商品',confirmText:'确认移除'});if(answer.confirm){setItems(await request(`/api/customer/cart/${i.id}`,'DELETE'));Taro.showToast({title:'已移除',icon:'success'})}}catch(e){Taro.showToast({title:'移除失败，请重试',icon:'none'})}finally{busy.current=false}}}>删除</Text></View>
  </View>)}
    <View className="line"><Text>已选商品金额</Text><Text className="total">{money(selected.reduce((sum,i)=>sum+i.unitPriceFen*i.quantity,0))}</Text></View>
    <View className="primary-button" onClick={()=>selected.length?Taro.navigateTo({url:'/pages/checkout/index?ids='+selected.map(i=>i.id).join(',')}):Taro.showToast({title:'请选择可购买商品',icon:'none'})}>结算（{selected.length}）</View>
  </View>
}
