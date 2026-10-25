#!/usr/bin/env node
/**
 * 构建产物预算检查（第九轮）。
 *
 * 用法：
 *   node scripts/check-bundle.mjs                                   # 检查 ./dist
 *   node scripts/check-bundle.mjs --dist E:/tmp/forum-dist-baseline  # 检查历史产物（自证 RED）
 *
 * 预算（单位 B；Vite 8 的报告口径是 bytes/1000，84431 B 显示为 84.43 kB）：
 *   入口 JS       <= 150,000 B   首屏必须下载的应用代码 + 首屏用到的 Element Plus 组件（实测 84,431）
 *   入口 CSS      <=  70,000 B   同上，按需引入后的入口样式（实测 39,019）
 *   任一 JS chunk <= 300,000 B   防止单个懒加载块重新膨胀（实测 233,744）
 *   CSS 总量      <= 260,000 B   按需样式拆成多个小 css，总量同样要设上限（实测 181,570）
 *   JS 总量       <= 950,000 B   回归护栏（不是优化目标）：防止「拆成很多小 chunk 但总量暴涨」（实测 835,623）
 *
 * 另有 CSS 覆盖断言：合并 dist/assets/*.css 后必须包含 9 个关键组件选择器。
 * 体积绿了但按需样式没装上时，组件会「裸样式」，体积检查看不出来，所以单独断言。
 *
 * 阈值定稿规则（计划 §0.5 裁决点 8）：
 *   以 Task 1 实测值为准向上取整到整十 kB 并留余量；每项都必须显著低于旧基线
 *   （入口 JS 878,120 / 入口 CSS 369,967 / CSS 总量 381,751 / JS 总量 1,344,673）。
 *   改常量时把「实测 → 预算」的算式写在常量行尾注释里。
 */
import { readdir, readFile, stat } from 'node:fs/promises'
import { join, resolve } from 'node:path'

const KB = 1000

// 第九轮定稿值（实测见 docs/DEVELOPMENT.md §4.14 的体积对比表）
const BUDGETS = {
  entryJs: 150 * KB, // 实测 84,431 B；150,000 = 实测的 1.78×，吸收首屏组件增长
  entryCss: 70 * KB, // 实测 39,019 B；70,000 = 实测的 1.79×
  chunkJs: 300 * KB, // 实测 233,744 B；300,000 低于 Vite 的 500 kB 告警线
  totalCss: 260 * KB, // 实测 181,570 B；260,000 = 实测的 1.43×
  totalJs: 950 * KB // 实测 835,623 B；仍比基线 1,344,673 B 低 394,673 B（-29.4%）
}

const LABELS = {
  entryJs: '入口 JS',
  entryCss: '入口 CSS',
  chunkJs: '最大 JS chunk',
  totalCss: 'CSS 总量',
  totalJs: 'JS 总量'
}

// 按需样式必须覆盖的关键组件：缺一个就说明「组件解析到了、样式没跟上」
const REQUIRED_SELECTORS = [
  '.el-button',
  '.el-dialog',
  '.el-table',
  '.el-message-box',
  '.el-notification',
  '.el-pagination',
  '.el-loading-mask',
  '.el-form',
  '.el-select'
]

function fail(message) {
  console.error(`[check-bundle] ${message}`)
  process.exit(1)
}

function parseDistArg(argv) {
  const at = argv.indexOf('--dist')
  if (at === -1) {
    return 'dist'
  }
  const value = argv[at + 1]
  if (!value || value.startsWith('--')) {
    fail('--dist 缺少目录参数')
  }
  return value
}

const formatKb = (bytes) => `${(bytes / KB).toFixed(2)} kB`

/** 中文按 2 列宽计算，表格才不会错位 */
function pad(text, width) {
  const visual = [...text].reduce((n, ch) => n + (ch.charCodeAt(0) > 0x2e80 ? 2 : 1), 0)
  return text + ' '.repeat(Math.max(0, width - visual))
}

async function loadAssets(dist) {
  const assetsDir = join(dist, 'assets')
  let names
  try {
    names = await readdir(assetsDir)
  } catch {
    fail(`找不到 ${assetsDir}：先在 frontend/ 跑 pnpm build（或用 --dist 指向别的产物目录）`)
  }
  const files = []
  for (const name of names) {
    const info = await stat(join(assetsDir, name))
    if (info.isFile()) {
      files.push({ name, size: info.size })
    }
  }
  return { assetsDir, files }
}

