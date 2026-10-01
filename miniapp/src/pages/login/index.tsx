import { useState } from 'react'
import Taro, { useRouter } from '@tarojs/taro'
import { Input, Picker, Text, View } from '@tarojs/components'
import { ApiError, request } from '../../api'
export default function Login() {
  const { params } = useRouter()
  const [platform, setPlatform] = useState<'WECHAT' | 'ALIPAY'>('WECHAT')
  const [userId, setUserId] = useState('demo-user')
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState('')
  const login = async () => {
    if (submitting) return
    if (!/^[A-Za-z0-9_-]{3,80}$/.test(userId)) {
      setError('开发用户标识需为 3–80 位字母、数字、下划线或连字符')
      return
    }
    setSubmitting(true)
    setError('')
    try {
      const result = await request<{ token: string }>('/api/dev/consumer-login', 'POST', { platform, externalUserId: userId })
      Taro.setStorageSync('customer_token', result.token)
      const target = params.returnUrl ? decodeURIComponent(params.returnUrl) : '/pages/index/index'
      if (target.includes('/pages/index/') || target.includes('/pages/cart/') || target.includes('/pages/orders/'))
        await Taro.switchTab({ url: target })
      else await Taro.redirectTo({ url: target })
    } catch (cause) {
      const message = cause instanceof ApiError && cause.status === 404
        ? '开发登录接口返回 404，请确认连接的是已开启模拟登录的 dev/test 后端'
        : (cause as Error).message || '登录失败，请检查网络后重试'
      setError(message)
      Taro.showToast({ title: message, icon: 'none', duration: 3000 })
    } finally {
      setSubmitting(false)
    }
  }
  return <View className="page"><Text className="heading">开发环境登录</Text>
    <Text className="note">仅调用后端开发模拟入口；正式环境禁止启用。微信与支付宝身份不会自动合并。</Text>
    <Picker mode="selector" range={['微信', '支付宝']} onChange={e => { setPlatform(Number(e.detail.value) === 0 ? 'WECHAT' : 'ALIPAY'); setError('') }}><View className="field">平台：{platform === 'WECHAT' ? '微信' : '支付宝'}</View></Picker>
    <Input className="field" value={userId} maxlength={80} onInput={e => { setUserId(e.detail.value); setError('') }} placeholder="开发用户标识" />
    {error && <Text className="error">{error}</Text>}
    <View className="primary-button" style={{ opacity: submitting ? 0.6 : 1 }} onClick={login}>{submitting ? '登录中…' : '登录'}</View>
  </View>
}
