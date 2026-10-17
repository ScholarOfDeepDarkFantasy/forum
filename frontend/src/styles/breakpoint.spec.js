import { describe, it, expect } from 'vitest'
import { readFileSync, readdirSync, existsSync } from 'node:fs'
import { resolve, join, sep } from 'node:path'
import { MOBILE_BREAKPOINT } from '../composables/useResponsive'

// 说明：这里本可以写成 new URL('../', import.meta.url)，但 vitest 在 jsdom 环境下
// 把 import.meta.url 暴露成 http://localhost/... 而非 file://，fileURLToPath 会抛
// "The URL must be of scheme file"。故改用 process.cwd()（vitest 以项目根为 cwd），
// 并显式校验目录确实是 frontend/src，避免在错误目录下静默扫描出「零个断点」而假绿。
const SRC_ROOT = resolve(process.cwd(), 'src')

if (!existsSync(resolve(SRC_ROOT, 'composables/useResponsive.js'))) {
  throw new Error(
    `断点一致性用例需以 frontend/ 为工作目录运行（当前 cwd=${process.cwd()}，解析出的 src=${SRC_ROOT}）`
  )
}

/** 递归收集 src 下所有 .vue / .css 文件（返回相对 cwd 的可读路径，便于报错定位） */
function collectStyleFiles(dir) {
  const entries = readdirSync(dir, { withFileTypes: true, recursive: true })
  return entries
    .filter((e) => e.isFile() && /\.(vue|css)$/.test(e.name))
    .map((e) => join(e.parentPath ?? e.path, e.name).split(sep).join('/'))
}

/** 取出文件里所有 max-width: Npx 中的 N */
function maxWidthPixels(source) {
  return [...source.matchAll(/max-width:\s*(\d+)px/g)].map((m) => Number(m[1]))
}

/**
 * 去掉 CSS/SCSS 注释再扫描。
 * 注释正是断点最常被写进文档的地方（tokens.css 就写了三处说明），
 * 若不去注释，一段形如 `@media (max-width: 768px) { ... }` 的示例会被当成
 * 真实媒体查询，让计数虚高、也让「找错断点」的报错指向注释而非代码。
 */
function stripComments(source) {
  return source.replace(/\/\*[\s\S]*?\*\//g, '')
}

/** 返回 src 下所有 max-width 媒体查询的 { file, px } */
function collectMediaBreakpoints() {
  const found = []
  for (const file of collectStyleFiles(SRC_ROOT)) {
    const source = stripComments(readFileSync(file, 'utf8'))
    // 只关心媒体查询内的断点；普通声明的容器宽度（如 `.page { max-width: 1100px }`）
    // 与断点无关，不参与比较
    for (const block of source.matchAll(/@media[^{]*\{/g)) {
      for (const px of maxWidthPixels(block[0])) found.push({ file, px })
    }
  }
  return found
}

describe('断点一致性', () => {
  // 本用例刻意扫描整个 src，而不是只扫当前已知的几处：断点字面量目前散落在
  // tokens.css 的 --jf-breakpoint-mobile、useResponsive.js 的 MOBILE_BREAKPOINT
  // 以及 HomeView / PostDetailView / LoginView / RegisterView / NotificationsView
  // 五个视图里的 @media (max-width: 768px) 块，靠人肉同步必然漂移。
  //
  // 注意：当前 src 下 @media (max-width: Npx) 的数量为 5（全部是 768px）。容器宽度
  // 形如 `max-width: 1100px` 的普通声明不是媒体查询，不参与计数。所以本用例现在通过
  // 是预期结果，不是空转——它保护这五处 @media 块与 MOBILE_BREAKPOINT 不漂移。
  it('所有 max-width 媒体查询断点与 MOBILE_BREAKPOINT 一致', () => {
    const breakpoints = collectMediaBreakpoints()
    const offenders = breakpoints
      .filter(({ px }) => px !== MOBILE_BREAKPOINT)
      .map(({ file, px }) => `${file}: @media 断点为 ${px}px，与 MOBILE_BREAKPOINT=${MOBILE_BREAKPOINT} 不一致`)

    // 报错文案已带上文件与两个数值，断言 offenders 为空即可直接定位
    expect(offenders).toEqual([])
  })

  it('tokens.css 的 --jf-breakpoint-mobile 与 MOBILE_BREAKPOINT 一致', () => {
    const css = readFileSync(resolve(SRC_ROOT, 'styles/tokens.css'), 'utf8')
    const m = css.match(/--jf-breakpoint-mobile:\s*(\d+)px/)
    expect(m, '未在 tokens.css 中找到 --jf-breakpoint-mobile').not.toBeNull()
    expect(Number(m[1])).toBe(MOBILE_BREAKPOINT)
  })

  it('收集到的样式文件包含 styles 与 views 下的已知文件（防止扫描路径写错而假绿）', () => {
    const files = collectStyleFiles(SRC_ROOT)
    expect(files.some((f) => f.endsWith('src/styles/tokens.css'))).toBe(true)
    expect(files.some((f) => f.endsWith('src/views/PostDetailView.vue'))).toBe(true)
    // .js 不在扫描范围内（用例文件自身含有断点字面量字符串，扫进来会自我干扰）
    expect(files.some((f) => f.endsWith('src/composables/useResponsive.js'))).toBe(false)
  })

  // 当前 src 下真实媒体查询数量为 5，上一用例已有真实输入。但为了不依赖「此刻正好
  // 有 @media」这一事实（某次改动把它们全删掉后，正则写错也照样假绿），此处仍对
  // 扫描逻辑喂入固定样本：命中、容器宽度不误报、注释不误报。
  it('扫描逻辑本身能识别断点、且不误报容器宽度与注释', () => {
    const sample = `
      /* 说明：@media (max-width: 768px) { 示例 } 不应被计入 */
      .page { max-width: 1100px; }
      @media (max-width: 768px) { .a { display: none; } }
      @media (max-width: 992px) { .b { display: none; } }
    `
    const px = [...stripComments(sample).matchAll(/@media[^{]*\{/g)].flatMap((b) => maxWidthPixels(b[0]))
    expect(px).toEqual([768, 992])
  })
})
