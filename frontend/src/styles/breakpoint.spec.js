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

/**
 * src 下真实存在的媒体查询断点字面量数量：5 处 @media (max-width: 768px)
 * （HomeView / PostDetailView / LoginView / RegisterView / NotificationsView）。
 *
 * 为什么是精确值而不是 `>= 5`：一致性断言只比对「找出来的字面量」的值，删掉一整块
 * @media（或把某处改写成 em 等非 px 单位）之后，剩下的仍是清一色的 768px，用例
 * 照样通过——也就是本任务要修的「零断点也假绿」。只有精确计数才能让「响应式覆盖被
 * 静默移除」变成红灯。新增视图带来第 6 处断点时，须同步更新本常量、tokens.css 里
 * 「断点字面量目前共七处」的说明与 README 的五个视图清单，这是刻意保留的人工闸门。
 */
const EXPECTED_MEDIA_BREAKPOINTS = 5

/** 唯一允许的断点字面量：与 MOBILE_BREAKPOINT 拼接出的规范写法 */
const CANONICAL_BREAKPOINT_LITERAL = `${MOBILE_BREAKPOINT}px`

/** 递归收集 src 下所有 .vue / .css 文件（返回相对 cwd 的可读路径，便于报错定位） */
function collectStyleFiles(dir) {
  const entries = readdirSync(dir, { withFileTypes: true, recursive: true })
  return entries
    .filter((e) => e.isFile() && /\.(vue|css)$/.test(e.name))
    .map((e) => join(e.parentPath ?? e.path, e.name).split(sep).join('/'))
}

/**
 * 媒体查询里的宽度字面量。旧写法 `/max-width:\s*(\d+)px/g` 有可证明的逃逸，此处逐条堵住：
 *
 * 1. `max-width : 768px`（冒号前有空格）——`\s*:\s*` 容忍冒号两侧空白；
 * 2. `768PX`（单位大小写）——整条正则加 `i` 标志，`PX`/`Px` 同样命中；
 * 3. `768.0px`（小数）——值段用 `[\d.]+` 收全，再交给下面的规范化判定；
 * 4. `min-width`——`(min|max)` 两种都收，另有独立用例禁止 min-width；
 * 5. 非 px 单位（em/rem 等）——正则不匹配，但会让精确计数变化，由计数断言兜住。
 *
 * 规范化规则：字面量必须逐字等于 `${MOBILE_BREAKPOINT}px`（`768 px` 这种数字与单位
 * 之间带空格的写法也不算）。tokens.css 的说明要求「改断点须在同一提交内同步七处」，
 * 而这一步靠人肉 grep `768px`；`768.0px` / `768PX` / `768 px` 语义等价却会让 grep
 * 漏掉某一处，正是本用例要防的漂移，故一律判失败，而不是「数值相等就放行」。
 */
const WIDTH_LITERAL_RE = /(min|max)-width\s*:\s*([\d.]+\s*px)/gi

function isCanonicalBreakpoint(literal) {
  return literal === CANONICAL_BREAKPOINT_LITERAL
}

