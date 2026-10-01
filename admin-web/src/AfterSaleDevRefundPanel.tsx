import { useEffect, useState } from 'react'
import { Alert, Button, Card, Modal, Space, Table, message } from 'antd'
import { api, json, type Row } from './api'

export default function AfterSaleDevRefundPanel() {
  const [cases, setCases] = useState<Row[]>([])
  const [detail, setDetail] = useState<Row | null>(null)
  const [busy, setBusy] = useState(false)
  const reload = () => api<Row[]>('/api/admin/after-sales').then(setCases).catch(e => message.error(e.message))
  useEffect(() => { if (import.meta.env.DEV) reload() }, [])
  if (!import.meta.env.DEV) return null
  return <Card title="开发模拟退款结果">
    <Alert type="warning" showIcon message="仅用于本地 dev 模拟通道，不调用真实资金接口；生产构建不提供此操作。" />
    <Table rowKey="id" dataSource={cases} columns={[
      { title: '售后单', dataIndex: 'caseNo' },
      { title: '状态', dataIndex: 'status' },
      { title: '操作', render: (_: unknown, row: Row) => <Button onClick={async () => {
        try { setDetail(await api<Row>(`/api/admin/after-sales/${row.id}`)) }
        catch (e) { message.error((e as Error).message) }
      }}>查看模拟结果</Button> }
    ]} />
    <Modal title={`开发退款 · ${detail?.caseNo || ''}`} open={!!detail} onCancel={() => !busy && setDetail(null)} footer={null}>
      <Space direction="vertical" style={{ width: '100%' }}>
        {(detail?.refunds || []).map((refund: Row) => <Card key={refund.id} size="small">
          {refund.refundNo} · {refund.status} · {refund.amountFen} 分
          {refund.channel === 'DEV_SIMULATOR' && refund.status !== 'SUCCEEDED' &&
            <Button style={{ marginLeft: 12 }} loading={busy} disabled={busy} onClick={async () => {
              setBusy(true)
              try {
                await api(`/api/admin/refund-attempts/${refund.attemptNo}/simulate`, json('POST', {
                  result: 'SUCCESS', eventKey: `phase5-ui-refund-${refund.attemptNo}`
                }))
                setDetail(await api<Row>(`/api/admin/after-sales/${detail?.id}`))
                await reload()
                message.success('开发模拟退款成功')
              } catch (e) { message.error((e as Error).message) }
              finally { setBusy(false) }
            }}>模拟成功</Button>}
        </Card>)}
        {!detail?.refunds?.length && '尚无退款尝试'}
      </Space>
    </Modal>
  </Card>
}
