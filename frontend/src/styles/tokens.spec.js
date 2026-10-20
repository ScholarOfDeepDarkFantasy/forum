import { describe, it, expect } from 'vitest'
import { readFileSync, readdirSync, statSync } from 'node:fs'
import { join, resolve } from 'node:path'

const SRC_DIR = resolve(process.cwd(), 'src')
const TOKENS_FILE = join(SRC_DIR, 'styles', 'tokens.css')

const EXPECTED_COLOR_TOKENS = [
  '--jf-color-primary',
  '--jf-color-text-1',
  '--jf-color-text-3',
  '--jf-color-border',
  '--jf-color-bg-page',
  '--jf-color-bg-card',
  '--jf-color-bg-hover',
  '--jf-color-code-bg',
  '--jf-color-text-weak',
  '--jf-color-unread-bg',
  '--jf-color-anchor-bg'
]

function blockOf(css, selector) {
  const start = css.indexOf(`${selector} {`)
  if (start === -1) return ''
  const from = css.indexOf('{', start)
  const to = css.indexOf('}', from)
  return css.slice(from + 1, to)
}

function colorTokens(block) {
  const tokens = {}
  for (const line of block.split('\n')) {
    const match = line.match(/^\s*(--jf-color-[\w-]+)\s*:\s*([^;]+);/)
    if (match) tokens[match[1]] = match[2].trim()
  }
  return tokens
}

function walk(dir) {
  const files = []
  for (const name of readdirSync(dir)) {
    const full = join(dir, name)
    if (statSync(full).isDirectory()) files.push(...walk(full))
    else if (/\.(vue|css|js)$/.test(name)) files.push(full)
  }
  return files
}

const css = readFileSync(TOKENS_FILE, 'utf8')
const lightTokens = colorTokens(blockOf(css, ':root'))
const darkTokens = colorTokens(blockOf(css, 'html.dark'))

describe('颜色令牌', () => {
  it(':root 与 html.dark 的 --jf-color-* 集合完全一致', () => {
    expect(Object.keys(darkTokens).sort()).toEqual(Object.keys(lightTokens).sort())
  })

  it('令牌全集与设计文档一致（11 个）', () => {
    expect(Object.keys(lightTokens).sort()).toEqual([...EXPECTED_COLOR_TOKENS].sort())
  })

  it('每个颜色令牌都是 6 位小写十六进制', () => {
    for (const [name, value] of Object.entries({ ...lightTokens, ...darkTokens })) {
      expect(value, `${name} 的取值格式`).toMatch(/^#[0-9a-f]{6}$/)
    }
  })

  it('每个颜色令牌在 src 下至少有一处 var() 消费', () => {
    const consumers = walk(SRC_DIR)
      .filter((file) => file !== TOKENS_FILE && !file.endsWith('.spec.js'))
      .map((file) => readFileSync(file, 'utf8'))
      .join('\n')
    for (const name of EXPECTED_COLOR_TOKENS) {
      expect(consumers.includes(`var(${name})`), `${name} 缺少消费方`).toBe(true)
    }
  })

  it('页面底/卡片底在亮暗两套中不同，主色保持同值', () => {
    expect(lightTokens['--jf-color-bg-page']).not.toBe(darkTokens['--jf-color-bg-page'])
    expect(lightTokens['--jf-color-bg-card']).not.toBe(darkTokens['--jf-color-bg-card'])
    expect(darkTokens['--jf-color-primary']).toBe(lightTokens['--jf-color-primary'])
  })
})

const MAIN_FILE = join(SRC_DIR, 'main.js')
const INDEX_HTML = resolve(process.cwd(), 'index.html')

describe('main.js 样式导入顺序', () => {
  const source = readFileSync(MAIN_FILE, 'utf8')
  const order = [
    'element-plus/dist/index.css',
    'element-plus/theme-chalk/dark/css-vars.css',
    './styles/tokens.css',
    './styles/common.css',
    './styles/markdown.css',
    'highlight.js/styles/github.css',
    './styles/highlight-dark.css',
    './style.css'
  ]

  it('8 个样式导入按设计顺序出现', () => {
    const positions = order.map((path) => source.indexOf(path))
    for (const [index, position] of positions.entries()) {
      expect(position, `${order[index]} 未导入`).toBeGreaterThan(-1)
    }
    expect(positions).toEqual([...positions].sort((a, b) => a - b))
  })
})

describe('index.html 防闪烁引导脚本', () => {
  const html = readFileSync(INDEX_HTML, 'utf8')

  it('在第一个模块脚本之前读取 jf_theme', () => {
    const inline = html.indexOf("localStorage.getItem('jf_theme')")
    const moduleScript = html.indexOf('type="module"')
    expect(inline).toBeGreaterThan(-1)
    expect(moduleScript).toBeGreaterThan(-1)
    expect(inline).toBeLessThan(moduleScript)
  })

  it('暗色时添加 dark 类并设置 colorScheme', () => {
    expect(html).toContain("classList.add('dark')")
    expect(html).toContain('style.colorScheme')
  })
})

describe('深色模式字面量清理', () => {
  const files = {
    'styles/markdown.css': join(SRC_DIR, 'styles', 'markdown.css'),
    'views/PostDetailView.vue': join(SRC_DIR, 'views', 'PostDetailView.vue'),
    'views/NotificationsView.vue': join(SRC_DIR, 'views', 'NotificationsView.vue')
  }
  const banned = ['#f6f8fa', '#c0c4cc', '#f0f7ff', '#fff8e6']

  for (const [label, file] of Object.entries(files)) {
    it(`${label} 不再出现一次性语义色`, () => {
      const source = readFileSync(file, 'utf8')
      for (const hex of banned) {
        expect(source.includes(hex), `${label} 仍含 ${hex}`).toBe(false)
      }
    })
  }
})
