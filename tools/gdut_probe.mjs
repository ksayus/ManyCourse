// 广工（广东工业大学）统一身份认证 + 教务系统联调探针（只在本地开发用；凭据走环境变量，不落盘）
//
//   node tools/gdut_probe.mjs page                  打开 CAS 登录页，打印隐藏域/脚本（不需要账号）
//   node tools/gdut_probe.mjs crypto <密码> [盐]     演示密码加密（不给盐就现抓一个）
//   node tools/gdut_probe.mjs login                 用 GDUT_USER / GDUT_PASS 登录并打印每一跳
//   node tools/gdut_probe.mjs kb [xnxqdm]           学期课表（xsAllKbList），默认 202601
//   node tools/gdut_probe.mjs weeks [xnxqdm]        每周课表 + 周次日期（getKbRq，第 1..22 周）
//
// 与 tools/cas_probe.mjs 的分工：那个打广软（CAS + 正方），这个打广工（authserver + jxfw）。
import { readFileSync, writeFileSync, existsSync, mkdirSync } from 'node:fs'
import { createCipheriv, randomBytes } from 'node:crypto'

const CAS = 'https://authserver.gdut.edu.cn'
const JXFW = 'https://jxfw.gdut.edu.cn'
const SERVICE = `${JXFW}/new/ssoLogin`
const LOGIN_URL = `${CAS}/authserver/login?service=${encodeURIComponent(SERVICE)}`
const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/152.0.0.0 Safari/537.36 Edg/152.0.0.0'

const STATE = 'build/gdut/state.json'
mkdirSync('build/gdut', { recursive: true })

// ── cookie jar（持久化，方便跨进程复现"只带 Cookie 也能查课表"）─────────────
let state = existsSync(STATE) ? JSON.parse(readFileSync(STATE, 'utf8')) : { cookies: {} }
const save = () => writeFileSync(STATE, JSON.stringify(state, null, 1))
const cookieHeader = () => Object.entries(state.cookies).map(([k, v]) => `${k}=${v}`).join('; ')

async function req(url, init = {}) {
  const headers = { 'User-Agent': UA, ...(init.headers ?? {}) }
  const ck = cookieHeader()
  if (ck && !headers.Cookie) headers.Cookie = ck
  const res = await fetch(url, { ...init, headers, redirect: init.redirect ?? 'manual' })
  const short = url.replace(CAS, '(cas)').replace(JXFW, '(jxfw)')
  console.log(`  → ${init.method ?? 'GET'} ${short} = ${res.status}${res.headers.get('location') ? ' → ' + res.headers.get('location') : ''}`)
  for (const sc of res.headers.getSetCookie()) {
    console.log('    ⇐ Set-Cookie:', sc.slice(0, 120))
    const [pair] = sc.split(';'); const i = pair.indexOf('=')
    const name = pair.slice(0, i).trim(), value = pair.slice(i + 1).trim()
    if (!value || /expires=Thu, 01 Jan 1970/i.test(sc)) delete state.cookies[name]
    else state.cookies[name] = value
  }
  save()
  return res
}

// ── 登录页隐藏域 ───────────────────────────────────────────────────────────
// 广工这版页面的盐 id 是 `pwdEncryptSalt`（不是新版金智的 `pwdDefaultEncryptSalt`），
// 两个都认，谁先出现用谁；抓不到就让调用方报错，别拿空盐去加密（那样只会得到"密码错误"）。
//
// ⚠️ 页面里**有四个表单**，`cllt` 各不一样（fidoLogin / dynamicLogin / userNameLogin / qrLogin），
// 顺序上第一个是 fidoLogin。账号密码登录**必须发 userNameLogin** ——
// 所以这里不按 id 取（`htmlInputValue` 那套取到的会是第一个 fidoLogin），直接写死。
function parseLoginPage(html) {
  const attr = (re) => html.match(re)?.[1]?.trim() ?? ''
  return {
    salt: attr(/<input[^>]*id="pwdEncryptSalt"[^>]*value="([^"]*)"/) ||
      attr(/<input[^>]*id="pwdDefaultEncryptSalt"[^>]*value="([^"]*)"/) ||
      attr(/pwdDefaultEncryptSalt\s*=\s*"([^"]*)"/),
    execution: attr(/<input[^>]*name="execution"[^>]*value="([^"]*)"/),
    lt: attr(/<input[^>]*name="lt"[^>]*value="([^"]*)"/),
    eventId: attr(/<input[^>]*name="_eventId"[^>]*value="([^"]*)"/) || 'submit',
    cllt: 'userNameLogin',
    dllt: 'generalLogin',
    hasCaptcha: /name="captcha"/.test(html),
    scripts: [...html.matchAll(/<script[^>]*src="([^"]+)"/g)].map((m) => m[1]),
  }
}

