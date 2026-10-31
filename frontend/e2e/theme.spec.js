import { test, expect } from './support/fixtures.js'

const THEME_KEY = 'jf_theme'

test.describe('主题切换', () => {
  test('选择深色后加 html.dark 并持久化，刷新后保持', async ({ page }) => {
    await page.goto('./', { waitUntil: 'domcontentloaded' })
    await page.getByRole('button', { name: '切换主题' }).click()
    await page.getByRole('menuitem', { name: '深色' }).click()

    await expect(page.locator('html')).toHaveClass(/dark/)
    await expect.poll(() => page.evaluate((key) => localStorage.getItem(key), THEME_KEY)).toBe('dark')

    await page.reload({ waitUntil: 'domcontentloaded' })
    await expect(page.locator('html')).toHaveClass(/dark/)
  })

  test('选择浅色后移除 dark 类', async ({ page }) => {
    await page.goto('./', { waitUntil: 'domcontentloaded' })
    await page.getByRole('button', { name: '切换主题' }).click()
    await page.getByRole('menuitem', { name: '浅色' }).click()

    await expect(page.locator('html')).not.toHaveClass(/dark/)
    await expect.poll(() => page.evaluate((key) => localStorage.getItem(key), THEME_KEY)).toBe('light')
  })
})
