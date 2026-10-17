import { describe, it, expect } from 'vitest'
import { readFileSync, readdirSync, existsSync } from 'node:fs'
import { resolve, join, sep } from 'node:path'

/**
 * 分页样式收敛的守卫用例。
 *
 * 背景：`.pager { margin-top; justify-content }` 这条规则曾在 5 个视图各写一遍，
 * 6 个 el-pagination 全部重复。抽到 styles/common.css 的 `.jf-pager` 后，
 * 若后来有人「顺手」在某个视图里再补一份本地 .pager，样式会重新分叉
 * （改一处不动另一处），而视觉上完全看不出来 —— 只有代码层面的断言能发现。
 *
 * 与 breakpoint.spec.js 同样的理由用 process.cwd()：vitest 在 jsdom 下把
 * import.meta.url 暴露成 http:// 协议，fileURLToPath 会抛错。
 */
const SRC_ROOT = resolve(process.cwd(), 'src')
const VIEWS_DIR = resolve(SRC_ROOT, 'views')
const COMMON_CSS = resolve(SRC_ROOT, 'styles/common.css')
const MAIN_JS = resolve(SRC_ROOT, 'main.js')

if (!existsSync(resolve(SRC_ROOT, 'composables/useResponsive.js'))) {
  throw new Error(
    `分页样式用例需以 frontend/ 为工作目录运行（当前 cwd=${process.cwd()}，解析出的 src=${SRC_ROOT}）`
  )
}

const read = (p) => readFileSync(p, 'utf8')

/** 递归收集 src 下所有 .vue 文件（相对路径，便于报错定位） */
function collectVueFiles() {
  const entries = readdirSync(SRC_ROOT, { withFileTypes: true, recursive: true })
  return entries
    .filter((e) => e.isFile() && e.name.endsWith('.vue'))
    .map((e) => join(e.parentPath ?? e.path, e.name).split(sep).join('/'))
}

/**
 * 视图里 el-pagination 的起始标签片段。
 *
 * 不能直接用 /<el-pagination\b[\s\S]*?>/ —— 属性值里可能出现 '>'，
 * 例如 v-if="myPosts.totalPages > 1"，惰性匹配会在那里提前截断，
 * 导致后面的 class 属性读不到（本用例初版正是这样误报 ProfileView）。
 * 改为按引号状态逐字符扫描，只在引号外遇到 '>' 才结束。
 */
function paginationTags(source) {
  const tags = []
  let from = 0
  for (;;) {
    const start = source.indexOf('<el-pagination', from)
    if (start === -1) break

    let i = start
    let quote = null
    while (i < source.length) {
      const ch = source[i]
      if (quote) {
        if (ch === quote) quote = null
      } else if (ch === '"' || ch === "'") {
        quote = ch
      } else if (ch === '>') {
        break
      }
      i++
    }
    tags.push(source.slice(start, i + 1))
    from = i + 1
  }
  return tags
}

describe('分页样式收敛', () => {
  it('common.css 定义了 .jf-pager 且规则内容完整', () => {
    const css = read(COMMON_CSS)
    const rule = css.match(/\.jf-pager\s*\{([^}]*)\}/)
    expect(rule, 'common.css 中应有 .jf-pager 规则').not.toBeNull()

    const body = rule[1]
    // 两条声明缺任一条，分页器的位置就会与设计不符
    expect(body).toMatch(/margin-top\s*:\s*var\(--jf-space-4\)/)
    expect(body).toMatch(/justify-content\s*:\s*center/)
  })

  it('main.js 在 tokens.css 之后引入 common.css（令牌先可用）', () => {
    const js = read(MAIN_JS)
    const tokensIdx = js.indexOf("styles/tokens.css")
    const commonIdx = js.indexOf("styles/common.css")
    expect(tokensIdx, 'main.js 应引入 tokens.css').toBeGreaterThan(-1)
    expect(commonIdx, 'main.js 应引入 common.css').toBeGreaterThan(-1)
    // common.css 里用了 var(--jf-space-4)，必须晚于令牌定义
    expect(commonIdx).toBeGreaterThan(tokensIdx)
  })

  it('没有任何 .vue 文件重新定义 .pager 规则', () => {
    const offenders = collectVueFiles().filter((f) => /\.pager\s*\{/.test(read(resolve(SRC_ROOT, f))))
    expect(offenders, `以下文件仍定义 .pager：${offenders.join(', ')}`).toEqual([])
  })

  it('每个 el-pagination 都带 jf-pager 类', () => {
    const offenders = []
    for (const f of collectVueFiles()) {
      for (const tag of paginationTags(read(resolve(SRC_ROOT, f)))) {
        if (!/\bclass="[^"]*\bjf-pager\b/.test(tag)) {
          offenders.push(`${f}: ${tag.replace(/\s+/g, ' ').trim().slice(0, 80)}`)
        }
      }
    }
    expect(offenders, `以下分页器缺少 jf-pager：\n${offenders.join('\n')}`).toEqual([])
  })

  it('jf-pager 的使用处数量与 el-pagination 数量一致（无多余 class）', () => {
    const files = collectVueFiles()
    const paginations = files.flatMap((f) => paginationTags(read(resolve(SRC_ROOT, f))))
    const usages = files.reduce(
      (n, f) => n + (read(resolve(SRC_ROOT, f)).match(/class="[^"]*\bjf-pager\b/g) ?? []).length,
      0
    )

    // 精确相等而非 >=：多出来的 jf-pager 会作用在非分页元素上，把 margin-top 和
    // justify-content 套到不该套的地方，是另一类回归。
    expect(usages).toBe(paginations.length)
    // 数量本身也要有下界：全部删掉时上面的相等断言会以 0 === 0 通过
    expect(paginations.length).toBeGreaterThanOrEqual(6)
  })
})