// ── 密码加密（金智 authserver 家族通用算法）────────────────────────────────
// 明文 = randomString(64) + 密码，AES-128-CBC/PKCS7，key = 盐，iv = randomString(16)，输出 base64。
const AES_CHARS = 'ABCDEFGHJKMNPQRSTWXYZabcdefhijkmnprstwxyz2345678'
const randomString = (n) => Array.from(randomBytes(n), (b) => AES_CHARS[b % AES_CHARS.length]).join('')

export function encryptPassword(password, salt) {
  const cipher = createCipheriv('aes-128-cbc', Buffer.from(salt, 'utf8'), Buffer.from(randomString(16), 'utf8'))
  return Buffer.concat([cipher.update(randomString(64) + password, 'utf8'), cipher.final()]).toString('base64')
}

// ── 教务系统的请求头 ────────────────────────────────────────────────────────
// ★ 实测：jxfw 的每个 `!action` 都要求带 Referer —— 不带就回 **HTTP 200 + 243 字节的
//   `<title>非法访问</title><body>你没有该权限`**，看起来跟"页面改版"一模一样。
//   只校验"是不是本站地址"，不校验具体哪一页（拿 login!welcome.action 当 Referer 也过）。
//   探针必须照抄这一点，否则 `kb` / `weeks` 永远拉不到东西。
const HTML_ACCEPT = 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8'
const JSON_ACCEPT = 'application/json, text/javascript, */*; q=0.01'
const jxfwHeaders = (referer, xhr = false) => ({
  Referer: referer,
  Accept: xhr ? JSON_ACCEPT : HTML_ACCEPT,
  ...(xhr ? { 'X-Requested-With': 'XMLHttpRequest' } : {}),
})

// ── 模式 ───────────────────────────────────────────────────────────────────
async function page() {
  const res = await req(LOGIN_URL)
  const html = await res.text()
  const p = parseLoginPage(html)
  console.log('  页面长度', html.length)
  console.log('  盐 salt      =', p.salt, `(${p.salt.length} 位)`)
  console.log('  execution    =', p.execution.slice(0, 60) + '…', `(${p.execution.length} 位)`)
  console.log('  lt           =', JSON.stringify(p.lt), ' _eventId =', p.eventId, ' cllt =', p.cllt, ' dllt =', p.dllt)
  console.log('  有验证码输入框:', p.hasCaptcha)
  console.log('  页面脚本:', p.scripts.join('\n             '))
  return p
}

