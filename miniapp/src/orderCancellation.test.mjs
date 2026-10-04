import test from 'node:test'
import assert from 'node:assert/strict'
import { createOrderCancellation } from './orderCancellation.ts'

test('dismissal sends no cancel request', async () => {
  let sent = 0
  const result = await createOrderCancellation().run(async () => false, async () => { sent++; return 'CANCELLED' }, async () => 'PENDING_PAYMENT')
  assert.equal(sent, 0)
  assert.equal(result.dismissed, true)
})
test('concurrent taps send one request', async () => {
  let release
  let sent = 0
  const flow = createOrderCancellation()
  const first = flow.run(async () => true, async () => { sent++; return new Promise(resolve => { release = resolve }) }, async () => 'PENDING_PAYMENT')
  assert.equal(await flow.run(async () => true, async () => { sent++; return 'CANCELLED' }, async () => 'PENDING_PAYMENT'), undefined)
  release('CANCELLED')
  assert.equal((await first).status, 'CANCELLED')
  assert.equal(sent, 1)
})
test('pending and paid outcomes reflect server status', async () => {
  const flow = createOrderCancellation()
  assert.equal((await flow.run(async () => true, async () => 'CLOSE_PENDING', async () => 'PAID')).status, 'CLOSE_PENDING')
  assert.equal((await flow.run(async () => true, async () => 'PAID', async () => 'CANCELLED')).status, 'PAID')
})
test('lost response checks current status before reporting result', async () => {
  const flow = createOrderCancellation()
  const result = await flow.run(async () => true, async () => { throw Error('timeout') }, async () => 'CANCELLED')
  assert.equal(result.status, 'CANCELLED')
  assert.equal(result.requestError.message, 'timeout')
  const unknown = await flow.run(async () => true, async () => { throw Error('timeout') }, async () => { throw Error('network') })
  assert.equal(unknown.status, 'UNKNOWN')
})
