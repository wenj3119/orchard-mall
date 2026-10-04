import { spawn } from 'node:child_process'
import { createWriteStream, mkdirSync, readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const target = process.argv[2]
if (!['weapp', 'alipay'].includes(target)) throw new Error('Use weapp or alipay')
const variant = process.argv[3] || 'local'
if (!['server', 'local'].includes(variant)) throw new Error('Use server or local')
const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const server = JSON.parse(readFileSync(resolve(root, 'server-build.json'), 'utf8'))
const apiBase = variant === 'server' ? server.apiOrigin : process.env.TARO_APP_API_BASE || 'http://127.0.0.1:18084'
if (!/^https?:\/\/[^/]+$/.test(apiBase) || apiBase.endsWith('/api')) throw new Error('API base must be an origin without /api or trailing slash')
if (variant === 'server' && (!/^https:\/\//.test(apiBase) || /(?:localhost|127\.0\.0\.1|192\.168\.)/.test(apiBase))) throw new Error('Server API origin must be public HTTPS')
const buildId = `${variant}-${target}-${new Date().toISOString().replace(/[:.]/g, '')}`
const logDir = resolve(root, '../.artifacts/miniapp-builds')
mkdirSync(logDir, { recursive: true, mode: 0o700 })
const log = createWriteStream(resolve(logDir, `${buildId}.log`), { mode: 0o600 })
const child = spawn(process.execPath, [resolve(root, 'node_modules/@tarojs/cli/bin/taro'), 'build', '--type', target, '--no-check'], {
  cwd: root,
  env: { ...process.env, ORCHARD_BUILD_VARIANT: variant, ORCHARD_BUILD_ID: buildId, TARO_APP_API_BASE: apiBase,
    ...(variant === 'server' ? { TARO_APP_DEV_LOGIN_ENABLED: 'false', TARO_APP_DEV_PAYMENT_ENABLED: 'false' } : {}) },
  stdio: ['ignore', 'pipe', 'pipe'],
  detached: process.platform !== 'win32'
})
for (const stream of [child.stdout, child.stderr]) stream.on('data', chunk => { log.write(chunk); (stream === child.stdout ? process.stdout : process.stderr).write(chunk) })
let timedOut = false
const stop = signal => {
  if (child.exitCode !== null) return
  try {
    if (process.platform === 'win32') child.kill(signal)
    else process.kill(-child.pid, signal) // child is the process-group leader started by this script
  } catch (error) {
    if (error.code !== 'ESRCH') throw error
  }
}
const timer = setTimeout(() => {
  timedOut = true
  console.error(`Build exceeded 120 seconds; terminating only this build's process group. Log: ${log.path}`)
  stop('SIGTERM')
  setTimeout(() => stop('SIGKILL'), 5000).unref()
}, 120_000)
for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => stop(signal))
child.on('error', error => { clearTimeout(timer); console.error(error); log.end(); process.exitCode = 1 })
child.on('close', async code => {
  clearTimeout(timer)
  log.end()
  if (timedOut || code !== 0) { process.exitCode = timedOut ? 124 : code ?? 1; return }
  const verification = spawn(process.execPath, [resolve(root, 'scripts/verify-build.mjs'), variant, target, apiBase, buildId], { cwd: root, stdio: 'inherit' })
  verification.on('error', error => { console.error(error); process.exitCode = 1 })
  verification.on('close', verifyCode => { process.exitCode = verifyCode ?? 1 })
})
