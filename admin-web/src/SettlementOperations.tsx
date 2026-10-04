import { useEffect, useState } from 'react'
import { Alert, Button, Card, Form, Input, InputNumber, Modal, Select, Space, Table, message } from 'antd'
import { api, json, type Row } from './api'

const fen = (value: number) => `${value < 0 ? '−' : ''}¥${Math.trunc(Math.abs(value) / 100)}.${String(Math.abs(value % 100)).padStart(2, '0')}`

export default function SettlementOperations() {
  const [statements, setStatements] = useState<Row[]>([])
  const [suppliers, setSuppliers] = useState<Row[]>([])
  const [detail, setDetail] = useState<Row | null>(null)
  const [busy, setBusy] = useState(false)
  const [adjustOpen, setAdjustOpen] = useState(false)
  const [reverseForm] = Form.useForm()
  const [adjustForm] = Form.useForm()

  const refresh = async (selectedId?: number) => {
    const next = await api<Row[]>('/api/admin/settlement-statements')
    setStatements(next)
    if (selectedId) setDetail(await api<Row>(`/api/admin/settlement-statements/${selectedId}`))
  }
  useEffect(() => {
    refresh().catch(e => message.error(e.message))
    api<Row[]>('/api/admin/suppliers').then(setSuppliers).catch(e => message.error(e.message))
  }, [])

  const reverse = async (values: Row) => {
    if (!detail || busy) return
    const payment = detail.payments.find((entry: Row) => entry.id === values.paymentId)
    Modal.confirm({ title: `冲正付款 · ${detail.statementNo}`, content: `将冲正人工付款 ${payment?.referenceNo || values.paymentId}，金额 ${fen(payment?.amountFen || 0)}。此操作追加冲正记录，不删除原记录，也不代表银行退款。`, okText: '确认冲正', cancelText: '继续核对', onOk: () => executeReverse(values) })
  }

  const executeReverse = async (values: Row) => {
    if (!detail || busy) return
    setBusy(true)
    try {
      await api(`/api/admin/settlement-statements/${detail.id}/payments/${values.paymentId}/reverse`, json('POST', {
        idempotencyKey: values.idempotencyKey,
        reason: values.reason
      }))
      await refresh(detail.id)
      reverseForm.resetFields()
      message.success('冲正已追加，原付款记录保留')
    } catch (e) { message.error((e as Error).message) }
    finally { setBusy(false) }
  }

  const adjust = async (values: Row) => {
    if (busy) return
    const supplier = suppliers.find(row => row.id === values.supplierId)
    Modal.confirm({ title: '追加结算调整？', content: `${supplier?.name || '供应商'} · ${fen(values.amountFen)}。将追加台账明细，已有付款及订单记录不变。`, okText: '确认追加', cancelText: '继续核对', onOk: () => executeAdjust(values) })
  }

  const executeAdjust = async (values: Row) => {
    if (busy) return
    setBusy(true)
    try {
      await api('/api/admin/supplier-ledger/adjustments', json('POST', values))
      setAdjustOpen(false)
      adjustForm.resetFields()
      message.success('调整明细已追加，请刷新上方台账')
    } catch (e) { message.error((e as Error).message) }
    finally { setBusy(false) }
  }

  return <Card title="付款冲正与追加调整" extra={<Button onClick={() => setAdjustOpen(true)}>追加调整</Button>}>
    <Alert type="warning" showIcon message="仅记录人工付款与冲正，不验证银行状态。冲正追加新记录，不删除原记录。" />
    <Table rowKey="id" dataSource={statements} columns={[
      { title: '结算单', dataIndex: 'statementNo' },
      { title: '总额', dataIndex: 'totalAmountFen', render: fen },
      { title: '已登记', dataIndex: 'paidAmountFen', render: fen },
      { title: '状态', dataIndex: 'status' },
      { title: '操作', render: (_: unknown, row: Row) => <Button onClick={async () => {
        try { setDetail(await api<Row>(`/api/admin/settlement-statements/${row.id}`)) }
        catch (e) { message.error((e as Error).message) }
      }}>查看明细/冲正</Button> }
    ]} />
    <Modal title={`结算单明细 · ${detail?.statementNo || ''}`} open={!!detail} onCancel={() => setDetail(null)} footer={null} width={760}>
      {detail && <Space direction="vertical" style={{ width: '100%' }}>
        <Table size="small" rowKey="ledgerEntryId" pagination={false} dataSource={detail.items} columns={[
          { title: '类型', dataIndex: 'type' }, { title: '说明', dataIndex: 'description' },
          { title: '锁定金额', dataIndex: 'amountFen', render: fen }
        ]} />
        <Table size="small" rowKey="id" pagination={false} dataSource={detail.payments} columns={[
          { title: '操作', dataIndex: 'operation' }, { title: '金额', dataIndex: 'amountFen', render: fen },
          { title: '参考号', dataIndex: 'referenceNo' }, { title: '冲正对象', dataIndex: 'reversesPaymentId' }
        ]} />
        <Form form={reverseForm} layout="vertical" onFinish={reverse}>
          <Form.Item name="paymentId" label="选择原付款" rules={[{ required: true }]}>
            <Select options={detail.payments.filter((p: Row) => p.operation === 'PAYMENT' && !detail.payments.some((r: Row) => r.reversesPaymentId === p.id)).map((p: Row) => ({ value: p.id, label: `${p.referenceNo} · ${fen(p.amountFen)}` }))} />
          </Form.Item>
          <Form.Item name="idempotencyKey" label="冲正请求键" rules={[{ required: true, min: 8 }, { pattern: /^[A-Za-z0-9_-]+$/, message: '仅限字母、数字、下划线和横线' }]}><Input maxLength={100} /></Form.Item>
          <Form.Item name="reason" label="冲正原因" rules={[{ required: true }]}><Input maxLength={240} /></Form.Item>
          <Button type="primary" htmlType="submit" loading={busy} disabled={busy}>追加冲正</Button>
        </Form>
      </Space>}
    </Modal>
    <Modal title="追加结算调整" open={adjustOpen} onCancel={() => !busy && setAdjustOpen(false)} footer={null}>
      <Form form={adjustForm} layout="vertical" onFinish={adjust}>
        <Form.Item name="supplierId" label="供应商" rules={[{ required: true }]}><Select showSearch optionFilterProp="label" options={suppliers.map(s => ({ value: s.id, label: s.name }))} /></Form.Item>
        <Form.Item name="type" label="调整类型" initialValue="OTHER_ADJUSTMENT" rules={[{ required: true }]}><Select options={[{ value: 'OTHER_ADJUSTMENT', label: '其他调整' }, { value: 'AFTER_SALE_DEDUCTION', label: '售后扣款' }]} /></Form.Item>
        <Form.Item name="amountFen" label="金额（分，可正可负）" rules={[{ required: true }]}><InputNumber precision={0} style={{ width: '100%' }} /></Form.Item>
        <Form.Item name="reason" label="原因" rules={[{ required: true }]}><Input maxLength={240} /></Form.Item>
        <Form.Item name="idempotencyKey" label="请求键" rules={[{ required: true, min: 8 }, { pattern: /^[A-Za-z0-9_-]+$/, message: '仅限字母、数字、下划线和横线' }]}><Input maxLength={100} /></Form.Item>
        <Button type="primary" htmlType="submit" loading={busy} disabled={busy}>追加调整</Button>
      </Form>
    </Modal>
  </Card>
}
