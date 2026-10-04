import test from 'node:test'
import assert from 'node:assert/strict'
import { displayAddress } from './addressDisplay.ts'

const address = { provinceName: '陕西省', cityName: '延安市', districtName: '宝塔区' }
test('only removes an exact region prefix from presentation', () => {
  assert.equal(displayAddress({ ...address, detail: '陕西省延安市宝塔区村一号' }), '陕西省延安市宝塔区村一号')
  assert.equal(displayAddress({ ...address, detail: '村一号陕西省延安市宝塔区二楼' }), '陕西省延安市宝塔区村一号陕西省延安市宝塔区二楼')
  assert.equal(displayAddress({ ...address, detail: '陕西省延安市村一号' }), '陕西省延安市宝塔区陕西省延安市村一号')
})
