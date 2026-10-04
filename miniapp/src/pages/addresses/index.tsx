import { useEffect, useRef, useState } from 'react'
import Taro, { useRouter } from '@tarojs/taro'
import { Input, Picker, Switch, Text, View } from '@tarojs/components'
import { ApiError, get, newCheckoutAddressKey, request, requireLogin, type Address, type Region } from '../../api'
import { displayAddress } from '../../addressDisplay'

type Form = Pick<Address, 'recipient' | 'mobile' | 'provinceCode' | 'cityCode' | 'districtCode' | 'detail' | 'isDefault'>
const empty: Form = { recipient: '', mobile: '', provinceCode: '', cityCode: '', districtCode: '', detail: '', isDefault: false }
const fieldNames: Record<string, string> = { recipient: '收货人', mobile: '手机号', provinceCode: '省', cityCode: '市', districtCode: '区县', detail: '详细地址' }
function friendlyError(error: Error) {
  if (error instanceof ApiError && error.status === 404) return '地址：不存在或无权修改，请刷新后重试'
  const message = error.message || '保存失败，请重试'
  const field = Object.keys(fieldNames).find(key => message.includes(key))
  if (field) return `${fieldNames[field]}：请检查填写内容`
  return /[\u4e00-\u9fff]/.test(message) ? message : '地址：保存失败，请检查网络后重试'
}
export default function Addresses() {
  const { params } = useRouter()
  const [items, setItems] = useState<Address[]>([])
  const [regions, setRegions] = useState<Region[]>([])
  const [regionError, setRegionError] = useState('')
  const [editing, setEditing] = useState<number>()
  const [form, setForm] = useState<Form>(empty)
  const [historicalError, setHistoricalError] = useState('')
  const [error, setError] = useState('')
  const [saving, setSaving] = useState(false)
  const busy = useRef(false)
  const deleting = useRef(false)
  const load = () => get<Address[]>('/api/customer/addresses').then(setItems).catch(e => setError((e as Error).message))
  const loadRegions = () => { setRegionError(''); get<Region[]>('/api/public/regions').then(setRegions).catch(() => setRegionError('地区加载失败，请重试')) }
  useEffect(() => { if (requireLogin('/pages/addresses/index')) load(); loadRegions() }, [])
  const province = regions.find(x => x.code === form.provinceCode)
  const cities = province?.children || []
  const city = cities.find(x => x.code === form.cityCode)
  const districts = city?.children || []
  const district = districts.find(x => x.code === form.districtCode)
  const startEdit = (address: Address) => {
    if (!regions.length) { setError('地区数据尚未加载，请重试后编辑'); return }
    const valid = regions.some(p => p.code === address.provinceCode && p.children.some(c => c.code === address.cityCode && c.children.some(d => d.code === address.districtCode)))
    setEditing(address.id)
    setForm({ recipient: address.recipient, mobile: address.mobile, provinceCode: valid ? address.provinceCode : '', cityCode: valid ? address.cityCode : '', districtCode: valid ? address.districtCode : '', detail: address.detail, isDefault: address.isDefault })
    setHistoricalError(valid ? '' : `原地区「${address.provinceName}${address.cityName}${address.districtName}」无法匹配，请重新选择所在地区`)
    setError('')
  }
  const input = (key: 'recipient' | 'mobile' | 'detail', label: string) => <View>
    <Text className="field-label">{label}</Text><Input className="field" value={form[key]} maxlength={key === 'detail' ? 240 : 60} placeholder={`请输入${label}`} onInput={e => setForm(current => ({ ...current, [key]: e.detail.value }))} />
  </View>
  const regionPicker = (label: string, values: Region[], value: string, onPick: (code: string) => void) => <View>
    <Text className="field-label">{label}</Text><Picker mode="selector" range={values.map(x => x.name)} value={Math.max(0, values.findIndex(x => x.code === value))} disabled={!values.length} onChange={e => onPick(values[Number(e.detail.value)]?.code || '')}>
      <View className="field">{values.find(x => x.code === value)?.name || `请选择${label}`}</View>
    </Picker>
  </View>
  const save = async () => {
    if (busy.current) return
    setError('')
    if (!form.recipient.trim()) return setError('收货人：请填写收货人')
    if (!/^1[3-9][0-9]{9}$/.test(form.mobile)) return setError('手机号：请输入有效的11位手机号')
    if (!province || !city || !district) return setError('所在地区：请选择完整的省、市、区县')
    if (!form.detail.trim()) return setError('详细地址：请填写详细地址')
    busy.current = true; setSaving(true)
    try {
      const saved = await request<Address>(editing ? `/api/customer/addresses/${editing}` : '/api/customer/addresses', editing ? 'PUT' : 'POST', form)
      if (params.from === 'checkout') {
        Taro.setStorageSync(newCheckoutAddressKey, saved.id)
        await Taro.navigateBack()
      } else { setEditing(undefined); setForm(empty); setHistoricalError(''); await load() }
    } catch (e) { setError(friendlyError(e as Error)) }
    finally { busy.current = false; setSaving(false) }
  }
  return <View className="page"><Text className="heading">收货地址</Text>{items.map(a => <View className="panel" key={a.id}>
    <Text className="line-title">{a.recipient}　{a.mobile}{a.isDefault ? ' · 默认' : ''}</Text><Text className="subtle">{displayAddress(a)}</Text>
    <View className="toolbar"><Text className="small-button" onClick={() => startEdit(a)}>编辑</Text>{!a.isDefault && <Text className="small-button" onClick={async () => { try { await request(`/api/customer/addresses/${a.id}/default`, 'PUT'); await load() } catch (e) { setError((e as Error).message) } }}>设默认</Text>}<Text className="small-button" onClick={async () => { if (deleting.current) return; deleting.current = true; try { const answer = await Taro.showModal({ title: '删除收货地址？', content: `将删除 ${a.recipient} 的地址：${displayAddress(a)}。历史订单地址不会改变。`, cancelText: '保留地址', confirmText: '确认删除' }); if (answer.confirm) { await request(`/api/customer/addresses/${a.id}`, 'DELETE'); await load(); Taro.showToast({ title: '地址已删除', icon: 'success' }) } } catch (e) { setError(friendlyError(e as Error)) } finally { deleting.current = false } }}>删除</Text></View>
  </View>)}
    <Text className="heading">{editing ? '编辑地址' : '新增地址'}</Text>
    {editing && <Text className="small-button" onClick={() => { setEditing(undefined); setForm(empty); setHistoricalError(''); setError('') }}>改为新增</Text>}
    {input('recipient', '收货人')}{input('mobile', '手机号')}
    <Text className="field-label">所在地区</Text>
    {regionError && <View><Text className="error">{regionError}</Text><View className="secondary-button" onClick={loadRegions}>重试加载地区</View></View>}
    {regionPicker('省', regions, form.provinceCode, code => { setForm(current => ({ ...current, provinceCode: code, cityCode: '', districtCode: '' })); setHistoricalError('') })}
    {regionPicker('市', cities, form.cityCode, code => { setForm(current => ({ ...current, cityCode: code, districtCode: '' })); setHistoricalError('') })}
    {regionPicker('区县', districts, form.districtCode, code => { setForm(current => ({ ...current, districtCode: code })); setHistoricalError('') })}
    {historicalError && <Text className="error">{historicalError}</Text>}
    <Text className="note">填写街道、村、小区、楼栋及门牌，无需重复填写省市区</Text>
    {input('detail', '详细地址')}
    <View className="line"><Text>默认地址</Text><Switch checked={form.isDefault} disabled={Boolean(editing && items.find(x => x.id === editing)?.isDefault)} onChange={e => setForm(current => ({ ...current, isDefault: e.detail.value }))} /></View>
    {error && <Text className="error">{error}</Text>}
    <View className="primary-button" style={{ opacity: saving ? 0.6 : 1 }} onClick={save}>{saving ? '保存中…' : '保存地址'}</View>
  </View>
}
