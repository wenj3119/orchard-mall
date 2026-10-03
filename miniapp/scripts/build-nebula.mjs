import { spawn } from 'node:child_process'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const target = process.argv[2]
if (!['weapp', 'alipay'].includes(target)) throw new Error('Use weapp or alipay')
const base = process.env.TARO_APP_API_BASE
if (!base || !/^https:\/\/[^/]+$/.test(base) || base.includes('example.invalid')) {
  throw new Error('Set TARO_APP_API_BASE to the verified HTTPS API origin without a trailing slash')
}
if (process.env.TARO_APP_DEV_PAYMENT_ENABLED === 'true') {
  throw new Error('The Nebula test build must not enable simulated payment before access restriction is verified')
}
if (process.env.TARO_APP_DEV_LOGIN_ENABLED === 'true') {
  throw new Error('The Nebula build must not show development identity login')
}
const script = resolve(dirname(fileURLToPath(import.meta.url)), 'build.mjs')
const child = spawn(process.execPath, [script, target], {
  stdio: 'inherit',
  env: { ...process.env, TARO_APP_DEV_PAYMENT_ENABLED: 'false', TARO_APP_DEV_LOGIN_ENABLED: 'false' }
})
child.on('error', error => { console.error(error); process.exitCode = 1 })
child.on('close', code => { process.exitCode = code ?? 1 })
