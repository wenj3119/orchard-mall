import { useEffect, useRef, useState } from 'react'
import { Alert, Button, Card, Cascader, Form, Image, Input, InputNumber, Layout, Modal, Popconfirm, Select, Space, Switch, Table, Tabs, Tag, Typography, Upload, message } from 'antd'
import type { UploadProps } from 'antd'
import { api, json, uploadAdminMedia, type Row } from './api'
import SettlementOperations from './SettlementOperations'
import AfterSaleDevRefundPanel from './AfterSaleDevRefundPanel'
import SupplierUsersPanel from './SupplierUsersPanel'
import { displayAddress } from './addressDisplay'

const { Title, Text } = Typography
const fen = (n: number) => `¥${(n / 100).toFixed(2)}`
const priceInput = (label: string, name: string) => <Form.Item label={label} name={name} rules={[{ required: true }, { type: 'integer', min: 0, message: '请输入非负整数分' }]}><InputNumber min={0} precision={0} addonAfter="分" style={{ width: '100%' }} /></Form.Item>
function AuthImage({ path }: { path: string }) {
  const [url, setUrl] = useState('')
  const [failed, setFailed] = useState(false)
  useEffect(() => {
    let objectUrl = ''
    setFailed(false)
    setUrl('')
    const token = localStorage.getItem('admin_token')
    fetch((import.meta.env.VITE_API_BASE || '') + path, { headers: token ? { Authorization: `Bearer ${token}` } : {} })
      .then(r => r.ok ? r.blob() : Promise.reject(new Error('图片加载失败')))
      .then(blob => { objectUrl = URL.createObjectURL(blob); setUrl(objectUrl) })
      .catch(() => setFailed(true))
    return () => { if (objectUrl) URL.revokeObjectURL(objectUrl) }
  }, [path])
  return url ? <Image className="thumb" src={url} alt="图片" preview={{ mask: '查看图片' }} /> : <span>{failed ? '图片加载失败，请检查登录状态' : '加载中'}</span>
}

