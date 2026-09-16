// 端到端复现 App 里的流程（只用 TGT，模拟 App 冷启动恢复会话）：
//   importSession(TGT, 无 Cookie) → renewSession → fetchWeeks → fetchWeekCourses
// 用法: node tools/verify_app_flow.mjs [周次...]
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs'

const CAS = 'https://cas.gzus.edu.cn'
const JWXT = 'https://jwxt.gzus.edu.cn'
const GNMKDM_ZC = 'N2154'
const SERVICE = `${JWXT}/sso/lyiotlogin`
const UA = 'Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36'
const LOGIN_PAGE_MARK = 'login_slogin'

mkdirSync('build/cas', { recursive: true })
const tgt = readFileSync('build/cas/tgt.txt', 'utf8').trim()

const jar = new Map()
const cookieHeader = () => [...jar.entries()].map(([k, v]) => `${k}=${v}`).join('; ')
const ok = (b) => b ? '✅' : '❌'

async function req(url, init = {}) {
  const headers = { 'User-Agent': UA, 'Accept-Language': 'zh-CN,zh;q=0.9', ...(init.headers ?? {}) }
  if (jar.size) headers.Cookie = cookieHeader()
  const res = await fetch(url, { ...init, headers, redirect: 'manual' })
  for (const sc of res.headers.getSetCookie()) {
    const [pair] = sc.split(';'); const i = pair.indexOf('=')
    const n = pair.slice(0, i).trim(), v = pair.slice(i + 1).trim()
    if (!v || /expires=Thu, 01 Jan 1970/i.test(sc)) jar.delete(n); else jar.set(n, v)
  }
  return res
}

/**
 * 手动跟 302 —— **必须自己跟**，不能交给 fetch 的 redirect:'follow'：
 * 那种模式下中间那几跳的 Set-Cookie 既拿不到也不会带下去，
 * 而正方会话（JSESSIONID）恰恰是在第 2 跳种下的。
 * App 这边是 OkHttp 的 CookieJar 在自动做这件事，两者行为要对齐。
 */
async function reqFollow(url, init = {}, maxHops = 10) {
  let current = url
  let method = init.method ?? 'GET'
  let body = init.body
  let headers = init.headers
  const chain = []
  for (let hop = 0; hop <= maxHops; hop++) {
    const res = await req(current, { ...init, method, body, headers })
    chain.push(`${res.status} ${new URL(current).pathname}`)
    if (res.status < 300 || res.status >= 400) {
      const text = await res.text()
      return { res, url: current, text, chain }
    }
    const loc = res.headers.get('location')
    if (!loc) return { res, url: current, text: await res.text(), chain }
    current = new URL(loc, current).toString()
    // 302 之后按浏览器/OkHttp 的规矩退化成 GET，且不再带原来的表单体
    method = 'GET'
    body = undefined
    headers = undefined
  }
  return { res: null, url: current, text: '', chain }
}

// ── GzusApi.renewSession()：POST /v1/tickets/<TGT> → ST → sso/lyiotlogin ──
async function renewSession() {
  const res = await req(`${CAS}/lyuapServer/v1/tickets/${encodeURIComponent(tgt)}`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8',
      loginToken: 'loginToken',
      'X-Requested-With': 'XMLHttpRequest',
      Origin: CAS,
      Referer: `${CAS}/lyuapServer/login?service=${encodeURIComponent(SERVICE)}`,
    },
    body: `service=${encodeURIComponent(SERVICE)}&loginToken=loginToken`,
  })
  const body = (await res.text()).trim()
  const ticket = body.startsWith('ST-') ? body.split('\n')[0].trim() : (JSON.parse(body).ticket ?? '')
  if (!ticket.startsWith('ST-')) return { ok: false, why: `换票失败：${body.slice(0, 200)}` }

  const jumped = await reqFollow(`${JWXT}/sso/lyiotlogin?ticket=${encodeURIComponent(ticket)}`)
  const alive = !jumped.url.includes(LOGIN_PAGE_MARK) && !jumped.text.includes(LOGIN_PAGE_MARK)
  return { ok: alive, ticket, finalUrl: jumped.url, chain: jumped.chain, why: alive ? '' : '最终落在登录页' }
}

// ── GzusScheduleParser.parseWeeks()：照抄 Kotlin 里的正则 ──
const optionRegex = /<option\b([^>]*)>([\s\S]*?)<\/option>/gi
const attrRegex = /([A-Za-z_:][-A-Za-z0-9_:.]*)\s*=\s*(?:"([^"]*)"|'([^']*)')/g
const dateRegex = /\d{4}-\d{2}-\d{2}/g

