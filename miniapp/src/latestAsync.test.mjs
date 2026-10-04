import test from 'node:test'
import assert from 'node:assert/strict'
import { createLatestAsync } from './latestAsync.ts'

test('late quote and address responses cannot replace a newer selection', () => {
  const quote = createLatestAsync()
  const old = quote.begin()
  const current = quote.begin()
  assert.equal(quote.isCurrent(old), false)
  assert.equal(quote.isCurrent(current), true)
  quote.invalidate()
  assert.equal(quote.isCurrent(current), false)
})