function Login({ onLogin }: { onLogin: () => void }) {
  const [busy, setBusy] = useState(false)
  return <div className="login"><Card><Title level={3}>商城管理后台</Title><Text type="secondary">使用初始化的管理员账号登录</Text>
    <Form layout="vertical" style={{ marginTop: 20 }} onFinish={async (values) => {
      setBusy(true)
      try { const result = await api<{ token: string }>('/api/auth/login', json('POST', values)); localStorage.setItem('admin_token', result.token); onLogin() }
      catch (e) { message.error((e as Error).message) } finally { setBusy(false) }
    }}>
      <Form.Item name="username" label="账号" rules={[{ required: true }]} initialValue="admin"><Input /></Form.Item>
      <Form.Item name="password" label="密码" rules={[{ required: true }]}><Input.Password /></Form.Item>
      <Button type="primary" htmlType="submit" loading={busy} block>登录</Button>
    </Form></Card></div>
}
function StorePanel() {
  const [form] = Form.useForm()
  const [loading, setLoading] = useState(true)
  useEffect(() => { api('/api/admin/store').then(v => form.setFieldsValue(v)).catch(e => message.error(e.message)).finally(() => setLoading(false)) }, [form])
  return <Card title="店铺配置" loading={loading}><Form form={form} layout="vertical" onFinish={async values => {
    try { await api('/api/admin/store', json('PUT', values)); message.success('已保存') } catch (e) { message.error((e as Error).message) }
  }}>
    <Form.Item name="name" label="店名" rules={[{ required: true }]}><Input maxLength={120} /></Form.Item>
    <Form.Item name="logoUrl" label="Logo URL"><Input placeholder="公开图片地址" /></Form.Item>
    <Upload showUploadList={false} accept="image/jpeg,image/png,image/webp" customRequest={async ({ file, onSuccess, onError }) => {
      try { const media = await uploadAdminMedia(file as File); form.setFieldValue('logoUrl', media.url); onSuccess?.(media); message.success('Logo 已上传，请保存店铺配置') }
      catch (e) { onError?.(e as Error); message.error((e as Error).message) }
    }}><Button style={{ marginBottom: 16 }}>上传 Logo</Button></Upload>
    <Form.Item name="themeColor" label="主题色" rules={[{ required: true }, { pattern: /^#[\da-fA-F]{6}$/, message: '请输入 #RRGGBB' }]}><Input /></Form.Item>
    <Form.Item name="contactPhone" label="联系电话"><Input /></Form.Item>
    <Form.Item name="description" label="店铺介绍"><Input.TextArea rows={3} /></Form.Item>
    <Button type="primary" htmlType="submit">保存配置</Button>
  </Form></Card>
}
function CategoriesPanel() {
  const [items, setItems] = useState<Row[]>([])
  const [editing, setEditing] = useState<Row | null>(null)
  const [open, setOpen] = useState(false)
  const [form] = Form.useForm()
  const reload = () => api<Row[]>('/api/admin/categories').then(setItems).catch(e => message.error(e.message))
  useEffect(() => { reload() }, [])
  return <Card title="分类" extra={<Button type="primary" onClick={() => { setEditing(null); form.setFieldsValue({ enabled: true, sortOrder: 0, name: '' }); setOpen(true) }}>新增分类</Button>}>
    <Table rowKey="id" dataSource={items} pagination={false} columns={[
      { title: '名称', dataIndex: 'name' }, { title: '排序', dataIndex: 'sortOrder' },
      { title: '状态', dataIndex: 'enabled', render: (v: boolean) => v ? '启用' : '停用' },
      { title: '操作', render: (_: unknown, item: Row) => <Button onClick={() => { setEditing(item); form.setFieldsValue(item); setOpen(true) }}>编辑</Button> }
    ]} />
    <Modal title={editing ? '编辑分类' : '新增分类'} open={open} onCancel={() => setOpen(false)} onOk={() => form.submit()} destroyOnClose>
      <Form form={form} layout="vertical" onFinish={async values => {
        try { await api(editing ? `/api/admin/categories/${editing.id}` : '/api/admin/categories', json(editing ? 'PUT' : 'POST', values)); setOpen(false); reload() }
        catch (e) { message.error((e as Error).message) }
      }}>
        <Form.Item name="name" label="分类名称" rules={[{ required: true }]}><Input /></Form.Item>
        <Form.Item name="sortOrder" label="排序"><InputNumber /></Form.Item>
        <Form.Item name="enabled" label="启用" valuePropName="checked"><Switch /></Form.Item>
      </Form>
    </Modal>
  </Card>
}
function SupplyPanel() {
  const [suppliers, setSuppliers] = useState<Row[]>([])
  const [origins, setOrigins] = useState<Row[]>([])
  const [type, setType] = useState<'supplier' | 'origin' | null>(null)
  const [editing, setEditing] = useState<Row | null>(null)
  const [supplierFilter, setSupplierFilter] = useState<number | undefined>()
  const [form] = Form.useForm()
  const reload = () => Promise.all([api<Row[]>('/api/admin/suppliers'), api<Row[]>('/api/admin/origins')]).then(([s, o]) => { setSuppliers(s); setOrigins(o) }).catch(e => message.error(e.message))
  useEffect(() => { reload() }, [])
  const open = async (t: 'supplier' | 'origin', row?: Row) => {
    try {
      if (t === 'origin') await reload()
      setType(t); setEditing(row || null)
      form.setFieldsValue(row || { enabled: true, isDefault: false, sourceType: 'FARMER', name: '', contactName: '', contactPhone: '', label: '', provinceCode: '', province: '', cityCode: '', city: '', districtCode: '', district: '', address: '', supplierId: supplierFilter })
    } catch (e) { message.error((e as Error).message) }
  }
  return <Space direction="vertical" style={{ width: '100%' }}>
    <Card title="供应商" extra={<Button type="primary" onClick={() => open('supplier')}>新增供应商</Button>}>
      <Table rowKey="id" dataSource={suppliers} pagination={false} columns={[
        { title: '名称', dataIndex: 'name' }, { title: '类型', dataIndex: 'sourceType', render: (v: string) => ({ SELF: '自营果园', FARMER: '农户', FACTORY: '厂家' })[v] || v },
        { title: '联系人', dataIndex: 'contactName' }, { title: '电话', dataIndex: 'contactPhone' },
        { title: '状态', dataIndex: 'enabled', render: (v: boolean) => v ? '启用' : '停用' },
        { title: '操作', render: (_: unknown, row: Row) => <Space><Button onClick={() => open('supplier', row)}>编辑</Button><Button onClick={() => setSupplierFilter(row.id)}>发货地</Button></Space> }
      ]} />
    </Card>
    <Card title={supplierFilter ? `${suppliers.find(s => s.id === supplierFilter)?.name || ''} · 发货地` : '全部发货地'} extra={<Space>{supplierFilter && <Button onClick={() => setSupplierFilter(undefined)}>查看全部</Button>}<Button type="primary" onClick={() => open('origin')}>新增发货地</Button></Space>}>
      <Table rowKey="id" dataSource={supplierFilter ? origins.filter(o => o.supplierId === supplierFilter) : origins} pagination={false} columns={[
        { title: '供应商', dataIndex: 'supplierName' }, { title: '名称', dataIndex: 'label' }, { title: '省', dataIndex: 'province' },
        { title: '市 / 区', render: (_: unknown, row: Row) => `${row.city} ${row.district}` }, { title: '详细地址', dataIndex: 'address' },
        { title: '发货联系人', render: (_: unknown, row: Row) => `${row.contactName} ${row.contactPhone}` },
        { title: '状态', render: (_: unknown, row: Row) => <Space>{row.isDefault && <Tag color="blue">默认</Tag>}<Tag color={row.enabled ? 'green' : 'default'}>{row.enabled ? '启用' : '停用'}</Tag></Space> },
        { title: '关联供货', dataIndex: 'usageSupplyCount' },
        { title: '操作', render: (_: unknown, row: Row) => <Space><Button onClick={() => open('origin', row)}>编辑</Button>{!row.isDefault && row.enabled && <Button onClick={async () => { try { await api(`/api/admin/origins/${row.id}/default`, { method: 'PUT' }); reload() } catch (e) { message.error((e as Error).message) } }}>设默认</Button>}<Popconfirm title={`停用发货地「${row.label}」？`} description={`将影响 ${row.usageSupplyCount || 0} 条供货关系的新试算与下单；历史订单不变。`} okText="确认停用" cancelText="暂不停用" disabled={!row.enabled} onConfirm={async () => { try { await api(`/api/admin/origins/${row.id}`, json('PUT', { ...row, isDefault: false, enabled: false })); reload() } catch (e) { message.error((e as Error).message) } }}><Button disabled={!row.enabled} danger>停用</Button></Popconfirm></Space> }
      ]} />
    </Card>
    <Modal title={type === 'supplier' ? '供应商' : '发货地'} open={!!type} onCancel={() => setType(null)} onOk={() => form.submit()} destroyOnClose>
      <Form form={form} layout="vertical" onFinish={async values => {
        if (!type) return
        const save = async () => { try {
          const endpoint = type === 'supplier' ? 'suppliers' : 'origins'
          await api(`/api/admin/${endpoint}${editing ? '/' + editing.id : ''}`, json(editing ? 'PUT' : 'POST', values))
          setType(null); reload()
        } catch (e) { message.error((e as Error).message) } }
        if (editing?.enabled && !values.enabled) Modal.confirm({ title: `停用${type === 'supplier' ? '供应商' : '发货地'}「${editing.name || editing.label}」？`, content: `将影响其关联供货来源的新报价与下单；历史订单快照不变。`, okText: '确认停用', cancelText: '继续编辑', onOk: save })
        else await save()
      }}>
        {type === 'supplier' ? <>
          <Form.Item name="name" label="名称" rules={[{ required: true }]}><Input /></Form.Item>
          <Form.Item name="sourceType" label="供货类型" rules={[{ required: true }]}><Select options={[{ label: '自营果园', value: 'SELF' }, { label: '外部农户', value: 'FARMER' }, { label: '厂家', value: 'FACTORY' }]} /></Form.Item>
          <Form.Item name="contactName" label="联系人" rules={[{ required: true }]}><Input /></Form.Item>
          <Form.Item name="contactPhone" label="联系电话" rules={[{ required: true }]}><Input /></Form.Item>
          <Form.Item name="enabled" label="启用" valuePropName="checked"><Switch /></Form.Item>
        </> : <>
          {(editing?.usageSupplyCount || 0) > 0 && <Alert type="warning" showIcon message={`已有 ${editing?.usageSupplyCount} 条供货关系；不能变更所属供应商或删除。停用只影响新试算、下单和补发，不改历史快照。`} />}
          <Form.Item name="supplierId" label="供应商" rules={[{ required: true }]}><Select disabled={editing?.usageSupplyCount > 0} showSearch optionFilterProp="label" options={suppliers.map(s => ({ label: s.name, value: s.id }))} /></Form.Item>
          <Form.Item name="label" label="发货地名称" rules={[{ required: true }]}><Input /></Form.Item>
          <Space style={{display:'flex'}}><Form.Item name="provinceCode" label="省编码" rules={[{ required: true },{pattern:/^\d{6}$/}]}><Input /></Form.Item><Form.Item name="province" label="省" rules={[{ required: true }]}><Input /></Form.Item></Space>
          <Space style={{display:'flex'}}><Form.Item name="cityCode" label="市编码" rules={[{ required: true },{pattern:/^\d{6}$/}]}><Input /></Form.Item><Form.Item name="city" label="市" rules={[{ required: true }]}><Input /></Form.Item></Space>
          <Space style={{display:'flex'}}><Form.Item name="districtCode" label="区县编码" rules={[{ required: true },{pattern:/^\d{6}$/}]}><Input /></Form.Item><Form.Item name="district" label="区县" rules={[{ required: true }]}><Input /></Form.Item></Space>
          <Form.Item name="address" label="详细地址" rules={[{ required: true }]}><Input /></Form.Item>
          <Form.Item name="contactName" label="发货联系人" rules={[{ required: true }]}><Input /></Form.Item>
          <Form.Item name="contactPhone" label="联系电话" rules={[{ required: true }]}><Input /></Form.Item>
          <Form.Item name="isDefault" label="默认发货地（仅用于新建供货关系预填）" valuePropName="checked"><Switch /></Form.Item>
          <Form.Item name="enabled" label="启用" valuePropName="checked"><Switch /></Form.Item>
        </>}
      </Form>
    </Modal>
  </Space>
}
function ProductsPanel() {
  const [items, setItems] = useState<Row[]>([])
  const [categories, setCategories] = useState<Row[]>([])
  const [suppliers, setSuppliers] = useState<Row[]>([])
  const [origins, setOrigins] = useState<Row[]>([])
  const [shippingTemplates, setShippingTemplates] = useState<Row[]>([])
  const [selected, setSelected] = useState<Row | null>(null)
  const [productOpen, setProductOpen] = useState(false)
  const [editingProduct, setEditingProduct] = useState<Row | null>(null)
  const [skuOpen, setSkuOpen] = useState(false)
  const [editingSku, setEditingSku] = useState<Row | null>(null)
  const [supplySku, setSupplySku] = useState<Row | null>(null)
  const [editingSupply, setEditingSupply] = useState<Row | null>(null)
  const [supplies, setSupplies] = useState<Row[]>([])
  const [form] = Form.useForm()
  const [skuForm] = Form.useForm()
  const [supplyForm] = Form.useForm()
  const [quickOriginOpen, setQuickOriginOpen] = useState(false)
  const [quickOriginForm] = Form.useForm()
  const selectedSupplySupplier = Form.useWatch('supplierId', supplyForm)
  const reload = () => api<Row[]>('/api/admin/products').then(setItems).catch(e => message.error(e.message))
  const loadSelected = (id: number) => api<Row>(`/api/admin/products/${id}`).then(setSelected).catch(e => message.error(e.message))
  const refreshReferences = async () => {
    const [nextCategories, nextSuppliers, nextOrigins, nextTemplates] = await Promise.all([
      api<Row[]>('/api/admin/categories'), api<Row[]>('/api/admin/suppliers'), api<Row[]>('/api/admin/origins'), api<Row[]>('/api/admin/shipping-templates')
    ])
    setCategories(nextCategories); setSuppliers(nextSuppliers); setOrigins(nextOrigins); setShippingTemplates(nextTemplates)
  }
  useEffect(() => { reload(); refreshReferences().catch(e => message.error(e.message)) }, [])
  const upload: UploadProps = { showUploadList: false, customRequest: async ({ file, onSuccess, onError }) => {
    if (!selected) return
    try {
      const media = await uploadAdminMedia(file as File)
      await api(`/api/admin/products/${selected.id}/images`, json('PUT', [...selected.images.map((v: Row) => v.id), media.id]))
      await loadSelected(selected.id); reload(); onSuccess?.(media); message.success('上传成功')
    } catch (e) { onError?.(e as Error); message.error((e as Error).message) }
  } }
  return <Space direction="vertical" style={{ width: '100%' }}>
    <Card title="商品" extra={<Button type="primary" onClick={async () => { try { await refreshReferences(); setEditingProduct(null); form.resetFields(); setProductOpen(true) } catch (e) { message.error((e as Error).message) } }}>新增商品</Button>}>
      <Alert type="info" showIcon style={{ marginBottom: 16 }} message="上架需要图片、可售 SKU 和启用供应商的默认供货关系；下单还需库存、重量和运费模板。" />
      <Table rowKey="id" dataSource={items} columns={[
        { title: '图片', dataIndex: 'imageUrl', render: (v?: string) => v ? <AuthImage path={v} /> : '待上传' },
        { title: '商品', dataIndex: 'title' }, { title: '分类', dataIndex: 'categoryName' },
        { title: '起价', dataIndex: 'minPriceFen', render: (v?: number) => v == null ? '—' : fen(v) },
        { title: '状态', dataIndex: 'published', render: (v: boolean) => <Tag color={v ? 'green' : 'default'}>{v ? '已上架' : '未上架'}</Tag> },
        { title: '操作', render: (_: unknown, row: Row) => <Space>
          <Button onClick={() => loadSelected(row.id)}>管理</Button>
          <Popconfirm title={row.published ? `下架「${row.title}」？` : `上架「${row.title}」？`} description={row.published ? '下架后新用户不能浏览或下单，已有订单不变。' : '上架后商品将对消费者可见，库存和运费仍按实时配置校验。'} okText={row.published ? '确认下架' : '确认上架'} cancelText="暂不操作" onConfirm={async () => {
            try { await api(`/api/admin/products/${row.id}/publication`, json('PUT', { published: !row.published })); reload(); if(selected?.id === row.id) loadSelected(row.id) }
            catch (e) { message.error((e as Error).message) }
          }}><Button>{row.published ? '下架' : '上架'}</Button></Popconfirm>
        </Space> }
      ]} />
    </Card>
    {selected && <Card title={`商品 #${selected.id} · ${selected.title}`} extra={<Space><Button onClick={async () => { try { await refreshReferences(); setEditingProduct(selected); form.setFieldsValue(selected); setProductOpen(true) } catch (e) { message.error((e as Error).message) } }}>编辑商品</Button><Button onClick={() => setSelected(null)}>关闭</Button></Space>}>
      <Title level={5}>商品图片</Title>
      <Space wrap>{selected.images.map((img: Row) => <div key={img.id}><AuthImage path={img.url} /><br /><Popconfirm title="移除这张商品图片？" description="商品图片列表会立即更新；若是最后一张图片，商品可能不再满足上架条件。" okText="确认移除" cancelText="保留" onConfirm={async () => {
        try { await api(`/api/admin/products/${selected.id}/images`, json('PUT', selected.images.filter((x: Row) => x.id !== img.id).map((x: Row) => x.id))); loadSelected(selected.id); reload() }
        catch (e) { message.error((e as Error).message) }
      }}><Button size="small" danger>移除</Button></Popconfirm></div>)}<Upload {...upload} accept="image/jpeg,image/png,image/webp"><Button>上传图片</Button></Upload></Space>
      <Title level={5} style={{ marginTop: 24 }}>规格 SKU</Title>
      <Button onClick={() => { setEditingSku(null); skuForm.setFieldsValue({ active: true, retailPriceFen: 0, netWeightG: 0, billableWeightG: 0, specJson: '{}' }); setSkuOpen(true) }}>新增 SKU</Button>
      <Table rowKey="id" dataSource={selected.skus} pagination={false} columns={[
        { title: '编码', dataIndex: 'code' }, { title: '规格 JSON', dataIndex: 'specJson' },
        { title: '零售价', dataIndex: 'retailPriceFen', render: fen },
        { title: '可售', dataIndex: 'active', render: (v: boolean) => v ? '是' : '否' },
        { title: '操作', render: (_: unknown, row: Row) => <Space><Button onClick={() => { setEditingSku(row); skuForm.setFieldsValue(row); setSkuOpen(true) }}>编辑</Button><Button onClick={async () => {
          try { await refreshReferences(); setSupplySku(row); setEditingSupply(null); api<Row[]>(`/api/admin/skus/${row.id}/supplies`).then(setSupplies); supplyForm.setFieldsValue({ isDefault: true, supplyPriceFen: 0 }) }
          catch (e) { message.error((e as Error).message) }
        }}>供货关系</Button></Space> }
      ]} />
    </Card>}
    <Modal title={editingProduct ? '编辑商品' : '新增商品'} open={productOpen} onCancel={() => setProductOpen(false)} onOk={() => form.submit()}>
      <Form form={form} layout="vertical" onFinish={async values => {
        try {
          const result = await api<Row>(editingProduct ? `/api/admin/products/${editingProduct.id}` : '/api/admin/products', json(editingProduct ? 'PUT' : 'POST', values))
          setProductOpen(false); reload(); loadSelected(result.id)
        } catch (e) { message.error((e as Error).message) }
      }}>
        <Form.Item name="categoryId" label="分类" rules={[{ required: true }]}><Select showSearch optionFilterProp="label" options={categories.map(c => ({ label: c.name, value: c.id }))} /></Form.Item>
        <Form.Item name="title" label="商品名称" rules={[{ required: true }]}><Input /></Form.Item>
        <Form.Item name="originDescription" label="商品产地（独立于发货地）"><Input maxLength={240} placeholder="例如：陕西洛川；编辑发货地不会改变此字段" /></Form.Item>
        <Form.Item name="description" label="描述"><Input.TextArea rows={3} /></Form.Item>
      </Form>
    </Modal>
    <Modal title={editingSku ? '编辑 SKU' : '新增 SKU'} open={skuOpen} onCancel={() => setSkuOpen(false)} onOk={() => skuForm.submit()}>
      <Form form={skuForm} layout="vertical" onFinish={async values => {
        try {
          JSON.parse(values.specJson)
          await api(editingSku ? `/api/admin/skus/${editingSku.id}` : `/api/admin/products/${selected?.id}/skus`, json(editingSku ? 'PUT' : 'POST', values))
          setSkuOpen(false); if(selected) loadSelected(selected.id); reload()
        } catch (e) { message.error((e as Error).message) }
      }}>
        <Form.Item name="code" label="SKU 编码" rules={[{ required: true }]}><Input /></Form.Item>
        <Form.Item name="specJson" label="规格 JSON" rules={[{ required: true }]}><Input.TextArea placeholder='{"包装":"5斤装"}' /></Form.Item>
        {priceInput('零售价', 'retailPriceFen')}
        <Form.Item name="netWeightG" label="展示净重" rules={[{ required: true }]}><InputNumber min={0} precision={0} addonAfter="克" style={{ width: '100%' }} /></Form.Item>
        <Form.Item name="billableWeightG" label="计费重量" rules={[{ required: true }]}><InputNumber min={0} precision={0} addonAfter="克" style={{ width: '100%' }} /></Form.Item>
        <Form.Item name="active" label="可售" valuePropName="checked"><Switch /></Form.Item>
      </Form>
    </Modal>
    <Modal title={`SKU ${supplySku?.code || ''} 的供货关系`} open={!!supplySku} onCancel={() => setSupplySku(null)} footer={null} width={700}>
      <Table rowKey="id" dataSource={supplies} pagination={false} columns={[
        { title: '供应商', dataIndex: 'supplierName' }, { title: '发货地', dataIndex: 'originLabel' },
        { title: '运费模板', dataIndex: 'shippingTemplateName' }, { title: '供货价', dataIndex: 'supplyPriceFen', render: fen },
        { title: '该来源库存', render: (_: unknown, row: Row) => `${row.availableQty} 可售 / ${row.onHandQty} 实物 / ${row.reservedQty} 预占` },
        { title: '默认', dataIndex: 'isDefault', render: (v: boolean) => v ? '是' : '否' },
        { title: '操作', render: (_: unknown, row: Row) => <Space>
          <Button onClick={() => { setEditingSupply(row); supplyForm.setFieldsValue(row) }}>编辑</Button>
          {!row.isDefault && <Popconfirm title={`将 ${row.supplierName} · ${row.originLabel} 设为 ${supplySku?.code} 的默认供货？`} description="后续新报价、下单和库存预占将使用此来源；历史订单快照不变。" okText="确认切换" cancelText="暂不切换" onConfirm={async () => {
            try { await api(`/api/admin/skus/${supplySku?.id}/supplies/${row.id}/default`, { method: 'PUT' }); setSupplies(await api<Row[]>(`/api/admin/skus/${supplySku?.id}/supplies`)) }
            catch (e) { message.error((e as Error).message) }
          }}><Button>设为默认</Button></Popconfirm>}
          <Popconfirm title={`删除 ${row.supplierName} · ${row.originLabel} 的供货关系？`} description="该来源不再参与新报价和下单；已有订单与库存流水不改写。" okText="确认删除" cancelText="保留" onConfirm={async () => {
            try { await api(`/api/admin/skus/${supplySku?.id}/supplies/${row.id}`, { method: 'DELETE' }); setSupplies(await api<Row[]>(`/api/admin/skus/${supplySku?.id}/supplies`)) }
            catch (e) { message.error((e as Error).message) }
          }}><Button danger>删除</Button></Popconfirm>
        </Space> }
      ]} />
      <Form form={supplyForm} layout="vertical" onFinish={async values => {
        const save = async () => { try {
          await api(`/api/admin/skus/${supplySku?.id}/supplies${editingSupply ? '/' + editingSupply.id : ''}`, json(editingSupply ? 'PUT' : 'POST', values))
          setSupplies(await api<Row[]>(`/api/admin/skus/${supplySku?.id}/supplies`))
          setEditingSupply(null); supplyForm.setFieldsValue({ supplierId: undefined, originId: undefined, shippingTemplateId: undefined, supplyPriceFen: 0, isDefault: false })
          message.success('已保存')
        } catch (e) { message.error((e as Error).message) } }
        if ((editingSupply && ['supplierId', 'originId', 'shippingTemplateId', 'isDefault'].some(field => values[field] !== editingSupply[field])) || (!editingSupply && values.isDefault))
          Modal.confirm({ title: `更改 ${supplySku?.code} 的供货关系？`, content: `供应商、发货地、运费模板或默认供货来源的更改将影响后续新报价、下单和库存预占；历史订单快照不变。`, okText: '确认更改', cancelText: '继续编辑', onOk: save })
        else await save()
      }}>
        {editingSupply?.sourceLocked && <Alert type="warning" showIcon message={editingSupply.sourceLockReason} action={<Button onClick={() => { setEditingSupply(null); supplyForm.setFieldsValue({supplierId:undefined,originId:undefined,shippingTemplateId:undefined,supplyPriceFen:0,isDefault:false}) }}>新建供货关系</Button>} />}
        <Form.Item name="supplierId" label="供应商" rules={[{ required: true }]}><Select disabled={!!editingSupply?.sourceLocked} showSearch optionFilterProp="label" onChange={(supplierId) => { supplyForm.setFieldValue('originId', undefined); if (!editingSupply) { const preferred = origins.find(o => o.supplierId === supplierId && o.enabled && o.isDefault); if (preferred) supplyForm.setFieldValue('originId', preferred.id) } }} options={suppliers.filter(s => s.enabled).map(s => ({ label: s.name, value: s.id }))} /></Form.Item>
        <Form.Item name="originId" label="有效发货地" rules={[{ required: true }]}><Select disabled={!!editingSupply?.sourceLocked || !selectedSupplySupplier} showSearch optionFilterProp="label" placeholder={selectedSupplySupplier && !origins.some(o => o.supplierId === selectedSupplySupplier && o.enabled) ? '该供应商没有有效发货地，请先创建' : '请选择'} options={origins.filter(o => o.supplierId === selectedSupplySupplier && o.enabled).map(o => ({ label: `${o.label}${o.isDefault ? '（默认）' : ''}`, value: o.id }))} /></Form.Item>
        <Button disabled={!selectedSupplySupplier} onClick={() => { const supplier = suppliers.find(s => s.id === selectedSupplySupplier); quickOriginForm.setFieldsValue({supplierId:selectedSupplySupplier,enabled:true,isDefault:!origins.some(o => o.supplierId === selectedSupplySupplier && o.isDefault),contactName:supplier?.contactName,contactPhone:supplier?.contactPhone}); setQuickOriginOpen(true) }}>新建该供应商发货地</Button>
        <Form.Item name="shippingTemplateId" label="适用运费模板" rules={[{ required: true }]}><Select showSearch optionFilterProp="label" options={shippingTemplates.filter(t => t.enabled).map(t => ({label:`${t.name} v${t.version}`,value:t.id}))} /></Form.Item>
        {priceInput('供货价', 'supplyPriceFen')}
        <Form.Item name="isDefault" label="设为默认供货来源" valuePropName="checked"><Switch /></Form.Item>
        <Space><Button type="primary" htmlType="submit">{editingSupply ? '保存修改' : '添加供货关系'}</Button>
          {editingSupply && <Button onClick={() => { setEditingSupply(null); supplyForm.resetFields() }}>取消编辑</Button>}</Space>
      </Form>
      <Modal title="新建发货地" open={quickOriginOpen} onCancel={() => setQuickOriginOpen(false)} onOk={() => quickOriginForm.submit()} destroyOnClose><Form form={quickOriginForm} layout="vertical" onFinish={async values => { try { const created=await api<Row>('/api/admin/origins',json('POST',values)); await refreshReferences(); supplyForm.setFieldValue('originId',created.id); setQuickOriginOpen(false); message.success('发货地已创建并选中') } catch(e) { message.error((e as Error).message) } }}>
        <Form.Item name="supplierId" hidden><Input/></Form.Item><Form.Item name="label" label="名称" rules={[{required:true}]}><Input/></Form.Item>
        <Space style={{display:'flex'}}><Form.Item name="provinceCode" label="省编码" rules={[{required:true},{pattern:/^\d{6}$/}]}><Input/></Form.Item><Form.Item name="province" label="省" rules={[{required:true}]}><Input/></Form.Item></Space>
        <Space style={{display:'flex'}}><Form.Item name="cityCode" label="市编码" rules={[{required:true},{pattern:/^\d{6}$/}]}><Input/></Form.Item><Form.Item name="city" label="市" rules={[{required:true}]}><Input/></Form.Item></Space>
        <Space style={{display:'flex'}}><Form.Item name="districtCode" label="区县编码" rules={[{required:true},{pattern:/^\d{6}$/}]}><Input/></Form.Item><Form.Item name="district" label="区县" rules={[{required:true}]}><Input/></Form.Item></Space>
        <Form.Item name="address" label="详细地址" rules={[{required:true}]}><Input/></Form.Item><Form.Item name="contactName" label="发货联系人" rules={[{required:true}]}><Input/></Form.Item><Form.Item name="contactPhone" label="联系电话" rules={[{required:true}]}><Input/></Form.Item><Form.Item name="isDefault" label="默认" valuePropName="checked"><Switch/></Form.Item><Form.Item name="enabled" label="启用" valuePropName="checked"><Switch/></Form.Item>
      </Form></Modal>
    </Modal>
  </Space>
}
function InventoryPanel() {
  const [items, setItems] = useState<Row[]>([])
  const [editing, setEditing] = useState<Row | null>(null)
  const [movements, setMovements] = useState<Row[]>([])
  const [form] = Form.useForm()
  const reload = () => api<Row[]>('/api/admin/inventory').then(setItems).catch(e => message.error(e.message))
  useEffect(() => { reload() }, [])
  return <Card title="默认供货库存" extra={<Text type="secondary">可售库存 = 实物库存 − 已预占</Text>}>
    <Alert type="info" showIcon style={{ marginBottom: 16 }} message="库存归属于 SKU 的供货来源；消费者下单只使用该 SKU 当前默认供货来源。MySQL 条件更新负责防止超卖。" />
    <Table rowKey="supplyId" dataSource={items} columns={[
      { title: '商品 / SKU', render: (_:unknown,r:Row)=><>{r.productTitle}<br/><Text type="secondary">{r.skuCode}</Text></> },
      { title: '供货', render: (_:unknown,r:Row)=>`${r.supplierName} · ${r.originLabel}` },
      { title: '默认', dataIndex: 'isDefault', render:(v:boolean)=>v?'是':'否' },
      { title: '实物', dataIndex: 'onHandQty' }, { title: '预占', dataIndex: 'reservedQty' }, { title: '可售', dataIndex: 'availableQty' },
      { title: '重量', render:(_:unknown,r:Row)=>`净重 ${r.netWeightG}g / 计费 ${r.billableWeightG}g` },
      { title: '操作', render:(_:unknown,r:Row)=><Space><Button onClick={()=>{setEditing(r);form.setFieldsValue({delta:0,reason:''})}}>调整</Button><Button onClick={async()=>{setEditing(r);setMovements(await api<Row[]>(`/api/admin/inventory/${r.supplyId}/movements`))}}>流水</Button></Space> }
    ]}/>
    <Modal title={`库存 · ${editing?.productTitle||''}`} open={!!editing} onCancel={()=>{setEditing(null);setMovements([])}} footer={null} width={760}>
      <Form form={form} layout="inline" onFinish={values=>Modal.confirm({title:`调整库存 · ${editing?.productTitle || ''}`,content:`${editing?.skuCode || ''} 的实物库存将${values.delta >= 0 ? '增加' : '减少'} ${Math.abs(values.delta)} 件；当前可售 ${editing?.availableQty ?? '未知'} 件。原因：${values.reason}`,okText:'确认调整',cancelText:'继续核对',onOk:async()=>{try{await api(`/api/admin/inventory/${editing?.supplyId}/adjust`,json('POST',values));message.success('库存已调整');reload();setMovements(await api<Row[]>(`/api/admin/inventory/${editing?.supplyId}/movements`))}catch(e){message.error((e as Error).message)}}})}>
        <Form.Item name="delta" label="增减数量" rules={[{required:true}]}><InputNumber precision={0}/></Form.Item><Form.Item name="reason" label="原因" rules={[{required:true,min:3}]}><Input maxLength={200}/></Form.Item><Button type="primary" htmlType="submit">确认调整</Button>
      </Form>
      {!!movements.length&&<Table style={{marginTop:20}} rowKey="id" dataSource={movements} pagination={false} columns={[{title:'时间',dataIndex:'createdAt'},{title:'实物变化',dataIndex:'deltaOnHand'},{title:'预占变化',dataIndex:'deltaReserved'},{title:'原因',dataIndex:'reason'},{title:'操作者',dataIndex:'actor'}]}/>} 
    </Modal>
  </Card>
}
type RegionNode = { code: string; name: string; children: RegionNode[] }
const cents = (value: string) => {
  if (!/^(0|[1-9]\d{0,10})(\.\d{1,2})?$/.test(value)) throw new Error('金额：请输入非负元金额，最多两位小数')
  const [yuan, fraction = ''] = value.split('.')
  return Number(yuan) * 100 + Number(fraction.padEnd(2, '0'))
}
const yuan = (value?: number | null) => value == null ? '' : (value / 100).toFixed(2)
function ShippingPanel() {
  const [items, setItems] = useState<Row[]>([])
  const [supplies, setSupplies] = useState<Row[]>([])
  const [regions, setRegions] = useState<RegionNode[]>([])
  const [regionError, setRegionError] = useState(false)
  const [editing, setEditing] = useState<Row | null>(null)
  const [ruleTemplate, setRuleTemplate] = useState<Row | null>(null)
  const [editingRule, setEditingRule] = useState<Row | null>(null)
  const [busy, setBusy] = useState(false)
  const [form] = Form.useForm()
  const [ruleForm] = Form.useForm()
  const [assignForm] = Form.useForm()
  const reload = () => Promise.all([api<Row[]>('/api/admin/shipping-templates'), api<Row[]>('/api/admin/inventory')]).then(([templates, inventory]) => { setItems(templates); setSupplies(inventory) }).catch(e => message.error(e.message))
  const loadRegions = () => { setRegionError(false); api<RegionNode[]>('/api/public/regions').then(setRegions).catch(() => setRegionError(true)) }
  useEffect(() => { reload(); loadRegions() }, [])
  const pathFor = (code: string) => {
    if (code === '000000') return []
    for (const province of regions) {
      if (province.code === code) return [code]
      for (const city of province.children) {
        if (city.code === code) return [province.code, code]
        for (const district of city.children) if (district.code === code) return [province.code, city.code, code]
      }
    }
    return undefined
  }
  const options = regions.map(province => ({ value: province.code, label: province.name, children: province.children.map(city => ({ value: city.code, label: city.name, children: city.children.map(district => ({ value: district.code, label: district.name })) })) }))
  const openRule = (rule?: Row) => {
    if (!regions.length) return message.error('地区数据尚未加载，请重试')
    setEditingRule(rule || null)
    ruleForm.setFieldsValue(rule ? { scope: rule.regionCode === '000000' ? '000000' : 'region', regionPath: pathFor(rule.regionCode), blocked: rule.blocked, firstWeightG: rule.firstWeightG, firstFeeYuan: yuan(rule.firstFeeFen), stepWeightG: rule.stepWeightG, stepFeeYuan: yuan(rule.stepFeeFen) } : { scope: '000000', regionPath: [], blocked: false, firstWeightG: 1000, firstFeeYuan: '0.00', stepWeightG: 1000, stepFeeYuan: '0.00' })
  }
  const saveRule = async (values: Row) => {
    if (!ruleTemplate || busy) return
    const regionCode = values.scope === '000000' ? '000000' : values.regionPath?.at(-1)
    if (!regionCode) return message.error('地区：请选择省、市或区县')
    if (ruleTemplate.rules.some((rule: Row) => rule.regionCode === regionCode && rule.id !== editingRule?.id)) return message.error('该模板已有此地区规则，请编辑已有规则')
    setBusy(true)
    try {
      const payload = { regionCode, blocked: values.blocked, firstWeightG: values.firstWeightG, firstFeeFen: cents(values.firstFeeYuan), stepWeightG: values.stepWeightG, stepFeeFen: cents(values.stepFeeYuan) }
      const next = await api<Row>(`/api/admin/shipping-templates/${ruleTemplate.id}/rules${editingRule ? `/${editingRule.id}` : ''}`, json(editingRule ? 'PUT' : 'POST', payload))
      setRuleTemplate(next); setEditingRule(null); openRule(); reload(); message.success('地区规则已保存')
    } catch (e) { message.error((e as Error).message) }
    finally { setBusy(false) }
  }
  const saveTemplate = async (values: Row) => {
    if (busy) return
    const execute = async () => {
      setBusy(true)
      try {
        const payload = { name: values.name, enabled: values.enabled, freeThresholdFen: values.freeThresholdYuan ? cents(values.freeThresholdYuan) : null }
        await api(editing?.id ? `/api/admin/shipping-templates/${editing.id}` : '/api/admin/shipping-templates', json(editing?.id ? 'PUT' : 'POST', payload))
        setEditing(null); reload(); message.success('运费模板已保存')
      } catch (e) { message.error((e as Error).message) }
      finally { setBusy(false) }
    }
    if (editing?.enabled && !values.enabled) {
      Modal.confirm({ title: `停用「${editing.name}」？`, content: '绑定此模板的供货来源将无法用于新报价和下单；历史订单运费快照不变。', okText: '确认停用', cancelText: '暂不停用', onOk: execute })
    } else await execute()
  }
  const assign = async (values: Row) => {
    if (busy) return
    const supply = supplies.find(row => row.supplyId === values.supplyId)
    const template = items.find(row => row.id === values.templateId)
    Modal.confirm({ title: '更换供货运费模板？', content: `${supply?.productTitle || '该商品'} / ${supply?.skuCode || ''} 将使用「${template?.name || ''}」参与后续报价与下单；历史订单快照不变。`, okText: '确认更换', cancelText: '暂不更换', onOk: async () => {
      setBusy(true)
      try { await api(`/api/admin/supplies/${values.supplyId}/shipping-template`, json('PUT', { templateId: values.templateId })); message.success('绑定已更新'); reload() }
      catch (e) { message.error((e as Error).message) }
      finally { setBusy(false) }
    } })
  }
  return <Space direction="vertical" style={{ width: '100%' }}><Card title="运费模板" extra={<Button type="primary" onClick={() => { setEditing({}); form.setFieldsValue({ name: '', freeThresholdYuan: '', enabled: true }) }}>新增模板</Button>}>
    <Alert type="info" showIcon style={{ marginBottom: 16 }} message="匹配优先级：区县 → 市 → 省 → 全国默认；无匹配则不可配送。不配送优先于包邮；可配送时达到门槛才包邮，未达到门槛按首重和续重收费。" />
    <Table rowKey="id" dataSource={items} scroll={{ x: 650 }} columns={[{ title: '名称', dataIndex: 'name' }, { title: '包邮门槛', dataIndex: 'freeThresholdFen', render: (value?: number) => value == null ? '不包邮' : fen(value) }, { title: '状态', dataIndex: 'enabled', render: (value: boolean) => value ? '启用' : '停用' }, { title: '版本', dataIndex: 'version' }, { title: '规则', render: (_: unknown, row: Row) => <Space><Button onClick={() => { setEditing(row); form.setFieldsValue({ ...row, freeThresholdYuan: yuan(row.freeThresholdFen) }) }}>编辑</Button><Button onClick={() => { setRuleTemplate(row); openRule() }}>地区规则（{row.rules.length}）</Button></Space> }]} />
    <Modal title={editing?.id ? '编辑运费模板' : '新增运费模板'} open={!!editing} onCancel={() => !busy && setEditing(null)} onOk={() => form.submit()} confirmLoading={busy} style={{ maxWidth: 'calc(100vw - 24px)' }}><Form form={form} layout="vertical" onFinish={saveTemplate}><Form.Item name="name" label="名称" rules={[{ required: true, message: '请输入模板名称' }]}><Input /></Form.Item><Form.Item name="freeThresholdYuan" label="分组商品包邮门槛（元；留空表示不包邮）" rules={[{ validator: (_, value) => !value || /^(0|[1-9]\d{0,10})(\.\d{1,2})?$/.test(value) ? Promise.resolve() : Promise.reject(new Error('请输入非负元金额，最多两位小数')) }]}><Input inputMode="decimal" placeholder="例如 58.00" /></Form.Item><Form.Item name="enabled" label="启用" valuePropName="checked"><Switch /></Form.Item></Form></Modal>
    <Modal title={`地区规则 · ${ruleTemplate?.name || ''}`} open={!!ruleTemplate} onCancel={() => !busy && setRuleTemplate(null)} footer={null} width={850} style={{ maxWidth: 'calc(100vw - 24px)' }}>
      <Alert type="info" showIcon style={{ marginBottom: 12 }} message="可选全国默认、省、市或区县。更具体的规则优先；不配送优先于包邮。金额以元填写，重量以克计。" />
      <Table rowKey="id" dataSource={ruleTemplate?.rules || []} pagination={false} scroll={{ x: 700 }} columns={[{ title: '地区', render: (_: unknown, rule: Row) => <>{rule.regionName} <Text type="secondary">（{rule.regionCode}）</Text></> }, { title: '配送', dataIndex: 'blocked', render: (value: boolean) => value ? <Tag color="red">不配送</Tag> : '配送' }, { title: '首重', dataIndex: 'firstWeightG', render: (value: number) => `${value} 克` }, { title: '首费', dataIndex: 'firstFeeFen', render: fen }, { title: '续重', dataIndex: 'stepWeightG', render: (value: number) => `${value} 克` }, { title: '续费', dataIndex: 'stepFeeFen', render: fen }, { title: '操作', render: (_: unknown, rule: Row) => <Space><Button onClick={() => openRule(rule)}>编辑</Button><Popconfirm title={`删除「${rule.regionName}」规则？`} description="后续新报价将按较宽地区规则匹配，或变成不可配送；历史订单快照不变。" okText="确认删除" cancelText="保留规则" onConfirm={async () => { try { const next = await api<Row>(`/api/admin/shipping-templates/${ruleTemplate?.id}/rules/${rule.id}`, { method: 'DELETE' }); setRuleTemplate(next || await api<Row>(`/api/admin/shipping-templates/${ruleTemplate?.id}`)); reload(); message.success('规则已删除') } catch (e) { message.error((e as Error).message) } }}><Button danger>删除</Button></Popconfirm></Space> }]} />
      {regionError && <Alert type="error" showIcon message="地区数据加载失败" action={<Button onClick={loadRegions}>重试</Button>} />}
      {editingRule?.regionName === '历史地区编码无法识别' && <Alert type="warning" showIcon message={`历史编码 ${editingRule.regionCode} 无法识别，请重新选择地区；保存前不会替换原规则。`} />}
      <Form form={ruleForm} layout="vertical" style={{ marginTop: 18 }} onFinish={saveRule}>
        <Form.Item name="scope" label="规则地区" rules={[{ required: true }]}><Select options={[{ value: '000000', label: '全国默认' }, { value: 'region', label: '选择省、市或区县' }]} /></Form.Item>
        <Form.Item noStyle shouldUpdate={(previous, current) => previous.scope !== current.scope}>{({ getFieldValue }) => getFieldValue('scope') === 'region' && <Form.Item name="regionPath" label="省 → 市 → 区县（可在任一级结束）" rules={[{ required: true, type: 'array', min: 1, message: '请选择省、市或区县' }]}><Cascader options={options} changeOnSelect placeholder="请选择地区" disabled={!regions.length} /></Form.Item>}</Form.Item>
        <Form.Item name="blocked" label="不配送" valuePropName="checked"><Switch /></Form.Item>
        <Space wrap><Form.Item name="firstWeightG" label="首重（克）" rules={[{ required: true, type: 'integer', min: 1, message: '首重须为正整数克' }]}><InputNumber min={1} precision={0} /></Form.Item><Form.Item name="firstFeeYuan" label="首费（元）" rules={[{ required: true }, { pattern: /^(0|[1-9]\d{0,10})(\.\d{1,2})?$/, message: '请输入非负元金额，最多两位小数' }]}><Input inputMode="decimal" /></Form.Item><Form.Item name="stepWeightG" label="续重单位（克）" rules={[{ required: true, type: 'integer', min: 1, message: '续重须为正整数克' }]}><InputNumber min={1} precision={0} /></Form.Item><Form.Item name="stepFeeYuan" label="续费（元）" rules={[{ required: true }, { pattern: /^(0|[1-9]\d{0,10})(\.\d{1,2})?$/, message: '请输入非负元金额，最多两位小数' }]}><Input inputMode="decimal" /></Form.Item></Space>
        <Button type="primary" htmlType="submit" loading={busy} disabled={!regions.length || busy}>{editingRule ? '保存规则' : '添加规则'}</Button>{editingRule && <Button onClick={() => openRule()}>取消编辑</Button>}
      </Form>
    </Modal>
  </Card><Card title="供货来源绑定运费模板"><Form form={assignForm} layout="inline" onFinish={assign}><Form.Item name="supplyId" label="供货来源" rules={[{ required: true }]}><Select style={{ width: 300 }} options={supplies.map(supply => ({ value: supply.supplyId, label: `${supply.productTitle} / ${supply.skuCode} · ${supply.supplierName}` }))} /></Form.Item><Form.Item name="templateId" label="模板" rules={[{ required: true }]}><Select style={{ width: 220 }} options={items.filter(item => item.enabled).map(item => ({ value: item.id, label: item.name }))} /></Form.Item><Button type="primary" htmlType="submit" loading={busy}>绑定</Button></Form></Card></Space>
}
function OrdersPanel(){const [items,setItems]=useState<Row[]>([]);const [detail,setDetail]=useState<Row|null>(null);useEffect(()=>{api<Row[]>('/api/admin/orders').then(setItems)},[]);return <Card title="订单查询"><Table rowKey="id" dataSource={items} columns={[{title:'订单号',dataIndex:'orderNo'},{title:'状态',dataIndex:'status'},{title:'商品金额',dataIndex:'itemAmountFen',render:fen},{title:'运费',dataIndex:'shippingAmountFen',render:fen},{title:'应付',dataIndex:'payableAmountFen',render:fen},{title:'创建时间',dataIndex:'createdAt'},{title:'操作',render:(_:unknown,r:Row)=><Button onClick={async()=>setDetail(await api<Row>(`/api/admin/orders/${r.id}`))}>详情</Button>} ]}/><Modal title={`订单详情 · ${detail?.order?.orderNo||''}`} open={!!detail} onCancel={()=>setDetail(null)} footer={null} width={900}>{detail&&<><Alert type="info" message={`状态：${detail.order.status}　合计：${fen(detail.order.payableAmountFen)}`}/><Card size="small" title="收货快照" style={{marginTop:16}}>{detail.address.recipient}　{detail.address.mobile}<br/>{displayAddress(detail.address)}</Card><Table rowKey="skuId" dataSource={detail.items} pagination={false} columns={[{title:'商品',dataIndex:'productTitle'},{title:'SKU',dataIndex:'skuCode'},{title:'规格',dataIndex:'specJson'},{title:'数量',dataIndex:'quantity'},{title:'单价',dataIndex:'unitPriceFen',render:fen},{title:'小计',dataIndex:'lineAmountFen',render:fen},{title:'供货来源',dataIndex:'supplyId'}]}/><Table rowKey="id" dataSource={detail.groups} pagination={false} columns={[{title:'供应商',dataIndex:'supplierName'},{title:'发货地快照',render:(_:unknown,r:Row)=><>{r.originLabel}<br/><Text type="secondary">{r.originProvince}{r.originCity}{r.originAddress}</Text></>},{title:'模板版本',render:(_:unknown,r:Row)=>`${r.templateName} v${r.templateVersion}`},{title:'计费重量g',dataIndex:'billableWeightG'},{title:'商品金额',dataIndex:'itemAmountFen',render:fen},{title:'运费',dataIndex:'shippingFeeFen',render:fen},{title:'匹配地区码',dataIndex:'ruleRegionCode'}]}/></>}</Modal></Card>}
function PaymentsPanel(){const [payments,setPayments]=useState<Row[]>([]);const [anomalies,setAnomalies]=useState<Row[]>([]);const reload=()=>Promise.all([api<Row[]>('/api/admin/payments'),api<Row[]>('/api/admin/payment-anomalies')]).then(([p,a])=>{setPayments(p);setAnomalies(a)});useEffect(()=>{reload()},[]);return <Space direction="vertical" style={{width:'100%'}}><Card title="支付记录"><Table rowKey="id" dataSource={payments} columns={[{title:'支付单',dataIndex:'paymentNo'},{title:'订单',dataIndex:'orderNo'},{title:'金额',dataIndex:'amountFen',render:fen},{title:'币种',dataIndex:'currency'},{title:'状态',dataIndex:'status'},{title:'创建时间',dataIndex:'createdAt'}]}/></Card><Card title="异常收款"><Alert type="warning" showIcon message="关单后到款或重复收款不会恢复订单或派单；只有退款成功后才解决异常。开发按钮不调用真实资金接口。"/><Table rowKey="id" dataSource={anomalies} columns={[{title:'支付单',dataIndex:'paymentNo'},{title:'类型',dataIndex:'type'},{title:'渠道交易号',dataIndex:'channelTransactionNo'},{title:'金额',dataIndex:'amountFen',render:fen},{title:'说明',dataIndex:'detail'},{title:'状态',dataIndex:'status'},{title:'操作',render:(_:unknown,r:Row)=><Button disabled={r.status==='RESOLVED'} onClick={()=>Modal.confirm({title:'开发模拟原路退款',content:`将创建 ${fen(r.amountFen)} 的 DEV_SIMULATOR 退款并模拟成功；不会调用微信、支付宝或银行。`,onOk:async()=>{try{const refund=await api<Row>(`/api/admin/payment-anomalies/${r.id}/refunds`,json('POST',{amountFen:r.amountFen,channel:'DEV_SIMULATOR',reason:'异常收款模拟原路退回'}));await api(`/api/admin/refund-attempts/${refund.attemptNo}/simulate`,json('POST',{result:'SUCCESS'}));message.success('模拟退款成功，异常已解决');reload()}catch(e){message.error((e as Error).message)}}})}>模拟原路退回</Button>}]}/></Card></Space>}
function FulfillmentPanel() {
  const [tasks, setTasks] = useState<Row[]>([])
  const [detail, setDetail] = useState<Row | null>(null)
  const [busy, setBusy] = useState(false)
  const shipLock = useRef(false)
  const [form] = Form.useForm()
  const reload = () => api<Row[]>('/api/admin/fulfillment-tasks').then(setTasks).catch(e => message.error(e.message))
  useEffect(() => { reload() }, [])
  const ship = (values: Row) => {
    if (!detail || shipLock.current) return
    shipLock.current = true; setBusy(true)
    const item = detail.items.find((entry: Row) => entry.id === values.taskItemId)
    Modal.confirm({ title: `代录发货 · ${detail.taskNo}`, content: `${item?.productTitle || item?.skuCode || '商品'} × ${values.quantity}，${values.carrierName} ${values.trackingNo}。创建后会更新发货数量，补发还会消耗预占库存。`, okText: '确认代发货', cancelText: '继续填写', onCancel: () => { shipLock.current = false; setBusy(false) }, onOk: async () => {
      try {
        await api(`/api/admin/fulfillment-tasks/${detail.id}/parcels`, json('POST', { ...values, items: [{ taskItemId: values.taskItemId, quantity: values.quantity }] }))
        setDetail(await api<Row>(`/api/admin/fulfillment-tasks/${detail.id}`)); reload(); message.success('包裹已创建，操作人记为管理员')
      } catch (e) { message.error((e as Error).message) }
      finally { shipLock.current = false; setBusy(false) }
    } })
  }
  return <Card title="发货与补发任务"><Table rowKey="id" dataSource={tasks} scroll={{ x: 650 }} columns={[{ title: '类型', dataIndex: 'taskType', render: (value: string) => value === 'REPLACEMENT' ? '售后补发' : '原订单' }, { title: '任务号', dataIndex: 'taskNo' }, { title: '订单', dataIndex: 'orderNo' }, { title: '供应商', dataIndex: 'supplierName' }, { title: '状态', dataIndex: 'status' }, { title: '操作', render: (_: unknown, row: Row) => <Button onClick={async () => { try { setDetail(await api<Row>(`/api/admin/fulfillment-tasks/${row.id}`)) } catch (e) { message.error((e as Error).message) } }}>详情/代发货</Button> }]} />
    <Modal width={900} style={{ maxWidth: 'calc(100vw - 24px)' }} title={`履约任务 · ${detail?.taskNo || ''}`} open={!!detail} onCancel={() => !busy && setDetail(null)} footer={null}>{detail && <><Alert message={`${detail.taskType === 'REPLACEMENT' ? '售后补发' : '原订单'} · ${detail.recipient} ${detail.mobile} · ${detail.address}`} /><Table rowKey="id" dataSource={detail.items} pagination={false} scroll={{ x: 600 }} columns={[{ title: '商品', dataIndex: 'productTitle' }, { title: 'SKU', dataIndex: 'skuCode' }, { title: '应发', dataIndex: 'requiredQty' }, { title: '冻结', dataIndex: 'frozenQty' }, { title: '取消', dataIndex: 'cancelledQty' }, { title: '已发', dataIndex: 'shippedQty' }]} />
      <Form form={form} layout="vertical" onFinish={ship}><Form.Item name="idempotencyKey" label="请求键" rules={[{ required: true, min: 8 }]}><Input /></Form.Item><Form.Item name="taskItemId" label="商品" rules={[{ required: true }]}><Select options={detail.items.map((item: Row) => ({ value: item.id, label: item.skuCode }))} /></Form.Item><Form.Item name="quantity" label="数量" rules={[{ required: true, type: 'integer', min: 1 }]}><InputNumber min={1} precision={0} /></Form.Item><Form.Item name="carrierCode" label="快递代码" rules={[{ required: true }]}><Input /></Form.Item><Form.Item name="carrierName" label="快递公司" rules={[{ required: true }]}><Input /></Form.Item><Form.Item name="trackingNo" label="单号" rules={[{ required: true }]}><Input /></Form.Item><Button htmlType="submit" type="primary" loading={busy}>代录发货</Button></Form>
      <Table style={{ marginTop: 16 }} rowKey="id" dataSource={detail.parcels} pagination={false} scroll={{ x: 500 }} columns={[{ title: '快递', dataIndex: 'carrierName' }, { title: '单号', dataIndex: 'trackingNo' }, { title: '录入身份', dataIndex: 'createdByType' }, { title: '操作人', dataIndex: 'createdBy' }]} /></>}</Modal>
  </Card>
}
function AfterSalesPanel(){const [cases,setCases]=useState<Row[]>([]);const [refunds,setRefunds]=useState<Row[]>([]);const [detail,setDetail]=useState<Row|null>(null);const [form]=Form.useForm();const reload=()=>Promise.all([api<Row[]>('/api/admin/after-sales'),api<Row[]>('/api/admin/refunds')]).then(([c,r])=>{setCases(c);setRefunds(r)});useEffect(()=>{reload().catch(e=>message.error(e.message))},[]);return <Space direction="vertical" style={{width:'100%'}}><Card title="售后审核"><Alert type="info" showIcon message="支持未发货退款、坏果/破损部分退款和补发；不包含退货寄回、换货或自动责任裁定。"/><Table rowKey="id" dataSource={cases} columns={[{title:'售后单',dataIndex:'caseNo'},{title:'订单',dataIndex:'orderNo'},{title:'诉求',dataIndex:'action'},{title:'原因',dataIndex:'reasonCode'},{title:'状态',dataIndex:'status'},{title:'申请金额',dataIndex:'requestedRefundFen',render:fen},{title:'操作',render:(_:unknown,r:Row)=><Button onClick={async()=>{const d=await api<Row>(`/api/admin/after-sales/${r.id}`);setDetail(d);form.setFieldsValue({decision:'APPROVE',refundAmountFen:d.requestedRefundFen,shippingRefundFen:0,responsibility:'MERCHANT',supplierDeductionFen:0,refundChannel:'DEV_SIMULATOR'})}}>审核</Button>}]}/><Modal width={900} title={`售后审核 · ${detail?.caseNo||''}`} open={!!detail} onCancel={()=>setDetail(null)} footer={null}>{detail&&<><Alert message={`${detail.orderNo} · ${detail.action} · ${detail.status}`}/><Table rowKey="id" dataSource={detail.items} pagination={false} columns={[{title:'商品',dataIndex:'productTitle'},{title:'SKU',dataIndex:'skuCode'},{title:'申请数量',dataIndex:'requestedQty'},{title:'冻结待发',dataIndex:'frozenQty'},{title:'实付单价',dataIndex:'unitPaidFen',render:fen}]}/>{detail.evidence?.length>0&&<Space wrap>{detail.evidence.map((e:Row)=><AuthImage key={e.id} path={`/api/admin/after-sales/evidence/${e.id}`}/>)}</Space>}<Form form={form} layout="vertical" onFinish={async v=>{try{const outcome=await api<Row>(`/api/admin/after-sales/${detail.id}/review`,json('POST',v));outcome.reason?message.warning(outcome.reason):message.success('审核已记录');setDetail(null);reload()}catch(e){message.error((e as Error).message)}}}><Form.Item name="decision" label="决定" rules={[{required:true}]}><Select options={[{value:'APPROVE',label:'同意'},{value:'REJECT',label:'拒绝'}]}/></Form.Item><Form.Item name="comment" label="审核意见"><Input.TextArea/></Form.Item>{detail.action==='REFUND'&&<><Form.Item name="refundAmountFen" label="退款总额（分）"><InputNumber min={1}/></Form.Item><Form.Item name="shippingRefundFen" label="其中运费（分）"><InputNumber min={0}/></Form.Item><Form.Item name="refundChannel" label="退款通道"><Select options={[{value:'DEV_SIMULATOR',label:'开发模拟（无真实资金）'},{value:'WECHAT',label:'微信（未配置）'},{value:'ALIPAY',label:'支付宝（未配置）'}]}/></Form.Item></>}<Form.Item name="responsibility" label="责任承担"><Select options={['MERCHANT','SUPPLIER','SHARED'].map(value=>({value,label:value}))}/></Form.Item><Form.Item name="supplierDeductionFen" label="供应商扣款（与消费者退款独立，分）"><InputNumber min={0}/></Form.Item><Button type="primary" htmlType="submit">提交审核</Button></Form></>}</Modal></Card><Card title="退款记录"><Alert type="warning" showIcon message="DEV_SIMULATOR 结果仅用于开发验证；渠道受理不等于退款成功。"/><Table rowKey="id" dataSource={refunds} columns={[{title:'退款单',dataIndex:'refundNo'},{title:'售后单',dataIndex:'caseNo'},{title:'异常类型',dataIndex:'anomalyType'},{title:'金额',dataIndex:'amountFen',render:fen},{title:'状态',dataIndex:'status'},{title:'原因',dataIndex:'reason'}]}/></Card></Space>}
function SettlementPanel(){const [ledger,setLedger]=useState<Row[]>([]);const [statements,setStatements]=useState<Row[]>([]);const [selected,setSelected]=useState<(string|number)[]>([]);const [detail,setDetail]=useState<Row|null>(null);const [form]=Form.useForm();const reload=()=>Promise.all([api<Row[]>('/api/admin/supplier-ledger'),api<Row[]>('/api/admin/settlement-statements')]).then(([l,s])=>{setLedger(l);setStatements(s)});useEffect(()=>{reload()},[]);return <Space direction="vertical" style={{width:'100%'}}><Card title="供应商结算台账" extra={<Button disabled={!selected.length} onClick={()=>{const rows=ledger.filter(x=>selected.includes(x.id));if(!rows.length)return;Modal.confirm({title:'生成结算单？',content:`${rows[0].supplierName} · ${rows.length} 条明细 · 合计 ${fen(rows.reduce((sum,row)=>sum+row.amountFen,0))}。生成后将锁定明细，不改历史订单。`,okText:'确认生成',cancelText:'继续核对',onOk:async()=>{try{await api('/api/admin/settlement-statements',json('POST',{supplierId:rows[0].supplierId,ledgerEntryIds:selected}));setSelected([]);reload()}catch(e){message.error((e as Error).message)}}})}}>生成结算单</Button>}><Alert type="info" message="货款采用下单时供货价快照；消费者退款不自动等同供应商扣款。仅可选同一供应商的可结算明细。"/><Table rowKey="id" rowSelection={{selectedRowKeys:selected,onChange:keys=>setSelected(keys as (string|number)[]),getCheckboxProps:r=>({disabled:r.status!=='AVAILABLE'||(selected.length>0&&ledger.find(x=>x.id===selected[0])?.supplierId!==r.supplierId)})}} dataSource={ledger} columns={[{title:'供应商',dataIndex:'supplierName'},{title:'订单',dataIndex:'orderNo'},{title:'类型',dataIndex:'type'},{title:'数量',dataIndex:'quantity'},{title:'金额',dataIndex:'amountFen',render:fen},{title:'口径',dataIndex:'status'},{title:'说明',dataIndex:'description'}]}/></Card><Card title="结算单"><Table rowKey="id" dataSource={statements} columns={[{title:'结算单',dataIndex:'statementNo'},{title:'供应商',dataIndex:'supplierName'},{title:'总额',dataIndex:'totalAmountFen',render:fen},{title:'已登记付款',dataIndex:'paidAmountFen',render:fen},{title:'状态',dataIndex:'status'},{title:'操作',render:(_:unknown,r:Row)=><Button onClick={async()=>{setDetail(await api<Row>(`/api/admin/settlement-statements/${r.id}`));form.setFieldsValue({idempotencyKey:`pay-${r.id}-${Date.now()}`,paidOn:new Date().toISOString().slice(0,10)})}}>付款登记</Button>}]}/><Modal title={`人工付款登记 · ${detail?.statementNo||''}`} open={!!detail} onCancel={()=>setDetail(null)} footer={null}>{detail&&<><Alert type="warning" message="仅记录人工付款信息，不代表银行或支付机构已验证。"/><Form form={form} layout="vertical" onFinish={v=>Modal.confirm({title:`登记人工付款 · ${detail.statementNo}`,content:`将人工登记 ${fen(v.amountFen)}，参考号 ${v.referenceNo}。这只是记账，不表示银行已付款；历史记录只能通过冲正调整。`,okText:'确认登记',cancelText:'继续核对',onOk:async()=>{try{const next=await api<Row>(`/api/admin/settlement-statements/${detail.id}/payments`,json('POST',v));setDetail(next);reload();message.success('人工付款已登记，不代表银行已付款')}catch(e){message.error((e as Error).message)}}})}><Form.Item name="idempotencyKey" label="幂等键" rules={[{required:true,min:8}]}><Input/></Form.Item><Form.Item name="amountFen" label="金额（分）" rules={[{required:true}]}><InputNumber min={1}/></Form.Item><Form.Item name="paidOn" label="付款日期 YYYY-MM-DD" rules={[{required:true}]}><Input/></Form.Item><Form.Item name="referenceNo" label="人工参考号" rules={[{required:true}]}><Input/></Form.Item><Form.Item name="note" label="备注"><Input/></Form.Item><Button type="primary" htmlType="submit">核对并登记（不验证银行状态）</Button></Form><Table rowKey="id" dataSource={detail.payments} pagination={false} columns={[{title:'操作',dataIndex:'operation'},{title:'金额',dataIndex:'amountFen',render:fen},{title:'日期',dataIndex:'paidOn'},{title:'参考号',dataIndex:'referenceNo'},{title:'操作人',dataIndex:'actor'}]}/></>}</Modal></Card></Space>}
function SupplierPhase4Panels(){const [cases,setCases]=useState<Row[]>([]);const [ledger,setLedger]=useState<Row[]>([]);const [statements,setStatements]=useState<Row[]>([]);useEffect(()=>{Promise.all([api<Row[]>('/api/supplier/after-sales'),api<Row[]>('/api/supplier/ledger'),api<Row[]>('/api/supplier/settlement-statements')]).then(([c,l,s])=>{setCases(c);setLedger(l);setStatements(s)}).catch(e=>message.error(e.message))},[]);return <Tabs items={[{key:'after-sales',label:'相关售后',children:<Card><Alert message="仅展示与本供应商商品有关的必要信息；退款金额由店主决定。"/><Table rowKey="id" dataSource={cases} scroll={{x:600}} columns={[{title:'售后单',dataIndex:'caseNo'},{title:'订单',dataIndex:'orderNo'},{title:'诉求',dataIndex:'action'},{title:'原因',dataIndex:'reasonCode'},{title:'状态',dataIndex:'status'}]}/></Card>},{key:'ledger',label:'我的结算',children:<Space direction="vertical" style={{width:'100%'}}><Card title="台账明细"><Table rowKey="id" dataSource={ledger} scroll={{x:700}} columns={[{title:'订单',dataIndex:'orderNo'},{title:'类型',dataIndex:'type'},{title:'数量',dataIndex:'quantity'},{title:'金额',dataIndex:'amountFen',render:fen},{title:'状态',dataIndex:'status'},{title:'说明',dataIndex:'description'}]}/></Card><Card title="结算单"><Table rowKey="id" dataSource={statements} scroll={{x:600}} columns={[{title:'结算单',dataIndex:'statementNo'},{title:'总额',dataIndex:'totalAmountFen',render:fen},{title:'已登记',dataIndex:'paidAmountFen',render:fen},{title:'状态',dataIndex:'status'}]}/></Card></Space>}]} />}
function SupplierPortal() {
  const [logged, setLogged] = useState(!!localStorage.getItem('supplier_token'))
  const [tasks, setTasks] = useState<Row[]>([])
  const [detail, setDetail] = useState<Row | null>(null)
  const [busy, setBusy] = useState(false)
  const shipLock = useRef(false)
  const [form] = Form.useForm()
  const reload = () => api<Row[]>('/api/supplier/tasks').then(setTasks).catch(e => message.error(e.message))
  useEffect(() => { if (logged) reload() }, [logged])
  if (!logged) return <div className="login"><Card><Title level={3}>供应商工作台</Title><Alert type="info" message="账号由店主创建并关联供应商，不能自行注册。" /><Form layout="vertical" onFinish={async values => { try { const result = await api<{ token: string }>('/api/supplier/auth/login', json('POST', values)); localStorage.setItem('supplier_token', result.token); setLogged(true) } catch (e) { message.error((e as Error).message) } }}><Form.Item name="username" label="账号" rules={[{ required: true }]}><Input /></Form.Item><Form.Item name="password" label="密码" rules={[{ required: true }]}><Input.Password /></Form.Item><Button block type="primary" htmlType="submit">登录</Button></Form></Card></div>
  const ship = (values: Row) => {
    if (!detail || shipLock.current) return
    shipLock.current = true; setBusy(true)
    const item = detail.items.find((entry: Row) => entry.id === values.taskItemId)
    Modal.confirm({ title: `确认发货 · ${detail.taskNo}`, content: `${item?.productTitle || item?.skuCode || '商品'} × ${values.quantity}，${values.carrierName} ${values.trackingNo}。确认后将创建包裹并更新发货数量${detail.taskType === 'REPLACEMENT' ? '，同时消耗补发预占库存' : ''}。`, okText: '确认发货', cancelText: '继续填写', onCancel: () => { shipLock.current = false; setBusy(false) }, onOk: async () => {
      try { await api(`/api/supplier/tasks/${detail.id}/parcels`, json('POST', { ...values, items: [{ taskItemId: values.taskItemId, quantity: values.quantity }] })); setDetail(await api<Row>(`/api/supplier/tasks/${detail.id}`)); reload(); message.success('包裹已创建') }
      catch (e) { message.error((e as Error).message) }
      finally { shipLock.current = false; setBusy(false) }
    } })
  }
  return <Layout className="container"><div className="header"><Title level={2}>供应商工作台</Title><Button onClick={() => { localStorage.removeItem('supplier_token'); setLogged(false) }}>退出</Button></div><Card title="我的发货与补发任务"><Table rowKey="id" dataSource={tasks} scroll={{ x: 650 }} columns={[{ title: '类型', dataIndex: 'taskType', render: (value: string) => value === 'REPLACEMENT' ? '补发' : '原订单' }, { title: '任务号', dataIndex: 'taskNo' }, { title: '订单', dataIndex: 'orderNo' }, { title: '状态', dataIndex: 'status' }, { title: '操作', render: (_: unknown, row: Row) => <Button onClick={async () => { try { setDetail(await api<Row>(`/api/supplier/tasks/${row.id}`)) } catch (e) { message.error((e as Error).message) } }}>处理</Button> }]} />
    <Modal width={900} style={{ maxWidth: 'calc(100vw - 24px)' }} title={detail?.taskNo} open={!!detail} onCancel={() => !busy && setDetail(null)} footer={null}>{detail && <><Alert message={`${detail.taskType === 'REPLACEMENT' ? '补发任务' : '原订单任务'} · ${detail.recipient} ${detail.mobile} · ${detail.address}`} />{detail.status === 'PENDING_ACCEPTANCE' && <Popconfirm title={`接收任务 ${detail.taskNo}？`} description="接单后可录入发货包裹。" okText="确认接单" cancelText="暂不接单" onConfirm={async () => { if (busy) return; setBusy(true); try { await api(`/api/supplier/tasks/${detail.id}/accept`, { method: 'POST' }); setDetail(await api<Row>(`/api/supplier/tasks/${detail.id}`)); reload(); message.success('已接单') } catch (e) { message.error((e as Error).message) } finally { setBusy(false) } }}><Button type="primary" loading={busy}>接单</Button></Popconfirm>}
      <Table rowKey="id" dataSource={detail.items} pagination={false} scroll={{ x: 600 }} columns={[{ title: '商品', dataIndex: 'productTitle' }, { title: 'SKU', dataIndex: 'skuCode' }, { title: '应发', dataIndex: 'requiredQty' }, { title: '冻结', dataIndex: 'frozenQty' }, { title: '取消', dataIndex: 'cancelledQty' }, { title: '已发', dataIndex: 'shippedQty' }]} />
      <Form form={form} layout="vertical" onFinish={ship}><Form.Item name="idempotencyKey" label="请求键" rules={[{ required: true, min: 8 }]}><Input /></Form.Item><Form.Item name="taskItemId" label="商品" rules={[{ required: true }]}><Select options={detail.items.map((item: Row) => ({ value: item.id, label: item.skuCode }))} /></Form.Item><Form.Item name="quantity" label="数量" rules={[{ required: true, type: 'integer', min: 1 }]}><InputNumber min={1} precision={0} /></Form.Item><Form.Item name="carrierCode" label="快递代码" rules={[{ required: true }]}><Input /></Form.Item><Form.Item name="carrierName" label="快递公司" rules={[{ required: true }]}><Input /></Form.Item><Form.Item name="trackingNo" label="单号" rules={[{ required: true }]}><Input /></Form.Item><Button type="primary" htmlType="submit" loading={busy} disabled={busy || detail.status === 'PENDING_ACCEPTANCE' || detail.status === 'SHIPPED'}>创建包裹</Button></Form>
      <Table style={{ marginTop: 16 }} rowKey="id" dataSource={detail.parcels} pagination={false} scroll={{ x: 420 }} columns={[{ title: '快递', dataIndex: 'carrierName' }, { title: '单号', dataIndex: 'trackingNo' }, { title: '创建时间', dataIndex: 'createdAt' }]} /></>}</Modal></Card><SupplierPhase4Panels /></Layout>
}
function NotificationsPanel(){const [data,setData]=useState<Row>({events:[]});const reload=()=>api<Row>('/api/admin/notification-events').then(setData);useEffect(()=>{reload()},[]);return <Card title="通知事件"><Alert type="info" message={`能力：${data.capability||'加载中'}；未接入真实短信或平台订阅消息。`}/><Table rowKey="id" dataSource={data.events} columns={[{title:'事件',dataIndex:'eventType'},{title:'聚合ID',dataIndex:'aggregateId'},{title:'状态',dataIndex:'status'},{title:'尝试',render:(_:unknown,r:Row)=>`${r.attemptCount}/${r.maxAttempts}`},{title:'失败原因',dataIndex:'lastError'},{title:'操作',render:(_:unknown,r:Row)=><Button disabled={!['PENDING','DEAD'].includes(r.status)} onClick={async()=>{await api(`/api/admin/notification-events/${r.id}/retry`,{method:'POST'});reload()}}>受控重试</Button>}]}/></Card>}
function AuditPanel() {
  const [items, setItems] = useState<Row[]>([])
  useEffect(() => { api<Row[]>('/api/admin/audit-logs').then(setItems).catch(e => message.error(e.message)) }, [])
  return <Card title="最近 100 条管理操作"><Table rowKey="id" dataSource={items} columns={[
    { title: '时间', dataIndex: 'createdAt' }, { title: '管理员', dataIndex: 'username' },
    { title: '方法', dataIndex: 'method' }, { title: '路径', dataIndex: 'path' }, { title: '结果', dataIndex: 'statusCode' }
  ]} /></Card>
}
export default function App() {
  if (location.pathname.startsWith('/supplier')) return <SupplierPortal />
  const [logged, setLogged] = useState(!!localStorage.getItem('admin_token'))
  if (!logged) return <Login onLogin={() => setLogged(true)} />
  return <Layout className="container"><div className="header"><div><Title level={2} style={{ margin: 0 }}>商城管理后台</Title><Text className="muted">商品、库存、运费与订单管理</Text></div>
    <Button onClick={async () => { try { await api('/api/auth/logout', { method: 'POST' }) } finally { localStorage.removeItem('admin_token'); setLogged(false) } }}>退出登录</Button></div>
    <Tabs items={[
      { key: 'products', label: '商品', children: <ProductsPanel /> },
      { key: 'categories', label: '分类', children: <CategoriesPanel /> },
      { key: 'supply', label: '供应商与发货地', children: <SupplyPanel /> },
      { key: 'inventory', label: '库存', children: <InventoryPanel /> },
      { key: 'shipping', label: '运费模板', children: <ShippingPanel /> },
      { key: 'orders', label: '订单', children: <OrdersPanel /> },
      { key: 'payments', label: '支付与异常', children: <PaymentsPanel /> },
      { key: 'fulfillment', label: '发货任务', children: <FulfillmentPanel /> },
      { key: 'after-sales', label: '售后与退款', children: <Space direction="vertical" style={{ width: '100%' }}><AfterSalesPanel /><AfterSaleDevRefundPanel /></Space> },
      { key: 'settlement', label: '供应商结算', children: <><SettlementPanel /><SettlementOperations /></> },
      { key: 'supplier-users', label: '供应商账号', children: <SupplierUsersPanel /> },
      { key: 'notifications', label: '通知事件', children: <NotificationsPanel /> },
      { key: 'store', label: '店铺配置', children: <StorePanel /> },
      { key: 'audit', label: '操作日志', children: <AuditPanel /> }
    ]} />
  </Layout>
}