function parseAttributes(tag) {
  const out = {}
  for (const m of tag.matchAll(attrRegex)) out[m[1].toLowerCase()] = m[2] !== '' || m[3] === '' ? m[2] : m[3]
  return out
}
function stripTags(s) { return s.replace(/<[^>]*>/g, ' ').replace(/\s+/g, ' ').trim() }
function selectOptions(html, id) {
  const sel = new RegExp(`<select\\b[^>]*\\bid\\s*=\\s*["']${id}["'][^>]*>([\\s\\S]*?)</select>`, 'i').exec(html)
  if (!sel) return []
  return [...sel[1].matchAll(optionRegex)].map(m => [parseAttributes(m[1]).value ?? '', stripTags(m[2])])
}
function selectedOption(html, id) {
  const sel = new RegExp(`<select\\b[^>]*\\bid\\s*=\\s*["']${id}["'][^>]*>([\\s\\S]*?)</select>`, 'i').exec(html)
  if (!sel) return null
  const hit = [...sel[1].matchAll(optionRegex)].find(m => 'selected' in parseAttributes(m[1]))
  return hit ? (parseAttributes(hit[1]).value ?? null) : null
}
function parseWeeks(html) {
  return selectOptions(html, 'zs').map(([value, label]) => {
    const index = Number.parseInt(value.trim(), 10)
    if (!Number.isFinite(index) || index <= 0) return null
    const dates = [...label.matchAll(dateRegex)].slice(0, 2).map(m => m[0])
    if (dates.length < 2) return null
    return { index, start: dates[0], end: dates[1] }
  }).filter(Boolean).sort((a, b) => a.index - b.index)
}

// ── GzusApi.fetchWeeks() ──
async function fetchWeeks() {
  const page = await reqFollow(`${JWXT}/jwglxt/kbcx/xskbcxZccx_cxXskbcxIndex.html?gnmkdm=${GNMKDM_ZC}&layout=default`)
  const html = page.text
  if (html.includes(LOGIN_PAGE_MARK)) return { ok: false, why: '页面是登录页（会话失效）' }
  const term = { xnm: selectedOption(html, 'xnm'), xqm: selectedOption(html, 'xqm') }
  const current = selectedOption(html, 'zs')
  return { ok: true, status: page.res.status, bytes: html.length, term, currentWeek: current, weeks: parseWeeks(html) }
}

// ── GzusApi.fetchWeekCourses() ──
async function fetchWeekCourses(term, week) {
  const res = await req(`${JWXT}/jwglxt/kbcx/xskbcxMobile_cxXsKb.html?gnmkdm=${GNMKDM_ZC}`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8',
      'X-Requested-With': 'XMLHttpRequest',
      Origin: JWXT,
      Referer: `${JWXT}/jwglxt/kbcx/xskbcxZccx_cxXskbcxIndex.html?gnmkdm=${GNMKDM_ZC}&layout=default`,
    },
    body: new URLSearchParams({ xnm: term.xnm, xqm: term.xqm, zs: String(week), kblx: '1', doType: 'app', xh: '' }).toString(),
  })
  const body = await res.text()
  if (body.includes(LOGIN_PAGE_MARK)) return { ok: false, why: '登录页（会话失效）' }
  const j = JSON.parse(body)
  return {
    ok: true, status: res.status, echoWeek: j.zs, student: j.xsxx?.XM, className: j.xsxx?.BJMC,
    dates: Object.fromEntries((j.rqazcList ?? []).map(r => [r.xqjmc, r.rq])),
    courses: (j.kbList ?? []).map(k => `周${k.xqj} ${k.jcs} ${k.kcmc} ${k.lh ?? ''}${k.cdmc ?? ''} ${k.xm} [${k.zcd}]`),
  }
}

// ─────────────────────────────────────────────────────────────────────────
const weeksToCheck = process.argv.slice(2).map(Number).filter(Boolean)
console.log(`【0】会话来源：只有 TGT（jar 里 ${jar.size} 条 Cookie）`)
console.log(`     ${tgt}`)

const renewed = await renewSession()
console.log(`【1】renewSession()  ${ok(renewed.ok)}  ${renewed.why || renewed.finalUrl}`)
console.log(`     跳转链：${(renewed.chain ?? []).join(' → ')}`)
console.log(`     CookieJar 现在有：${[...jar.keys()].join(', ') || '(空)'}`)
if (!renewed.ok) process.exit(1)

const wk = await fetchWeeks()
console.log(`【2】fetchWeeks()    ${ok(wk.ok)}  HTTP ${wk.status} ${wk.bytes}B  学年学期=${wk.term.xnm}/${wk.term.xqm}  当前周=${wk.currentWeek}`)
if (!wk.ok) process.exit(1)
console.log(`     教学周 ${wk.weeks.length} 个：`)
for (const w of wk.weeks) console.log(`       ${String(w.index).padStart(2)}  ${w.start} ~ ${w.end}${String(w.index) === wk.currentWeek ? '   ← 服务端标为当前周' : ''}`)

const today = new Date().toISOString().slice(0, 10)
const hit = wk.weeks.find(w => today >= w.start && today <= w.end)
console.log(`     今天 ${today} 落在：${hit ? '第 ' + hit.index + ' 周' : '不在任何教学周里（假期）'}`)

const targets = weeksToCheck.length ? weeksToCheck : [1, Number(wk.currentWeek), wk.weeks.length]
for (const week of targets) {
  const r = await fetchWeekCourses(wk.term, week)
  console.log(`【3】第 ${week} 周  ${ok(r.ok)}  课程数=${r.courses.length}  回显 zs=${r.echoWeek}  日期：${JSON.stringify(r.dates)}`)
  r.courses.forEach(c => console.log(`       ${c}`))
}

writeFileSync('build/cas/verify_result.json', JSON.stringify({ weeks: wk.weeks, term: wk.term, currentWeek: wk.currentWeek }, null, 1))
