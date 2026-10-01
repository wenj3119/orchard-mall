import { useEffect, useState } from 'react'
import Taro, { useRouter } from '@tarojs/taro'
import { Input, Picker, Text, Textarea, View } from '@tarojs/components'
import { get, money, request, upload } from '../../api'
import { fenToYuan, yuanToFen } from '../../refundAmount'

type Case = { id: number; caseNo: string; orderNo: string; action: string; reasonCode: string; status: string; requestedRefundFen: number; approvedRefundFen: number }
type Detail = Case & { reviewComment?: string; items: { productTitle: string; skuCode: string; requestedQty: number }[]; evidence: { id: number }[]; refunds: { refundNo: string; amountFen: number; status: string }[]; replacementTasks: { taskNo: string; status: string }[] }
type Eligibility = { remainingQty: number; unitPaidFen: number; maxRefundFen: number; reason: string; storeContactPhone: string }
const reasonCodes = ['BAD_FRUIT', 'DAMAGED', 'UNSHIPPED_CANCEL', 'OTHER']
const reasonNames = ['坏果', '破损', '未发货取消', '其他']
const actionName = (value: string) => ({ REFUND: '退款', REPLACEMENT: '补发' } as Record<string, string>)[value] || value
const caseStatusName = (value: string) => ({ SUBMITTED: '待审核', REFUND_PENDING: '退款处理中', REPLACEMENT_PENDING: '补发处理中', WAITING_STOCK: '等待补发库存', COMPLETED: '已完成', REJECTED: '已拒绝', CANCELLED: '已取消' } as Record<string, string>)[value] || value
const refundStatusName = (value: string) => ({ PENDING: '处理中', PROCESSING: '处理中', SUCCEEDED: '已退款', FAILED: '退款失败', UNKNOWN: '结果待确认' } as Record<string, string>)[value] || value
const taskStatusName = (value: string) => ({ PENDING_ACCEPTANCE: '待接单', ACCEPTED: '已接单', PARTIALLY_SHIPPED: '部分发货', SHIPPED: '已发货' } as Record<string, string>)[value] || value

