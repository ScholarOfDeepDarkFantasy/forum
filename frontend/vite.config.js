import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vite'
import { fileURLToPath, URL } from 'node:url'

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
export default defineConfig({
  base: basePath,
  plugins: [vue()],
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
})
