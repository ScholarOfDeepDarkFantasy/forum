import MarkdownIt from 'markdown-it'
import DOMPurify from 'dompurify'
import hljs from 'highlight.js/lib/core'
import java from 'highlight.js/lib/languages/java'
import javascript from 'highlight.js/lib/languages/javascript'
import typescript from 'highlight.js/lib/languages/typescript'
import bash from 'highlight.js/lib/languages/bash'
import json from 'highlight.js/lib/languages/json'
import xml from 'highlight.js/lib/languages/xml'
import css from 'highlight.js/lib/languages/css'
import sql from 'highlight.js/lib/languages/sql'
import python from 'highlight.js/lib/languages/python'
import yaml from 'highlight.js/lib/languages/yaml'
import markdown from 'highlight.js/lib/languages/markdown'

// 只注册常用语言：highlight.js 全量入口会把所有语言打进包体
const LANGUAGES = {
  java,
  javascript,
  typescript,
  bash,
  json,
  xml,
  css,
  sql,
  python,
  yaml,
  markdown
}
Object.entries(LANGUAGES).forEach(([name, lang]) => hljs.registerLanguage(name, lang))

const md = new MarkdownIt({
  html: false, // 不解析原始 HTML，XSS 第一层防线
  linkify: true,
  breaks: true, // 历史纯文本帖子：单换行保持为 <br>，观感不退化
  highlight(code, lang) {
    if (lang && hljs.getLanguage(lang)) {
      try {
        return hljs.highlight(code, { language: lang, ignoreIllegals: true }).value
      } catch {
        /* 高亮失败按纯文本处理 */
      }
    }
    return ''
  }
})

// 链接协议白名单交给 DOMPurify 统一裁决（markdown-it 没有 afterValidateLink 之类的
// 钩子，只能在渲染后剥离 href/src）。markdown-it 默认拒绝 javascript: 的方式是把整段
// Markdown 语法原样当纯文本输出——「[点我](javascript:alert(1))」会连危险协议一起
// 显示在页面上。放开这里的校验后，链接会先渲染成 <a>，再由 DOMPurify 连同 href
// 一起剥离：链接文字保留、不可点击，危险协议不出现在成品 HTML 里。
md.validateLink = () => true

// 渲染后统一加安全属性（DOMPurify 默认会丢掉 target，必须在 afterSanitizeAttributes 里补）
DOMPurify.addHook('afterSanitizeAttributes', (node) => {
  if (node.tagName === 'A') {
    const href = node.getAttribute('href') || ''
    if (/^https?:\/\//i.test(href)) {
      node.setAttribute('target', '_blank')
      node.setAttribute('rel', 'noopener noreferrer')
    }
  } else if (node.tagName === 'IMG') {
    node.setAttribute('loading', 'lazy')
  }
})

/** 渲染失败时的兜底：按纯文本展示，避免整页白屏 */
export function escapeHtml(text) {
  return String(text)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}

/**
 * 帖子正文渲染入口：详情页与编辑页预览共用，保证「预览所见 = 详情所得」。
 * @param {string} source Markdown 源文本
 * @returns {string} 已消毒的 HTML
 */
export function renderMarkdown(source) {
  if (source == null || source === '') return ''
  try {
    return DOMPurify.sanitize(md.render(String(source)), { USE_PROFILES: { html: true } })
  } catch {
    return escapeHtml(String(source)).replace(/\n/g, '<br>')
  }
}
