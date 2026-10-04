import { defineConfig } from '@tarojs/cli'
import serverBuild from '../server-build.json'

const variant = process.env.ORCHARD_BUILD_VARIANT
if (variant !== 'server' && variant !== 'local') throw new Error('Use an npm build/dev script: ORCHARD_BUILD_VARIANT must be server or local')
const apiBase = process.env.TARO_APP_API_BASE
if (!apiBase || !/^https?:\/\/[^/]+$/.test(apiBase) || apiBase.endsWith('/api')) throw new Error('TARO_APP_API_BASE must be an origin without /api or a trailing slash')
if (variant === 'server' && (apiBase !== serverBuild.apiOrigin || process.env.TARO_APP_DEV_LOGIN_ENABLED !== 'false' || process.env.TARO_APP_DEV_PAYMENT_ENABLED !== 'false'))
  throw new Error('Server build requires the pinned HTTPS API origin and disabled development login/payment')
if (variant === 'server' && !process.env.ORCHARD_BUILD_ID) throw new Error('Server build identifier is missing')
export default defineConfig({
  projectName: 'orchard-mall',
  date: '2026-09-18',
  designWidth: 750,
  deviceRatio: { 640: 2.34 / 2, 750: 1, 828: 1.81 / 2 },
  sourceRoot: 'src',
  outputRoot: `dist/${variant}/${process.env.TARO_ENV || 'weapp'}`,
  plugins: [],
  defineConstants: {
    API_BASE: JSON.stringify(apiBase),
    BUILD_ID: JSON.stringify(process.env.ORCHARD_BUILD_ID || `local-${process.env.TARO_ENV || 'weapp'}`),
    DEV_PAYMENT_ENABLED: JSON.stringify(process.env.TARO_APP_DEV_PAYMENT_ENABLED === 'true'),
    DEV_LOGIN_ENABLED: JSON.stringify(process.env.TARO_APP_DEV_LOGIN_ENABLED !== 'false')
  },
  copy: { patterns: [], options: {} },
  framework: 'react',
  compiler: 'webpack5',
  mini: { postcss: { pxtransform: { enable: true, config: {} }, url: { enable: true, config: { limit: 1024 } }, cssModules: { enable: false } } }
})
