import { test, expect } from './support/fixtures.js'

test.describe('登录与登出', () => {
  // 错误密码在 dev 构建下会让 Vue 把未捕获的 401 重抛成 pageerror，用 annotation 精确声明预期。
  test('错误密码提示后端文案', { annotation: { type: 'expected-login-401' } }, async ({ page, e2e }) => {
    await page.goto('login', { waitUntil: 'domcontentloaded' })
    await page.getByPlaceholder('用户名').fill(e2e.credentials.username)
    await page.getByPlaceholder('密码').fill(`${e2e.credentials.password}-wrong`)
    await page.locator('form .submit').click()
    await expect(page.locator('.el-message').filter({ hasText: '用户名或密码错误' })).toBeVisible()
  })

  test('正确密码登录后显示用户菜单，登出后清理本地状态', async ({ page, e2e }) => {
    await page.goto('login', { waitUntil: 'domcontentloaded' })
    await page.getByPlaceholder('用户名').fill(e2e.credentials.username)
    await page.getByPlaceholder('密码').fill(e2e.credentials.password)
    await page.locator('form .submit').click()

    await expect(page.locator('.el-message').filter({ hasText: '登录成功' })).toBeVisible()
    await expect(page.locator('.header .username')).toBeVisible()
    // header 展示 nickname || username，公网账号可能改过昵称，不写死「管理员」
    expect((await page.locator('.header .username').innerText()).trim()).not.toBe('')

    await page.locator('.header .user-chip').click()
    await page.getByRole('menuitem', { name: '退出登录' }).click()

    await expect(page.locator('.header').getByRole('button', { name: '登录' })).toBeVisible()
    await expect.poll(() => page.evaluate(() => ({
      token: localStorage.getItem('jf_token'),
      user: localStorage.getItem('jf_user'),
    }))).toEqual({ token: null, user: null })
  })
})
