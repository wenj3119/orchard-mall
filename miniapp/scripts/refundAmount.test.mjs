import test from 'node:test'
import assert from 'node:assert/strict'
import { yuanToFen, fenToYuan } from '../src/refundAmount.ts'

test('yuan input is converted to exact integer fen', () => {
  assert.equal(yuanToFen('5'), 500)
  assert.equal(yuanToFen('5.00'), 500)
  assert.equal(yuanToFen('0.01'), 1)
  assert.equal(fenToYuan(1234), '12.34')
})

test('invalid input is rejected without rounding', () => {
  for (const value of ['', '0', '0.00', '-1', '5.001', 'abc', '1e2', ' 5', '5.', '01.00']) {
    assert.equal(yuanToFen(value), null, value)
  }
})
