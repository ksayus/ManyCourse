// 从 uiautomator dump 出来的 UI XML 里抽出所有可见文本（+ 关键控件坐标）
// 用法: node tools/ui_text.mjs <ui.xml>
import { readFileSync } from 'node:fs'

const xml = readFileSync(process.argv[2], 'utf8')

const nodes = [...xml.matchAll(/<node\b([^>]*)\/?>/g)].map(m => {
  const attrs = {}
  for (const a of m[1].matchAll(/([\w-]+)="([^"]*)"/g)) attrs[a[1]] = a[2]
  return attrs
})

const texts = nodes.map(n => n.text).filter(t => t && t.trim())
console.log('--- 文本（去重，按出现顺序）---')
console.log([...new Set(texts)].join('  |  '))

console.log('\n--- 可点击控件（text / content-desc + bounds）---')
for (const n of nodes) {
  if (n.clickable !== 'true') continue
  const label = [n.text, n['content-desc']].filter(Boolean).join(' / ')
  if (!label) continue
  console.log(`  ${label}    ${n.bounds}`)
}