const dist = resolve(parseDistArg(process.argv.slice(2)))
const { assetsDir, files } = await loadAssets(dist)

const jsFiles = files.filter((f) => f.name.endsWith('.js'))
const cssFiles = files.filter((f) => f.name.endsWith('.css'))
const entryJs = jsFiles.filter((f) => /^index-.+\.js$/.test(f.name))
const entryCss = cssFiles.filter((f) => /^index-.+\.css$/.test(f.name))

const rows = []
const problems = []

function row(key, name, size, budget) {
  return { key, name, size, budget, ok: size <= budget }
}

// 入口必须恰好一个：Vite 改了命名规则时宁可红，也不要静默「什么都没检查」
if (entryJs.length !== 1) {
  problems.push(
    `入口 JS 应恰好 1 个（index-*.js），实际 ${entryJs.length} 个：${entryJs.map((f) => f.name).join(', ') || '无'}`
  )
} else {
  rows.push(row('entryJs', entryJs[0].name, entryJs[0].size, BUDGETS.entryJs))
}

if (entryCss.length !== 1) {
  problems.push(
    `入口 CSS 应恰好 1 个（index-*.css），实际 ${entryCss.length} 个：${entryCss.map((f) => f.name).join(', ') || '无'}`
  )
} else {
  rows.push(row('entryCss', entryCss[0].name, entryCss[0].size, BUDGETS.entryCss))
}

if (jsFiles.length === 0) {
  problems.push('dist/assets 里没有任何 .js 文件')
} else {
  const biggest = [...jsFiles].sort((a, b) => b.size - a.size)[0]
  rows.push(row('chunkJs', biggest.name, biggest.size, BUDGETS.chunkJs))
  const totalJs = jsFiles.reduce((sum, f) => sum + f.size, 0)
  rows.push(row('totalJs', `${jsFiles.length} 个 chunk`, totalJs, BUDGETS.totalJs))
}

let missingSelectors = REQUIRED_SELECTORS
if (cssFiles.length === 0) {
  problems.push('dist/assets 里没有任何 .css 文件')
} else {
  const totalCss = cssFiles.reduce((sum, f) => sum + f.size, 0)
  rows.push(row('totalCss', `${cssFiles.length} 个文件`, totalCss, BUDGETS.totalCss))

  // 合并全部 css 文本做覆盖断言：按需引入后样式被拆成很多文件，只看体积会漏掉「少装样式」
  let cssText = ''
  for (const f of cssFiles) {
    cssText += await readFile(join(assetsDir, f.name), 'utf8')
  }
  missingSelectors = REQUIRED_SELECTORS.filter((sel) => !cssText.includes(sel))
  if (missingSelectors.length > 0) {
    problems.push(`CSS 缺少关键选择器：${missingSelectors.join(', ')}（按需样式没装上？）`)
  }
}

console.log(`[check-bundle] dist = ${dist}`)
console.log(`${pad('项', 14)}${pad('文件', 40)}${pad('实测', 13)}${pad('预算', 13)}结果`)
for (const r of rows) {
  const verdict = r.ok ? '✓' : `✗ 超 ${formatKb(r.size - r.budget)}`
  console.log(
    `${pad(LABELS[r.key], 14)}${pad(r.name, 40)}${pad(formatKb(r.size), 13)}${pad(formatKb(r.budget), 13)}${verdict}`
  )
}
const covered = REQUIRED_SELECTORS.length - missingSelectors.length
console.log(
  `[check-bundle] CSS 覆盖 ${covered}/${REQUIRED_SELECTORS.length}` +
    (covered === REQUIRED_SELECTORS.length ? ' ✓' : `，缺：${missingSelectors.join(', ')}`)
)
for (const message of problems) {
  console.log(`✗ ${message}`)
}

const failed = rows.filter((r) => !r.ok).length + problems.length
if (failed > 0) {
  console.log(`✗ ${failed} 项超预算（退出码 1）`)
  process.exit(1)
}
console.log('✓ 全部在预算内（退出码 0）')
