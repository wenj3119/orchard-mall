import { spawn } from 'node:child_process'
import { createWriteStream, mkdirSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const target = process.argv[2]
if (!['weapp', 'alipay'].includes(target)) throw new Error('Use weapp or alipay')
const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const logDir = resolve(root, '../.artifacts/miniapp-builds')
mkdirSync(logDir, { recursive: true, mode: 0o700 })
const log = createWriteStream(resolve(logDir, `${target}-${new Date().toISOString().replace(/[:.]/g, '-')}.log`), { mode: 0o600 })
const child = spawn(process.execPath, [resolve(root, 'node_modules/@tarojs/cli/bin/taro'), 'build', '--type', target, '--no-check'], {
  cwd: root,
  env: process.env,
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
child.on('close', code => {
  clearTimeout(timer)
  log.end()
  process.exitCode = timedOut ? 124 : code ?? 1
})
