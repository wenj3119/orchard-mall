import { spawn } from 'node:child_process'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const target = process.argv[2]
if (!['weapp', 'alipay'].includes(target)) throw new Error('Use weapp or alipay')
if (process.env.TARO_APP_DEV_PAYMENT_ENABLED === 'true') {
  throw new Error('The Nebula test build must not enable simulated payment before access restriction is verified')
}
if (process.env.TARO_APP_DEV_LOGIN_ENABLED === 'true') {
  throw new Error('The Nebula build must not show development identity login')
}
const script = resolve(dirname(fileURLToPath(import.meta.url)), 'build.mjs')
const child = spawn(process.execPath, [script, target, 'server'], {
  stdio: 'inherit',
  env: process.env
})
child.on('error', error => { console.error(error); process.exitCode = 1 })
child.on('close', code => { process.exitCode = code ?? 1 })
