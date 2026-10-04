import { spawn } from 'node:child_process'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const target = process.argv[2]
if (!['weapp', 'alipay'].includes(target)) throw new Error('Use weapp or alipay')
const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const apiBase = process.env.TARO_APP_API_BASE || 'http://127.0.0.1:18084'
if (!/^https?:\/\/[^/]+$/.test(apiBase) || apiBase.endsWith('/api')) throw new Error('Local API base must be an origin without /api or trailing slash')
console.log(`Local watch: dist/local/${target}; API: ${apiBase}`)
const child = spawn(process.execPath, [resolve(root, 'node_modules/@tarojs/cli/bin/taro'), 'build', '--type', target, '--watch', '--no-check'], {
  cwd: root, stdio: 'inherit',
  env: { ...process.env, ORCHARD_BUILD_VARIANT: 'local', ORCHARD_BUILD_ID: `local-${target}-watch`, TARO_APP_API_BASE: apiBase }
})
for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => child.kill(signal))
child.on('error', error => { console.error(error); process.exitCode = 1 })
child.on('close', code => { process.exitCode = code ?? 1 })
