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
    async ({ page }, use, testInfo) => {
      const problems = []
      page.on('console', (msg) => {
        if (msg.type() === 'error' && CSP_ERROR.test(msg.text())) problems.push(`console: ${msg.text()}`)
      })
      page.on('pageerror', (error) => {
        const message = typeof error === 'string' ? error : error?.message || String(error)
        // 管理页点「取消」时 ElMessageBox.confirm 以 'cancel' 拒绝，Vue 会自行记录，这里兜底放行。
        if (message === 'cancel') return
        // 登录页错误密码：axios 以 Error('Request failed with status code 401') 拒绝，
        // LoginView.submit 只有 try/finally 没有 catch，Vue 3 dev 构建会把未捕获错误重新抛给浏览器。
        // 仅放行「测试显式声明 annotation + 该确定文案」的组合，其它页面/其它错误照常拦截。
        const expectsLogin401 = testInfo.annotations.some((a) => a.type === 'expected-login-401')
        if (expectsLogin401 && message === 'Request failed with status code 401') return
        problems.push(`pageerror: ${message}`)
      })
      await use(problems)
      expect(problems, `浏览器控制台/页面异常:\n${problems.join('\n')}`).toEqual([])
    },
    { auto: true },
  ],
})

export { expect }
