import { test, expect } from './support/fixtures.js'

test.describe('管理后台', () => {
  test('板块表格可见，删除确认框可取消', async ({ page, e2e }) => {
    await page.goto('/login', { waitUntil: 'domcontentloaded' })
    await page.getByPlaceholder('用户名').fill(e2e.credentials.username)
    await page.getByPlaceholder('密码').fill(e2e.credentials.password)
    await page.locator('form .submit').click()
    await expect(page.locator('.header .username')).toBeVisible()

    await page.goto('/admin', { waitUntil: 'domcontentloaded' })
    const boardTable = page.locator('.el-table').first()
    await expect(boardTable.getByText('帖子数', { exact: true })).toBeVisible()

    const rows = boardTable.locator('.el-table__row')
    // 表头先于数据渲染，rows.count() 不会自动等待，先等首行出现再计数
    await expect(rows.first()).toBeVisible()
    expect(await rows.count()).toBeGreaterThanOrEqual(1)
    await rows.first().getByRole('button', { name: '删除' }).click()

    const box = page.locator('.el-message-box')
    await expect(box).toBeVisible()
    await expect(box).toContainText('提示')
    await expect(box).toContainText('确定删除板块')

    // 只取消，绝不点「确定」，保证不产生删除
    await box.getByRole('button', { name: '取消' }).click()
    await expect(box).toBeHidden()
    await expect(rows.first()).toBeVisible()
  })
})
