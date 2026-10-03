import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { before, beforeEach, test } from 'node:test'
import ts from 'typescript'

let api
const storage = new Map()

before(async () => {
  const source = (await readFile(new URL('../src/api.ts', import.meta.url), 'utf8'))
    .replace('import.meta.env.VITE_API_BASE', "''")
  const compiled = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.ESNext } }).outputText
  globalThis.localStorage = {
    getItem: key => storage.get(key) ?? null,
    setItem: (key, value) => storage.set(key, value),
    removeItem: key => storage.delete(key)
  }
  api = await import(`data:text/javascript,${encodeURIComponent(compiled)}`)
})
beforeEach(() => storage.clear())

test('admin image upload reads the current token and lets fetch set the multipart boundary', async () => {
  const requests = []
  globalThis.fetch = async (url, options) => {
    requests.push({ url, options })
    return Response.json({ id: requests.length, url: `/api/media/${requests.length}` })
  }
  const file = new File(['image'], 'test.png', { type: 'image/png' })
  storage.set('admin_token', 'first')
  await api.uploadAdminMedia(file)
  storage.set('admin_token', 'second')
  await api.uploadAdminMedia(file)

  assert.equal(requests.length, 2)
  assert.deepEqual(requests.map(r => r.options.headers.get('Authorization')), ['Bearer first', 'Bearer second'])
  for (const { url, options } of requests) {
    assert.equal(url, '/api/admin/media')
    assert.equal(options.method, 'POST')
    assert.equal(options.headers.has('Content-Type'), false)
    assert.equal(options.body.get('file').name, 'test.png')
  }
})

test('supplier token never authorizes an admin upload', async () => {
  storage.set('supplier_token', 'supplier')
  let authorization
  globalThis.fetch = async (_url, options) => {
    authorization = options.headers.get('Authorization')
    return Response.json({ message: '请先登录' }, { status: 401 })
  }
  await assert.rejects(api.uploadAdminMedia(new File(['image'], 'test.png')), /请先登录/)
  assert.equal(authorization, null)
  assert.equal(storage.get('supplier_token'), 'supplier')
})

test('FormData removes an accidental JSON content type and a current 401 clears only admin login', async () => {
  storage.set('admin_token', 'admin')
  storage.set('supplier_token', 'supplier')
  let headers
  globalThis.fetch = async (_url, options) => {
    headers = options.headers
    return Response.json({ message: '登录已失效' }, { status: 401 })
  }
  await assert.rejects(api.api('/api/admin/media', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: new FormData()
  }), /登录已失效/)
  assert.equal(headers.get('Content-Type'), null)
  assert.equal(storage.has('admin_token'), false)
  assert.equal(storage.get('supplier_token'), 'supplier')
})

test('a stale 401 does not erase a newer admin login', async () => {
  storage.set('admin_token', 'old')
  globalThis.fetch = async () => {
    storage.set('admin_token', 'new')
    return Response.json({ message: '登录已失效' }, { status: 401 })
  }
  await assert.rejects(api.uploadAdminMedia(new File(['image'], 'test.png')), /登录已失效/)
  assert.equal(storage.get('admin_token'), 'new')
})

test('JSON admin requests and supplier requests retain their own authentication', async () => {
  storage.set('admin_token', 'admin')
  storage.set('supplier_token', 'supplier')
  const requests = []
  globalThis.fetch = async (_url, options) => {
    requests.push(options)
    return Response.json({ ok: true })
  }
  await api.api('/api/admin/store', api.json('PUT', { name: 'store' }))
  await api.api('/api/supplier/tasks')
  assert.equal(requests[0].headers.get('Authorization'), 'Bearer admin')
  assert.equal(requests[0].headers.get('Content-Type'), 'application/json')
  assert.equal(requests[1].headers.get('Authorization'), 'Bearer supplier')
})
