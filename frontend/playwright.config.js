import { defineConfig, devices } from '@playwright/test'

const baseURL = process.env.E2E_BASE_URL || 'http://127.0.0.1:5173'
const channel = process.env.E2E_CHANNEL || undefined
const isRemote = !/^https?:\/\/(127\.0\.0\.1|localhost)(:\d+)?(\/|$)/.test(baseURL)

export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  workers: 1,
  forbidOnly: !!process.env.CI,
  retries: isRemote ? 1 : 0,
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
