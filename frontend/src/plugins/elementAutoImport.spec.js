// @vitest-environment node
// 说明：本 spec 只读源码并求值构建配置，不需要 DOM。必须固定在 node 环境——
// jsdom 环境会把 Vite 转换后的模块 import.meta.url 暴露成 http://localhost/...，
// vite.config.js 里的 fileURLToPath 会抛 "The URL must be of scheme file"
//（同样的原因见 src/styles/breakpoint.spec.js 与 src/styles/common.spec.js 的注释）。
import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

const read = (rel) => readFileSync(fileURLToPath(new URL(rel, import.meta.url)), 'utf8')

describe('Element Plus 按需引入（构建期）', () => {
  it('main.js 不再全量注册 Element Plus，并保留 v-loading 指令与程序式样式', () => {
    const main = read('../main.js')
    expect(main).not.toContain('app.use(ElementPlus')
    expect(main).not.toContain("from 'element-plus/dist/index.css'")
    expect(main).toContain("app.directive('loading', ElLoading.directive)")
    expect(main).toContain('element-plus/theme-chalk/base.css')
    expect(main).toContain('element-plus/theme-chalk/dark/css-vars.css')
    for (const name of ['message', 'message-box', 'notification', 'loading']) {
      expect(main).toContain(`element-plus/es/components/${name}/style/css`)
    }
  })

  it('vite 配置在构建期挂载按需解析插件、测试期不挂载；App.vue 提供本地化', () => {
    const app = read('../App.vue')
    expect(app).toContain('el-config-provider')
    expect(app).toContain(':locale="zhCn"')
    return import('../../vite.config.js').then(async ({ default: config }) => {
      const testCfg = await config({ mode: 'test' })
      const prodCfg = await config({ mode: 'production' })
      expect(testCfg.plugins).toHaveLength(1)
      expect(prodCfg.plugins).toHaveLength(2)
      expect(prodCfg.plugins.some((p) => String(p?.name).includes('unplugin-vue-components'))).toBe(true)
    })
  })
})
