import { defineConfig, devices } from '@playwright/test'

// 统一补尾斜杠：公网入口是子路径（https://treenewbee.men/forum/），
// 不补的话 page.goto('login') 会解析成 https://treenewbee.men/login，
// 而源站只在 /forum/ 下挂了应用（根路径 404）。spec 里一律用相对路径。
const rawBaseURL = process.env.E2E_BASE_URL || 'http://127.0.0.1:5173'
const baseURL = rawBaseURL.endsWith('/') ? rawBaseURL : `${rawBaseURL}/`
const channel = process.env.E2E_CHANNEL || undefined
const isLocal = /^https?:\/\/(127\.0\.0\.1|localhost|\[::1\])(:\d+)?(\/|$)/i.test(baseURL)
const isRemote = !isLocal

export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  workers: 1,
  forbidOnly: !!process.env.CI,
  retries: isRemote ? 1 : 0,
  // 写数据的 E2E 不能被重试「吸收」成 flaky 后静默通过：清理失败必须红。
  failOnFlakyTests: true,
  timeout: 30_000,
  expect: { timeout: 8_000 },
  reporter: [['list'], ['html', { open: 'never' }]],
  use: {
    baseURL,
    locale: 'zh-CN',
    colorScheme: 'light',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'off',
  },
  projects: [
    {
      name: 'chromium',
      // 默认 Chromium；E2E_CHANNEL=msedge 时改用系统 Edge，免下载浏览器
      use: { ...devices['Desktop Chrome'], channel },
    },
  ],
})
