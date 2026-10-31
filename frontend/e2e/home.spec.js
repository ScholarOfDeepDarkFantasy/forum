import { test, expect } from './support/fixtures.js'

test.describe('首页', () => {
  test('展示品牌、帖子列表与板块筛选', async ({ page, e2e }) => {
    await page.goto('./', { waitUntil: 'domcontentloaded' })
    await expect(page.locator('.header .brand')).toContainText('JavaForum')
    await expect(page.locator('.post-item').first()).toBeVisible()
    // 断言本轮 bootstrap 的帖子确实进了首页列表（数据引导与首页查询路径真正连通）
    await expect(page.locator('.post-item').filter({ hasText: e2e.title })).toBeVisible()
    await expect(page.locator('.board-tag').first()).toContainText('全部')
    expect(e2e.boardId).toBeTruthy()
  })

  test('未登录时 header 提供登录入口', async ({ page }) => {
    await page.goto('./', { waitUntil: 'domcontentloaded' })
    await expect(page.locator('.header').getByRole('button', { name: '登录' })).toBeVisible()
  })
})
