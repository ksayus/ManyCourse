// HAR 摘要工具（开发期用，不参与打包）
// 用法: node tools/har_dump.mjs <har> [--grep <子串>] [--body <n>] [--host <子串>] [--method POST]
import { readFileSync } from 'node:fs'

const [, , file, ...rest] = process.argv
const opt = (name, def = null) => {
  const i = rest.indexOf(name)
  return i >= 0 ? rest[i + 1] : def
}
const has = (name) => rest.includes(name)

const har = JSON.parse(readFileSync(file, 'utf8'))
const entries = har.log.entries

const grep = opt('--grep')
const host = opt('--host')
const method = opt('--method')
const bodyLen = Number(opt('--body', 0))
const listOnly = has('--list')

const cookieNames = new Set()
for (const e of entries) {
  for (const c of e.request.cookies ?? []) cookieNames.add(c.name + ' @' + new URL(e.request.url).host)
  for (const c of e.response.cookies ?? []) cookieNames.add(c.name + ' @' + new URL(e.request.url).host + ' (resp)')
}

let n = 0
entries.forEach((e, i) => {
  const url = e.request.url
  const u = new URL(url)
  if (host && !u.host.includes(host)) return
  if (method && e.request.method.toUpperCase() !== method.toUpperCase()) return
  const text = [
    url,
    e.request.method,
    e.response.content?.text ?? '',
    JSON.stringify(e.request.postData ?? {}),
  ].join('\n')
  if (grep && !text.includes(grep)) return
  n++
  const status = e.response.status
  const size = (e.response.content?.text ?? '').length
  console.log(`[${i}] ${e.request.method} ${status} ${size}B ${u.host}${u.pathname}${u.search}`)
  if (listOnly) return
  const reqHeaders = Object.fromEntries((e.request.headers ?? []).map(h => [h.name, h.value]))
  const interesting = ['cookie', 'referer', 'content-type', 'origin', 'x-requested-with', 'user-agent', 'loginusertoken', 'logintoken', 'accept']
  for (const k of interesting) {
    const v = Object.entries(reqHeaders).find(([hk]) => hk.toLowerCase() === k)?.[1]
    if (v) console.log(`    ▸ ${k}: ${v}`)
  }
  if (e.request.postData?.text) console.log(`    ▸ body: ${e.request.postData.text.slice(0, 800)}`)
  const respHeaders = Object.fromEntries((e.response.headers ?? []).map(h => [h.name, h.value]))
  for (const k of ['set-cookie', 'location', 'content-type', 'date']) {
    const v = Object.entries(respHeaders).find(([hk]) => hk.toLowerCase() === k)?.[1]
    if (v) console.log(`    ◂ ${k}: ${v}`)
  }
  if (bodyLen > 0) console.log(`    ◂ body: ${(e.response.content?.text ?? '').slice(0, bodyLen).replace(/\s+/g, ' ')}`)
})

console.log(`\n--- ${n} / ${entries.length} entries ---`)
console.log('cookies seen:')
for (const c of [...cookieNames].sort()) console.log('  ' + c)
