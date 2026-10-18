import { describe, it, expect } from 'vitest'
import { readFileSync, existsSync } from 'node:fs'
import { join } from 'node:path'

/**
 * 部署路径守卫。
 *
 * 起因：vite.config.js 里曾写成 `base: './forum'`，本意是「部署到 /forum 子路径」，
 * 但 Vite 对 base 只接受绝对 URL、'./' 或空串——这种值不会让构建失败，
 * 只会在日志里留一条 warn 然后**按 '/' 处理**，产物里仍是 /assets/...，
 * 上线后才表现为整站 404。这类静默降级靠人眼 review 很难兜住，所以固化成测试。
 *
 * 同时守住「子路径部署」真正需要的三处联动：base、路由 base、接口前缀。
 * 只改其中一处，其余两处就会在子路径下失效。
 */

// vitest 的工作目录是 frontend/，与 common.spec.js 保持同样的前置校验
function readSource(relPath) {
  const full = join(process.cwd(), relPath)
  if (!existsSync(full)) {
    throw new Error(
      `找不到 ${relPath}。请确认以 frontend/ 为工作目录运行测试（pnpm test）。`
    )
  }
  return readFileSync(full, 'utf8')
}

const viteConfig = readSource('vite.config.js')
const router = readSource('src/router/index.js')
const api = readSource('src/api/index.js')

describe('部署路径配置', () => {
  it('vite.config.js 的 base 不是 "./xxx" 这种会被 Vite 静默忽略的非法值', () => {
    // 非法写法示例：base: './forum'
    expect(viteConfig).not.toMatch(/base:\s*['"`]\.\/[^'"`]+['"`]/)
  })

  it('base 由 VITE_BASE_PATH 决定，默认 '/'', () => {
    expect(viteConfig).toMatch(/process\.env\.VITE_BASE_PATH\s*\|\|\s*['"]\/['"]/)
    expect(viteConfig).toMatch(/base:\s*basePath/)
  })

  it('接口前缀与 base 配套：根路径 /api，子路径 /forum/api', () => {
    expect(viteConfig).toMatch(/apiPrefix/)
    // 代理必须用计算出的前缀，写死 '/api' 会让子路径部署时开发代理失效
    expect(viteConfig).not.toMatch(/['"]\/api['"]\s*:\s*\{/)
    expect(viteConfig).toMatch(/\[apiPrefix\]\s*:\s*\{/)
  })

  it('路由 history 使用 BASE_URL 而不是写死根路径', () => {
    expect(router).toMatch(/createWebHistory\(import\.meta\.env\.BASE_URL\)/)
    expect(router).not.toMatch(/createWebHistory\(\s*\)/)
  })

  it('axios 的 baseURL 由 BASE_URL 派生', () => {
    expect(api).toMatch(/baseURL:\s*`\$\{BASE_URL\}api`/)
    // 写死 '/api' 会在子路径下打到根路径接口
    expect(api).not.toMatch(/baseURL:\s*['"]\/api['"]/)
  })

  it('401 跳转登录页也走 BASE_URL 派生路径，不写死 /login', () => {
    expect(api).toMatch(/LOGIN_PATH/)
    expect(api).not.toMatch(/location\.href\s*=\s*['"]\/login['"]/)
  })
})