/** 解析媒体查询头，返回 [{ kind: 'min'|'max', literal, value }] */
function parseWidthLiterals(mediaHeader) {
  return [...mediaHeader.matchAll(WIDTH_LITERAL_RE)].map((m) => ({
    kind: m[1].toLowerCase(),
    literal: m[2],
    value: Number.parseFloat(m[2])
  }))
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

/** 返回 src 下所有媒体查询宽度字面量的 { file, kind, literal, value } */
function collectMediaBreakpoints() {
  const found = []
  for (const file of collectStyleFiles(SRC_ROOT)) {
    const source = stripComments(readFileSync(file, 'utf8'))
    // 只关心媒体查询内的断点；普通声明的容器宽度（如 `.page { max-width: 1100px }`）
    // 与断点无关，不参与比较
    for (const block of source.matchAll(/@media[^{]*\{/g)) {
      for (const lit of parseWidthLiterals(block[0])) found.push({ file, ...lit })
    }
  }
  return found
}

/** 报错前缀：文件 + 查询形态，便于直接定位 */
function where(b) {
  return `${b.file}: @media (${b.kind}-width: ${b.literal})`
}

describe('断点一致性', () => {
  // 本用例刻意扫描整个 src，而不是只扫当前已知的几处：断点字面量目前散落在
  // tokens.css 的 --jf-breakpoint-mobile、useResponsive.js 的 MOBILE_BREAKPOINT
  // 以及 HomeView / PostDetailView / LoginView / RegisterView / NotificationsView
  // 五个视图里的 @media (max-width: 768px) 块，靠人肉同步必然漂移。
  it('媒体查询断点字面量的数量仍为 5（删光 @media 块也会红，杜绝空转假绿）', () => {
    const found = collectMediaBreakpoints()
    expect(
      found.length,
      `期望 src 下恰有 ${EXPECTED_MEDIA_BREAKPOINTS} 处媒体查询宽度断点，实际找到 ${found.length} 处：` +
        (found.map(where).join('；') || '（一处都没有）')
    ).toBe(EXPECTED_MEDIA_BREAKPOINTS)
  })

  it('每个断点字面量都逐字写成 768px（小数、大写单位、带空格写法一律判失败）', () => {
    const offenders = collectMediaBreakpoints()
      .filter((b) => !isCanonicalBreakpoint(b.literal))
      .map(
        (b) =>
          `${where(b)} 不是规范写法，必须逐字为 ${CANONICAL_BREAKPOINT_LITERAL}` +
          `（768.0px / 768PX / 768 px 都会让 grep 同步漏项）`
      )
    expect(offenders).toEqual([])
  })

  it('所有断点的数值都与 MOBILE_BREAKPOINT 一致', () => {
    const offenders = collectMediaBreakpoints()
      .filter((b) => b.value !== MOBILE_BREAKPOINT)
      .map(
        (b) =>
          `${where(b)} 的断点为 ${b.value}px，与 MOBILE_BREAKPOINT=${MOBILE_BREAKPOINT} 不一致`
      )
    expect(offenders).toEqual([])
  })

  it('src 下不存在 min-width 媒体查询（本项目只用 max-width 单一断点轴）', () => {
    // 规则选择：五个断点块全是 max-width（桌面样式 + 窄屏覆盖），MOBILE_BREAKPOINT
    // 是唯一基准。min-width 会引入第二条轴和第二个基准值（其自然对应物是
    // MOBILE_BREAKPOINT + 1 = 769px），靠人肉同步必然漂移，故本轮直接禁止：
    // 将来确实需要时，应连同本用例一起显式扩展，而不是悄悄多出一个 min-width 块。
    const offenders = collectMediaBreakpoints()
      .filter((b) => b.kind === 'min')
      .map((b) => `${where(b)} 使用了 min-width；本项目只用 max-width 断点`)
    expect(offenders).toEqual([])
  })

  it('tokens.css 的 --jf-breakpoint-mobile 与 MOBILE_BREAKPOINT 一致', () => {
    const css = readFileSync(resolve(SRC_ROOT, 'styles/tokens.css'), 'utf8')
    const m = css.match(/--jf-breakpoint-mobile\s*:\s*([\d.]+\s*px)/)
    expect(m, '未在 tokens.css 中找到 --jf-breakpoint-mobile').not.toBeNull()
    expect(
      isCanonicalBreakpoint(m[1]),
      `--jf-breakpoint-mobile 应逐字为 ${CANONICAL_BREAKPOINT_LITERAL}，实际为 ${m[1]}`
    ).toBe(true)
    expect(Number.parseFloat(m[1])).toBe(MOBILE_BREAKPOINT)
  })

  it('收集到的样式文件包含 styles 与 views 下的已知文件（防止扫描路径写错而假绿）', () => {
    const files = collectStyleFiles(SRC_ROOT)
    expect(files.some((f) => f.endsWith('src/styles/tokens.css'))).toBe(true)
    expect(files.some((f) => f.endsWith('src/views/PostDetailView.vue'))).toBe(true)
    // .js 不在扫描范围内（用例文件自身含有断点字面量字符串，扫进来会自我干扰）
    expect(files.some((f) => f.endsWith('src/composables/useResponsive.js'))).toBe(false)
  })

  // 上一用例已用真实输入覆盖扫描路径，此处仍对解析逻辑喂入固定样本
  // （命中四种写法、不误报容器宽度与注释），以免扫描逻辑自身写错时靠「碰巧」通过。
  it('解析逻辑能识别四种断点写法、且不误报容器宽度与注释', () => {
    const sample = `
      /* 说明：@media (max-width: 768px) { 示例 } 不应被计入 */
      .page { max-width: 1100px; }
      @media (max-width: 768px) { .a { display: none; } }
      @media (max-width: 768.0px) { .b { display: none; } }
      @media (MAX-WIDTH : 768PX) { .c { display: none; } }
      @media (min-width: 769px) { .d { display: none; } }
    `
    const found = [...stripComments(sample).matchAll(/@media[^{]*\{/g)].flatMap((b) =>
      parseWidthLiterals(b[0])
    )

    // 四种写法都被「看见」：旧正则 /max-width:\s*(\d+)px/g 会漏掉后三种
    expect(found.map((l) => l.literal)).toEqual(['768px', '768.0px', '768PX', '769px'])
    expect(found.map((l) => l.kind)).toEqual(['max', 'max', 'max', 'min'])
    // 数值化后 768.0px / 768PX 与 768px 等值，但只有逐字写成的 768px 通过规范化断言
    expect(found.filter((l) => l.value === MOBILE_BREAKPOINT)).toHaveLength(3)
    expect(found.filter((l) => isCanonicalBreakpoint(l.literal)).map((l) => l.value)).toEqual([768])
    // 容器宽度 1100px 在 @media 头之外，注释里的示例也已被剥离，均未计入
    expect(found).toHaveLength(4)
  })
})

// 扫描范围的边界（为什么只扫 frontend/src 是完整的）：
// - frontend/src 下没有 .scss / .less / .sass（node_modules 内的 Element Plus 源码不算）；
// - frontend/index.html 只引 favicon 与 /src/main.js，没有 <link rel="stylesheet">；
// - frontend/src/assets/vite.svg 内联了一个 @media，但那是 prefers-color-scheme 的
//   颜色查询、不含宽度断点，且 .svg 不在扫描范围。
// 若将来引入外部样式表、在 index.html 里内联 @media，或新增预处理器样式，需在这里
// 显式扩大扫描范围并同步计数。
