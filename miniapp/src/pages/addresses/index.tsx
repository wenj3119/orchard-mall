import { useEffect, useState } from 'react'
import Taro from '@tarojs/taro'
import { Input, Text, View } from '@tarojs/components'
import { get, request, requireLogin, type Address } from '../../api'
const empty = { recipient:'', mobile:'', provinceCode:'', provinceName:'', cityCode:'', cityName:'', districtCode:'', districtName:'', detail:'' }
export default function Addresses() {
  const [items,setItems]=useState<Address[]>([]); const [editing,setEditing]=useState<number|undefined>(); const [form,setForm]=useState(empty)
  const load=()=>get<Address[]>('/api/customer/addresses').then(setItems).catch(e=>Taro.showToast({title:e.message,icon:'none'}))
  useEffect(()=>{ if(requireLogin('/pages/addresses/index')) load() },[])
  const field=(key:keyof typeof empty,label:string)=><Input id={`address-${key}`} className="field" value={form[key]} maxlength={key==='detail'?240:60} placeholder={label} onInput={e=>setForm({...form,[key]:e.detail.value})}/>
  return <View className="page"><Text className="heading">收货地址</Text>{items.map(a=><View className="panel" key={a.id}>
    <Text className="line-title">{a.recipient}　{a.mobile}{a.isDefault?' · 默认':''}</Text><Text className="subtle">{a.provinceName}{a.cityName}{a.districtName}{a.detail}</Text>
    <View className="toolbar"><Text className="small-button" onClick={()=>{setEditing(a.id);setForm({...a, detail:a.detail})}}>编辑</Text>{!a.isDefault&&<Text className="small-button" onClick={async()=>{await request(`/api/customer/addresses/${a.id}/default`,'PUT');load()}}>设默认</Text>}<Text className="small-button" onClick={async()=>{await request(`/api/customer/addresses/${a.id}`,'DELETE');load()}}>删除</Text></View>
  </View>)}
    <Text className="heading">{editing?'编辑地址':'新增地址'}</Text>{field('recipient','收件人')}{field('mobile','手机号')}{field('provinceCode','省代码，例如 610000')}{field('provinceName','省名称')}{field('cityCode','市代码，例如 610100')}{field('cityName','市名称')}{field('districtCode','区县代码，例如 610102')}{field('districtName','区县名称')}{field('detail','详细地址')}
    <View className="primary-button" onClick={async()=>{try{await request(editing?`/api/customer/addresses/${editing}`:'/api/customer/addresses',editing?'PUT':'POST',form);setEditing(undefined);setForm(empty);load()}catch(e){Taro.showToast({title:(e as Error).message,icon:'none'})}}}>保存地址</View>
  </View>
}
