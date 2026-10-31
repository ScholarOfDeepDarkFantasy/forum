// @vitest-environment node
//
// 第十一轮：CSP 与安全响应头的守护测试。
// 这些断言只读仓库文件，不启动浏览器：
// - 首页唯一的内联主题引导脚本必须被 CSP 的 SHA-256 精确放行（改脚本忘了改哈希时立刻红）；
// - 公网（public.conf）与内网（forum.conf）的 CSP 必须逐字一致，避免两个入口分叉；
// - uploads location 必须带全套安全头，且 deploy.sh 的部署断言不能被悄悄删掉。
import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { createHash } from 'node:crypto'
import { resolve } from 'node:path'

const ROOT = resolve(process.cwd(), '..')
const FORUM_CONF = resolve(ROOT, 'deploy/nginx/forum.conf')
const PUBLIC_CONF = resolve(ROOT, 'deploy/nginx/public.conf')
const DEPLOY_SH = resolve(ROOT, 'deploy/deploy.sh')
const INDEX_HTML = resolve(process.cwd(), 'index.html')

const CSP_RE = /add_header\s+Content-Security-Policy\s+"([^"]+)"\s+always;/g

function cspValues(file) {
  const source = readFileSync(file, 'utf8')
  return [...source.matchAll(CSP_RE)].map((match) => match[1])
}

// 从 conf 文本里截出某个 location 的完整块：先定位名字，再按花括号配对到匹配的 }
// （不能用「第一个 }」，location 里嵌套 if/limit_except 时会截错）。
function locationBlock(source, marker) {
  const start = source.indexOf(marker)
  if (start < 0) {
    return null
  }
  const open = source.indexOf('{', start)
  let depth = 0
  for (let i = open; i < source.length; i += 1) {
    if (source[i] === '{') {
      depth += 1
    } else if (source[i] === '}') {
      depth -= 1
      if (depth === 0) {
        return source.slice(start, i + 1)
      }
    }
  }
  return null
}

function inlineScriptHash() {
  const html = readFileSync(INDEX_HTML, 'utf8')
  const scripts = [...html.matchAll(/<script(?![^>]*\bsrc=)[^>]*>([\s\S]*?)<\/script>/g)]
  expect(scripts).toHaveLength(1)
  return `sha256-${createHash('sha256').update(scripts[0][1]).digest('base64')}`
}

describe('CSP 与内联脚本哈希', () => {
  it('index.html 内联脚本的 sha256 出现在 forum.conf 的 CSP 中', () => {
    const hash = inlineScriptHash()
    const [csp] = cspValues(FORUM_CONF)
    expect(csp, 'forum.conf 缺少 CSP').toBeTruthy()
    expect(csp).toContain(`script-src 'self' '${hash}'`)
  })

  it('CSP 落在 index.html 的精确 location 内（否则会被该 location 的 add_header 屏蔽）', () => {
    const conf = readFileSync(FORUM_CONF, 'utf8')
    const block = locationBlock(conf, 'location = /forum/index.html')
    expect(block, 'forum.conf 缺少 index.html location').toBeTruthy()
    expect(block, 'CSP 必须写在 index.html location 里').toContain('Content-Security-Policy')
    // 全文件只允许这一条 CSP：多写一条到 server 级/其它 location 说明下发点又漂了
    expect(conf.match(CSP_RE) || []).toHaveLength(1)
  })

  it('CSP 必备指令齐全且 script-src 没有 unsafe-* / 通配符', () => {
    const [csp] = cspValues(FORUM_CONF)
    expect(csp, 'forum.conf 缺少 CSP').toBeTruthy()
    for (const directive of [
      "default-src 'self'",
      "base-uri 'self'",
      "object-src 'none'",
      "style-src 'self' 'unsafe-inline'",
      "img-src 'self' data: https:",
      "font-src 'self' data:",
      "connect-src 'self'",
      "form-action 'self'",
      "frame-ancestors 'self'"
    ]) {
      expect(csp, `缺少指令 ${directive}`).toContain(directive)
    }
    const scriptSrc = csp
      .split(';')
      .map((part) => part.trim())
      .find((part) => part.startsWith('script-src'))
    expect(scriptSrc).toBeTruthy()
    expect(scriptSrc).not.toContain('unsafe-inline')
    expect(scriptSrc).not.toContain('unsafe-eval')
    expect(scriptSrc).not.toMatch(/\*/)
  })

  it('CSP 放行 Cloudflare Web Analytics 信标（否则每页都会报 script 被拦截）', () => {
    const [csp] = cspValues(FORUM_CONF)
    expect(csp, 'forum.conf 缺少 CSP').toBeTruthy()
    const directives = csp.split(';').map((part) => part.trim())
    const scriptSrc = directives.find((part) => part.startsWith('script-src'))
    const connectSrc = directives.find((part) => part.startsWith('connect-src'))
    // CF 免费版默认给 HTML 注入 beacon.min.js；不放行会在控制台每页报 CSP 违规
    expect(scriptSrc).toContain('https://static.cloudflareinsights.com')
    expect(connectSrc).toContain('https://cloudflareinsights.com')
  })

  it('forum.conf 与 public.conf 的 CSP 逐字相同', () => {
    const [forumCsp] = cspValues(FORUM_CONF)
    const [publicCsp] = cspValues(PUBLIC_CONF)
    expect(forumCsp, 'forum.conf 缺少 CSP').toBeTruthy()
    expect(publicCsp, 'public.conf 缺少 CSP').toBeTruthy()
    expect(publicCsp).toBe(forumCsp)
  })

  it('uploads location 带 4 个安全头且都是 always', () => {
    const conf = readFileSync(FORUM_CONF, 'utf8')
    const uploadsBlock = locationBlock(conf, 'location ^~ /forum/uploads/')
    expect(uploadsBlock, 'forum.conf 缺少 uploads location').toBeTruthy()
    for (const header of [
      'add_header X-Content-Type-Options "nosniff" always;',
      'add_header X-Frame-Options "SAMEORIGIN" always;',
      'add_header Referrer-Policy "strict-origin-when-cross-origin" always;',
      'add_header Strict-Transport-Security "max-age=31536000" always;'
    ]) {
      expect(uploadsBlock, `uploads 缺少 ${header}`).toContain(header)
    }
    // 图片响应不需要 CSP；写在这里只会让人误以为 HTML 也受它保护
    expect(uploadsBlock).not.toContain('Content-Security-Policy')
  })

  it('deploy.sh 含 CSP 与 uploads 头断言（按唯一串断言，避免恒真）', () => {
    const script = readFileSync(DEPLOY_SH, 'utf8')
    expect(script).toContain('content-security-policy')
    // 哈希必须现算并与响应头比对（只查前缀的写法抓不到「改脚本忘改哈希」）
    expect(script).toContain("grep -io 'sha256-")
    expect(script).toContain('openssl dgst -sha256')
    expect(script).toContain('expected_script_src=')
    expect(script).toContain('expected_connect_src=')
    // 这行 die 文案只出现在 uploads 断言循环里，是「断言还在」的唯一可靠证据
    expect(script).toContain('uploads location 缺少安全响应头')
    expect(script).toContain('for expected_header in')
  })
})
