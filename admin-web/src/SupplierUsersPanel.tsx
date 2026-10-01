import { useEffect, useRef, useState } from 'react'
import { Alert, Button, Card, Form, Input, Modal, Select, Switch, Table, message } from 'antd'
import { api, json, type Row } from './api'

type AccountPayload = { supplierId: number; username: string; password: string; enabled: boolean }
type Confirmation = { payload: AccountPayload; supplierName: string; editingId?: number }

export default function SupplierUsersPanel() {
  const [items, setItems] = useState<Row[]>([])
  const [suppliers, setSuppliers] = useState<Row[]>([])
  const [editing, setEditing] = useState<Row | null | undefined>()
  const [confirmation, setConfirmation] = useState<Confirmation | null>(null)
  const [busy, setBusy] = useState(false)
  const busyRef = useRef(false)
  const [form] = Form.useForm<AccountPayload>()
  const reload = () => api<Row[]>('/api/admin/supplier-users').then(setItems)

  useEffect(() => {
    reload().catch(e => message.error(e.message))
    api<Row[]>('/api/admin/suppliers').then(setSuppliers).catch(e => message.error(e.message))
  }, [])

  const openCreate = () => {
    form.resetFields()
    form.setFieldsValue({ supplierId: undefined, username: '', password: '', enabled: true })
    setConfirmation(null)
    setEditing(null)
  }
  const openEdit = (account: Row) => {
    form.resetFields()
    form.setFieldsValue({ supplierId: account.supplierId, username: account.username, password: '', enabled: account.enabled })
    setConfirmation(null)
    setEditing(account)
  }
  const prepare = (values: AccountPayload) => {
    const supplier = suppliers.find(s => s.id === values.supplierId)
    if (!supplier) { message.error('请明确选择供应商'); return }
    // Preserve exactly the submitted fields, including a password that is never displayed.
    setConfirmation({ payload: { ...values }, supplierName: supplier.name, editingId: editing?.id })
  }
  const submit = async () => {
    if (!confirmation || busyRef.current) return
    const current = form.getFieldsValue(true) as AccountPayload
    if (JSON.stringify(current) !== JSON.stringify(confirmation.payload)) {
      setConfirmation(null)
      message.warning('表单内容已变化，请重新核对')
      return
    }
    busyRef.current = true
    setBusy(true)
    try {
      const { payload, editingId } = confirmation
      await api(editingId ? `/api/admin/supplier-users/${editingId}` : '/api/admin/supplier-users', json(editingId ? 'PUT' : 'POST', payload))
      setConfirmation(null)
      setEditing(undefined)
      form.resetFields()
      await reload()
      message.success(editingId ? '账号已更新' : '账号已创建')
    } catch (e) { message.error((e as Error).message) }
    finally { busyRef.current = false; setBusy(false) }
  }

  return <Card title="供应商账号" extra={<Button onClick={openCreate}>创建账号</Button>}>
    <Alert type="info" message="账号只能由店主创建并绑定供应商；禁用或重置凭据会使已有会话失效。" />
    <Table rowKey="id" dataSource={items} columns={[
      { title: '供应商', dataIndex: 'supplierName' },
      { title: '用户名', dataIndex: 'username' },
      { title: '启用', dataIndex: 'enabled', render: (v: boolean) => v ? '是' : '否' },
      { title: '凭据版本', dataIndex: 'credentialsVersion' },
      { title: '操作', render: (_: unknown, row: Row) => <Button onClick={() => openEdit(row)}>关联/禁用/重置</Button> }
    ]} />
    <Modal title={editing ? '管理供应商账号' : '创建供应商账号'} open={editing !== undefined}
      onCancel={() => { if (!busy) { setEditing(undefined); setConfirmation(null) } }}
      onOk={() => form.submit()} okText="核对提交内容" okButtonProps={{ disabled: busy }} cancelButtonProps={{ disabled: busy }}>
      <Form form={form} layout="vertical" onFinish={prepare} onValuesChange={() => setConfirmation(null)}>
        <Form.Item name="supplierId" label="供应商" rules={[{ required: true, message: '请明确选择供应商' }]}>
          <Select placeholder="请选择供应商" showSearch optionFilterProp="label" options={suppliers.map(s => ({ value: s.id, label: `${s.name}（ID ${s.id}）` }))} />
        </Form.Item>
        <Form.Item name="username" label="用户名" rules={[{ required: true }]}><Input autoComplete="off" /></Form.Item>
        <Form.Item name="password" label={editing ? '新密码（留空表示不重置）' : '初始密码（至少12位）'} rules={editing ? [] : [{ required: true, min: 12 }]}><Input.Password autoComplete="new-password" /></Form.Item>
        <Form.Item name="enabled" label="启用" valuePropName="checked"><Switch /></Form.Item>
      </Form>
    </Modal>
    <Modal title="确认供应商账号提交" open={!!confirmation} confirmLoading={busy} okButtonProps={{ disabled: busy }}
      onCancel={() => { if (!busy) setConfirmation(null) }} onOk={submit} okText="确认提交">
      {confirmation && <>
        <p>供应商：{confirmation.supplierName}（ID {confirmation.payload.supplierId}）</p>
        <p>用户名：{confirmation.payload.username}</p>
        <p>账号角色：供应商账号</p>
        <p>启用：{confirmation.payload.enabled ? '是' : '否'}</p>
        <p>密码仅随本次确认提交，不在确认页显示。</p>
      </>}
    </Modal>
  </Card>
}