async function login() {
  const user = process.env.GDUT_USER, pass = process.env.GDUT_PASS
  if (!user || !pass) throw new Error('请先设置 GDUT_USER / GDUT_PASS 环境变量')
  const p = await page()
  if (!p.salt) throw new Error('登录页里没找到盐（pwdEncryptSalt），页面结构可能变了')
  const body = new URLSearchParams({
    username: user,
    password: encryptPassword(pass, p.salt),
    captcha: '',
    rememberMe: 'true',
    _eventId: p.eventId,
    cllt: p.cllt,
    dllt: p.dllt,
    lt: p.lt,
    execution: p.execution,
  }).toString()
  const res = await req(LOGIN_URL, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/x-www-form-urlencoded',
      Origin: CAS,
      Referer: LOGIN_URL,
      'Upgrade-Insecure-Requests': '1',
    },
    body,
  })
  const loc = res.headers.get('location')
  if (!loc) {
    const html = await res.text()
    // 实测：账号不存在 / 密码错时服务端回 **HTTP 401**，正文是登录页，
    // 原因在 <span id="showErrorTip"><span>…</span></span> 里（"认证失败"）
    console.log(`  ⚠️ 没有跳转（HTTP ${res.status}），登录页错误提示：`,
      html.match(/id="showErrorTip"><span>([\s\S]*?)<\/span>/)?.[1]?.replace(/\s+/g, ' ') ?? '(空)')
    return
  }
  console.log('  ✅ 拿到票据跳转:', loc)
  // 跟随后续 302，把 jxfw 会话种下来
  let url = loc
  for (let hop = 0; hop < 6; hop++) {
    const r = await req(url, { headers: { Referer: `${CAS}/` } })
    const next = r.headers.get('location')
    if (!next) break
    url = next.startsWith('http') ? next : new URL(next, url).toString()
  }
  console.log('  cookie jar:', Object.keys(state.cookies).join(', '))
}

async function kb(xnxqdm = '202601') {
  const res = await req(`${JXFW}/xsgrkbcx!xsAllKbList.action?xnxqdm=${xnxqdm}`, {
    headers: jxfwHeaders(`${JXFW}/xsgrkbcx!getXsgrbkList.action`),
  })
  const html = await res.text()
  writeFileSync(`build/gdut/kb_${xnxqdm}.html`, html)
  if (/非法访问|你没有该权限/.test(html)) {
    console.log('  ⚠️ 被防盗链挡了（你没有该权限）—— 检查上面的 Referer 头')
    return
  }
  if (/login!welcome|name="username"|authserver/.test(html) && !/var kbxx/.test(html)) {
    console.log('  ⚠️ 未登录（被踢回登录页），先跑 login')
    return
  }
  const list = JSON.parse(html.match(/var kbxx\s*=\s*(\[[\s\S]*?\]);/)[1])
  console.log(`  ✅ ${xnxqdm} 共 ${list.length} 条`)
  for (const c of list.slice(0, 5)) {
    console.log(`   ${c.kcmc} | ${c.teaxms} | ${c.jxcdmcs} | 周${c.xq} | 第${c.jcdm2}节 | 周次 ${c.zcs}`)
  }
  writeFileSync(`build/gdut/kb_${xnxqdm}.json`, JSON.stringify(list, null, 1))
}

async function weeks(xnxqdm = '202601') {
  for (const zc of [1, 2, 3]) {
    const res = await req(`${JXFW}/xsgrkbcx!getKbRq.action?xnxqdm=${xnxqdm}&zc=${zc}`, {
      headers: jxfwHeaders(`${JXFW}/xsgrkbcx!xskbList.action?xnxqdm=${xnxqdm}&zc=${zc}`, true),
    })
    const text = await res.text()
    if (/非法访问|你没有该权限/.test(text)) {
      console.log(`   第 ${zc} 周：⚠️ 被防盗链挡了（你没有该权限）`)
      return
    }
    const json = JSON.parse(text)
    const [courses, dates] = json
    console.log(`   第 ${zc} 周：${courses.length} 条课；日期 ${dates.map((d) => d.xqmc + ':' + d.rq).join(' ')}`)
    if (zc === 1) console.log('   样例字段:', JSON.stringify(courses[0] ?? {}, null, 1).slice(0, 600))
  }
}

const [mode, arg, arg2] = process.argv.slice(2)
const run = {
  page,
  crypto: async () => {
    const salt = arg2 ?? (await page()).salt
    console.log('  salt =', salt)
    console.log('  密文 =', encryptPassword(arg ?? 'test', salt))
  },
  login,
  kb: () => kb(arg),
  weeks: () => weeks(arg),
}

if (!run[mode]) { console.error('未知模式:', mode, '\n可用:', Object.keys(run).join(' ')); process.exit(1) }
run[mode]().catch((e) => { console.error('ERR', e); process.exit(1) })