export default function AfterSales() {
  const { params } = useRouter()
  const [items, setItems] = useState<Case[]>([])
  const [detail, setDetail] = useState<Detail>()
  const [action, setAction] = useState('REFUND')
  const [qty, setQty] = useState(1)
  const [refundYuan, setRefundYuan] = useState('')
  const [eligibility, setEligibility] = useState<Eligibility>()
  const [reason, setReason] = useState('DAMAGED')
  const [description, setDescription] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [submittedCaseId, setSubmittedCaseId] = useState<number>()
  const [uploading, setUploading] = useState(false)
  const load = () => get<Case[]>('/api/customer/after-sales').then(setItems).catch(e => Taro.showToast({ title: e.message, icon: 'none' }))
  const openCase = async (id: number) => {
    setDetail(await get<Detail>(`/api/customer/after-sales/${id}`))
    await load()
  }
  useEffect(() => { load() }, [])
  useEffect(() => {
    if (!params.orderId || !params.orderItemId) return
    get<Eligibility>(`/api/customer/orders/${params.orderId}/items/${params.orderItemId}/after-sales/eligibility`)
      .then(value => { setEligibility(value); setQty(1); setRefundYuan(value.maxRefundFen > 0 ? fenToYuan(Math.min(value.unitPaidFen, value.maxRefundFen)) : '') })
      .catch(e => Taro.showToast({ title: e.message, icon: 'none' }))
  }, [params.orderId, params.orderItemId])
  const maxForSelection = eligibility ? Math.min(eligibility.maxRefundFen, eligibility.unitPaidFen * qty) : 0

  const addEvidence = async (caseId: number) => {
    if (uploading) return
    try {
      const media = await Taro.chooseImage({ count: 3, sourceType: ['album', 'camera'] })
      if (!media.tempFilePaths.length) return
      setUploading(true)
      for (const filePath of media.tempFilePaths) await upload(`/api/customer/after-sales/${caseId}/evidence`, filePath)
      await openCase(caseId)
      Taro.showToast({ title: '凭证上传成功', icon: 'success' })
    } catch (e) {
      if (!String((e as { errMsg?: string }).errMsg || '').includes('cancel')) Taro.showToast({ title: (e as Error).message || '凭证上传失败', icon: 'none' })
      await openCase(caseId).catch(() => undefined)
    } finally { setUploading(false) }
  }

  const submit = async () => {
    if (submitting) return
    if (!eligibility || eligibility.remainingQty < 1 || (action === 'REFUND' && maxForSelection < 1)) {
      Taro.showToast({ title: eligibility?.reason || '正在读取可申请范围', icon: 'none' })
      return
    }
    const requestedRefundFen = yuanToFen(refundYuan)
    if (action === 'REFUND' && (requestedRefundFen === null || requestedRefundFen > maxForSelection)) {
      Taro.showToast({ title: '请输入有效金额，最多两位小数且不超过当前可申请金额', icon: 'none' })
      return
    }
    setSubmitting(true)
    try {
      const c = await request<Detail>(`/api/customer/orders/${params.orderId}/after-sales`, 'POST', {
        action, reasonCode: reason, reasonDetail: description,
        ...(action === 'REFUND' ? { requestedRefundFen } : {}),
        items: [{ orderItemId: Number(params.orderItemId), quantity: qty }]
      })
      setSubmittedCaseId(c.id)
      await openCase(c.id)
      await load()
      Taro.showToast({ title: '售后申请已提交', icon: 'success' })
      await addEvidence(c.id)
    } catch (e) { Taro.showToast({ title: (e as Error).message, icon: 'none' }) }
    finally { setSubmitting(false) }
  }

  return <View className="page">
    {params.orderId && !submittedCaseId && <View className="panel">
      <Text className="heading">提交售后申请</Text>
      {eligibility && eligibility.remainingQty < 1 && <><Text className="error">{eligibility.reason}</Text><Text className="note">{eligibility.storeContactPhone ? `店铺联系电话：${eligibility.storeContactPhone}` : '店铺尚未配置联系电话'}</Text></>}
      {eligibility && eligibility.remainingQty > 0 && eligibility.maxRefundFen < 1 && <Text className="error">{eligibility.reason}</Text>}
      <Picker mode="selector" range={['退款', '补发']} onChange={e => setAction(Number(e.detail.value) === 0 ? 'REFUND' : 'REPLACEMENT')}><View className="field">处理方式：{action === 'REFUND' ? '退款' : '补发'}</View></Picker>
      {eligibility && eligibility.remainingQty > 0 && <Input className="field" type="number" value={String(qty)} onInput={e => setQty(Math.max(1, Math.min(eligibility.remainingQty, Number(e.detail.value) || 1)))} placeholder="数量" />}
      {action === 'REFUND' && eligibility && eligibility.maxRefundFen > 0 && <><Text className="subtle">申请退款金额（元，仅商品；本次最多 {money(maxForSelection)}）</Text><Input className="field" type="text" value={refundYuan} onInput={e => setRefundYuan(e.detail.value)} placeholder="例如 5.00" /></>}
      <Picker mode="selector" range={reasonNames} onChange={e => setReason(reasonCodes[Number(e.detail.value)])}><View className="field">原因：{reasonNames[reasonCodes.indexOf(reason)]}</View></Picker>
      <Textarea className="field" value={description} onInput={e => setDescription(e.detail.value)} placeholder="补充说明" />
      <Text className="note">提交后可选择 JPEG/PNG/WebP 凭证，单张不超过 5MB。退款由店主审核；这里不会调用真实资金接口。</Text>
      {eligibility && eligibility.remainingQty > 0 && (action !== 'REFUND' || eligibility.maxRefundFen > 0) && <View className="primary-button" onClick={submit}>{submitting ? '提交中…' : '提交申请并选择凭证'}</View>}
    </View>}
    <Text className="heading">我的售后</Text>
    {items.map(c => <View className="panel" key={c.id} onClick={() => openCase(c.id).catch(e => Taro.showToast({ title: e.message, icon: 'none' }))}>
      <Text className="line-title case-no">{c.caseNo}</Text><Text className="subtle">{c.orderNo} · {actionName(c.action)} · {caseStatusName(c.status)}</Text>
      <Text className="subtle">申请 {money(c.requestedRefundFen)} / 核准 {money(c.approvedRefundFen)}</Text>
    </View>)}
    {detail && <View className="panel">
      <Text className="line-title">进度：{caseStatusName(detail.status)}</Text>
      {detail.reviewComment && <Text className="subtle">审核意见：{detail.reviewComment}</Text>}
      {detail.items.map((i, n) => <Text className="subtle" key={n}>{i.productTitle} {i.skuCode} × {i.requestedQty}</Text>)}
      <Text className="subtle">已上传凭证：{detail.evidence.length} 张</Text>
      <View className="small-button" onClick={() => addEvidence(detail.id)}>{uploading ? '上传中…' : '继续上传凭证'}</View>
      {detail.refunds.map(r => <View key={r.refundNo}><Text className="subtle">退款 {r.refundNo} · {money(r.amountFen)} · {refundStatusName(r.status)}</Text><Text className="note">模拟退款状态，仅用于开发验证，不代表微信、支付宝或银行结果。</Text></View>)}
      {detail.replacementTasks.map(t => <Text className="subtle" key={t.taskNo}>补发任务 {t.taskNo} · {taskStatusName(t.status)}；包裹会显示在原订单详情</Text>)}
    </View>}
  </View>
}
