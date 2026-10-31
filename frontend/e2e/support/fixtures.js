import { test as base, expect } from '@playwright/test'
import { bootstrapData } from './api.js'

const CSP_ERROR = /Content Security Policy|Refused to/i

export const test = base.extend({
  // 一个 worker 只引导一次数据，所有 spec 共享；测试结束统一清理。
  e2e: [
    async ({}, use, workerInfo) => {
      const data = await bootstrapData()
      console.log(`[E2E] worker ${workerInfo.workerIndex}: board=${data.boardId} post=${data.postId}`)
      await use(data)
      await data.cleanup()
    },
    { scope: 'worker' },
  ],

  // auto: true 让每个 spec 自动获得守卫，无需在 test 参数里声明。
  consoleGuard: [
    async ({ page }, use) => {
      const problems = []
      page.on('console', (msg) => {
        if (msg.type() === 'error' && CSP_ERROR.test(msg.text())) problems.push(`console: ${msg.text()}`)
      })
      page.on('pageerror', (error) => {
        const message = typeof error === 'string' ? error : error?.message || String(error)
        // 管理页点「取消」时 ElMessageBox.confirm 以 'cancel' 拒绝，Vue 会自行记录，这里兜底放行。
        if (message === 'cancel') return
        problems.push(`pageerror: ${message}`)
      })
      await use(problems)
      expect(problems, `浏览器控制台/页面异常:\n${problems.join('\n')}`).toEqual([])
    },
    { auto: true },
  ],
})

export { expect }
