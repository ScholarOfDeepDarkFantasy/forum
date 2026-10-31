import { test, expect } from './support/fixtures.js'

test.describe('帖子详情', () => {
  test('渲染标题、Markdown 与点赞按钮', async ({ page, e2e }) => {
    await page.goto(`posts/${e2e.postId}`, { waitUntil: 'domcontentloaded' })
    await expect(page.getByRole('heading', { level: 1, name: e2e.title })).toBeVisible()

    const body = page.locator('.markdown-body')
    await expect(body.locator('h1')).toHaveText('E2E 标题')
    await expect(body.locator('strong')).toHaveText('加粗')
    await expect(body.locator('pre code').first()).toBeVisible()
    await expect(page.getByRole('button', { name: /👍 点赞/ })).toBeVisible()
  })
})
