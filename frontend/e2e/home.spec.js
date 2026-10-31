import { test, expect } from './support/fixtures.js'

test.describe('首页', () => {
  test('展示品牌、帖子列表与板块筛选', async ({ page, e2e }) => {
    await page.goto('./', { waitUntil: 'domcontentloaded' })
    await expect(page.locator('.header .brand')).toContainText('JavaForum')
    await expect(page.locator('.post-item').first()).toBeVisible()
    // 断言本轮 bootstrap 的帖子确实进了首页列表（数据引导与首页查询路径真正连通）
    await expect(page.locator('.post-item').filter({ hasText: e2e.title })).toBeVisible()
    await expect(page.locator('.board-tag').first()).toContainText('全部')

    // 板块筛选：点击本轮数据所在板块，断言高亮切换、列表只剩该板块的帖子
    const boardTag = page.locator('.board-tag').filter({ hasText: e2e.boardName })
    await expect(boardTag).toBeVisible()
    await boardTag.click()
    await expect(boardTag).toHaveClass(/el-tag--primary/)
    await expect(page.locator('.post-item').filter({ hasText: e2e.title })).toBeVisible()
    const filtered = page.locator('.post-item')
    const filteredTexts = await filtered.allTextContents()
    expect(filteredTexts.length).toBeGreaterThan(0)
    for (const text of filteredTexts) expect(text).toContain(e2e.boardName)

    // 再点回「全部」，断言列表恢复（能再次看到本轮帖子）
    await page.locator('.board-tag').first().click()
    await expect(page.locator('.board-tag').first()).toHaveClass(/el-tag--primary/)
    await expect(page.locator('.post-item').filter({ hasText: e2e.title })).toBeVisible()
  })

  test('未登录时 header 提供登录入口', async ({ page }) => {
    await page.goto('./', { waitUntil: 'domcontentloaded' })
    await expect(page.locator('.header').getByRole('button', { name: '登录' })).toBeVisible()
  })
})
