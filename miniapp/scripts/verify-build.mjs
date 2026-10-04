import { readFileSync, readdirSync, writeFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const [variant, target, expectedArg, buildIdArg] = process.argv.slice(2)
if (!['server', 'local'].includes(variant) || !['weapp', 'alipay'].includes(target)) throw new Error('Use: verify-build.mjs server|local weapp|alipay')
const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const output = resolve(root, 'dist', variant, target)
const server = JSON.parse(readFileSync(resolve(root, 'server-build.json'), 'utf8'))
const expected = expectedArg || (variant === 'server' ? server.apiOrigin : undefined)
const manifestPath = join(output, 'build-info.json')
const manifest = buildIdArg ? null : JSON.parse(readFileSync(manifestPath, 'utf8'))
const buildId = buildIdArg || manifest.buildId
if (!expected && !manifest?.apiOrigin) throw new Error('Expected API origin missing')
const apiOrigin = expected || manifest.apiOrigin
if (variant === 'server' && apiOrigin !== server.apiOrigin) throw new Error('Server API origin differs from pinned origin')
const files = ['app.js', 'common.js']
const pagesDir = join(output, 'pages')
for (const page of readdirSync(pagesDir, { withFileTypes: true })) {
  if (page.isDirectory()) files.push(`pages/${page.name}/index.js`)
}
const contents = files.map(file => readFileSync(join(output, file), 'utf8'))
const common = contents[1]
const diagnostic = common.match(/apiOrigin:\s*"([^"]+)",\s*buildId:\s*"([^"]+)"/)
if (!diagnostic || diagnostic[1] !== apiOrigin || diagnostic[2] !== buildId) throw new Error('Compiled API origin or build identifier does not match')
if (variant === 'server') {
  const localApi = /https?:\/\/(?:localhost|0\.0\.0\.0|127(?:\.\d{1,3}){3}|10(?:\.\d{1,3}){3}|192\.168(?:\.\d{1,3}){2}|172\.(?:1[6-9]|2\d|3[01])(?:\.\d{1,3}){2})(?::\d+)?(?:\/api\b|(?=["'`]))/i
  const markerAt = common.indexOf('apiOrigin:')
  const boundaries = [...common.matchAll(/\b\d+:function\([^)]*\)\{/g)].map(match => match.index)
  const moduleStart = boundaries.filter(index => index <= markerAt).at(-1) || 0
  const moduleEnd = boundaries.find(index => index > markerAt) || common.length
  if ([common.slice(moduleStart, moduleEnd), contents[0], ...contents.slice(2)].some(code => localApi.test(code)))
    throw new Error('Server first-party executable contains a local API origin')
  const sourceDir = join(root, 'src')
  const checkSource = dir => {
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
      const file = join(dir, entry.name)
      if (entry.isDirectory()) checkSource(file)
      else if (/\.tsx?$/.test(entry.name) && localApi.test(readFileSync(file, 'utf8')))
        throw new Error(`First-party source contains a local API origin: ${file}`)
    }
  }
  checkSource(sourceDir)
}
if (target === 'weapp') {
  const project = JSON.parse(readFileSync(join(output, 'project.config.json'), 'utf8'))
  if (project.miniprogramRoot !== './') throw new Error('Generated WeChat project must use its own output directory as miniprogramRoot')
}
if (buildIdArg) writeFileSync(manifestPath, JSON.stringify({ variant, target, apiOrigin, buildId, ...(target === 'weapp' ? { miniprogramRoot: './' } : {}) }, null, 2) + '\n')
console.log(`Verified ${variant}/${target}: API ${apiOrigin}; build ${buildId}; root ${output}`)
