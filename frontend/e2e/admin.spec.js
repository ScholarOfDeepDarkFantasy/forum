import { test, expect } from './support/fixtures.js'

test.describe('管理后台', () => {
  test('板块表格可见，删除确认框可取消且不删数据', async ({ page, e2e }, testInfo) => {
    // ElMessageBox.confirm 取消时以 'cancel' 拒绝，fixtures 仅对带此 annotation 的用例放行该 pageerror。
    testInfo.annotations.push({ type: 'expected-cancel-dialog', description: '点取消时 MessageBox 会 reject cancel' })
    await page.goto('login', { waitUntil: 'domcontentloaded' })
    await page.getByPlaceholder('用户名').fill(e2e.credentials.username)
    await page.getByPlaceholder('密码').fill(e2e.credentials.password)
    await page.locator('form .submit').click()
    await expect(page.locator('.header .username')).toBeVisible()

    await page.goto('admin', { waitUntil: 'domcontentloaded' })
    const boardTable = page.locator('.el-table').first()
    await expect(boardTable.getByText('帖子数', { exact: true })).toBeVisible()

    const rows = boardTable.locator('.el-table__row')
    // 表头先于数据渲染，rows.count() 不会自动等待，先等首行出现再计数
    await expect(rows.first()).toBeVisible()
    const rowsBefore = await rows.count()
    expect(rowsBefore).toBeGreaterThanOrEqual(1)
    // 记录取消前首行内容与行数：只断言「首行仍可见」会被「删掉了首行但库里还有别行」骗过。
    const rowTextsBefore = await rows.allInnerTexts()
    await rows.first().getByRole('button', { name: '删除' }).click()

    const box = page.locator('.el-message-box')
    await expect(box).toBeVisible()
    await expect(box).toContainText('提示')
    await expect(box).toContainText('确定删除板块')

    // 只取消，绝不点「确定」，保证不产生删除
    await box.getByRole('button', { name: '取消' }).click()
    await expect(box).toBeHidden()
    await expect(rows.first()).toBeVisible()
    // 行内容与行数都未变——证明取消路径没有产生删除
    await expect(rows.first()).toBeVisible()
    expect(await rows.allInnerTexts()).toEqual(rowTextsBefore)
    expect(await rows.count()).toBe(rowsBefore)
  })
})
