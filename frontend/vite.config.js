import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vite'
import { fileURLToPath, URL } from 'node:url'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'

/**
 * 部署路径。
 *
 * 默认 '/'（根路径），开发与本地预览行为不变。
 * 需要部署到子路径时用环境变量覆盖，例如：VITE_BASE_PATH=/forum/ pnpm build
 *
 * 注意 Vite 对 base 的取值限制很严：只接受绝对 URL、'./' 或空串。
 * 写成 './forum' 这类值不会报错退出，只在构建日志里留一条 warn 然后**静默按 '/' 处理**，
 * 产物里仍然是 /assets/...，子路径部署必然 404。src/viteConfig.spec.js 守住这一点。
 */
const basePath = process.env.VITE_BASE_PATH || '/'

// 与 base 配套的接口前缀：根路径下是 /api，子路径下是 /forum/api
const apiPrefix = `${basePath === '/' ? '' : basePath.replace(/\/+$/, '')}/api`

// 开发环境代理到后端 8080，前端统一走同一前缀的相对路径，避免 CORS
export default defineConfig(({ mode }) => ({
  base: basePath,
  plugins: [
    vue(),
    // 关键：vitest 下保持「不解析组件」——测试用 global.stubs 拦截 el-* 标签；
    // 若测试期也注入组件，stub 不再命中，既有 321 个用例会大面积失效（实测 63 例 / 7 spec 失败）。
    // 构建期才挂按需解析：模板里的 <el-*> 在编译期变成
    //   import { ElButton } from 'element-plus/es'
    //   import 'element-plus/es/components/button/style/css'
    // importStyle: 'css' 复用官方 theme-chalk 的 css 产物，不引入 sass 编译链。
    // dirs: [] 关闭目录扫描（不把 src/components 全部注册成全局组件；自有组件都显式 import）。
    // dts: false —— 仓库没有 TypeScript，不生成 components.d.ts。
    ...(mode === 'test'
      ? []
      : [Components({ resolvers: [ElementPlusResolver({ importStyle: 'css' })], dirs: [], dts: false })])
  ],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  server: {
    port: 5173,
    proxy: {
      [apiPrefix]: {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  },
  test: {
    environment: 'jsdom',
    globals: true,
    include: ['src/**/*.spec.js']
  }
}))
