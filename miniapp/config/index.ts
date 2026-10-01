import { defineConfig } from '@tarojs/cli'
export default defineConfig({
  projectName: 'orchard-mall',
  date: '2026-09-18',
  designWidth: 750,
  deviceRatio: { 640: 2.34 / 2, 750: 1, 828: 1.81 / 2 },
  sourceRoot: 'src',
  outputRoot: `dist/${process.env.TARO_ENV || 'weapp'}`,
  plugins: [],
  defineConstants: {
    API_BASE: JSON.stringify(process.env.TARO_APP_API_BASE || 'http://127.0.0.1:18084'),
    DEV_PAYMENT_ENABLED: JSON.stringify(process.env.TARO_APP_DEV_PAYMENT_ENABLED === 'true')
  },
  copy: { patterns: [], options: {} },
  framework: 'react',
  compiler: 'webpack5',
  mini: { postcss: { pxtransform: { enable: true, config: {} }, url: { enable: true, config: { limit: 1024 } }, cssModules: { enable: false } } }
})
