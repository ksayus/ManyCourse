// 广软 CAS 联调探针（只在本地开发用；凭据走环境变量，不落盘）
//
//   node tools/cas_probe.mjs captcha            取一张验证码图（存 build/cas/captcha.png + state.json）
//   node tools/cas_probe.mjs validate <答案>     只校验验证码（不消耗登录次数，用来确认算式）
//   node tools/cas_probe.mjs login <答案>        真正登录，打印响应头/tgt/ticket
//   node tools/cas_probe.mjs tgt-reuse          用 build/cas/tgt.txt 里的 TGT 换新票据（全新 cookie jar）
//   node tools/cas_probe.mjs kb                 用已建立的会话拉 N2154 周次课表（第 3 周）
import { readFileSync, writeFileSync, existsSync, mkdirSync } from 'node:fs'

const CAS = 'https://cas.gzus.edu.cn'
const JWXT = 'https://jwxt.gzus.edu.cn'
const SERVICE = `${JWXT}/sso/lyiotlogin`
const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/152.0.0.0 Safari/537.36 Edg/152.0.0.0'

const MODULUS_HEX = '00b5eeb166e069920e80bebd1fea4829d3d1f3216f2aabe79b6c47a3c18dcee5fd22c2e7ac519cab59198ece036dcf289ea8201e2a0b9ded307f8fb704136eaeb670286f5ad44e691005ba9ea5af04ada5367cd724b5a26fdb5120cc95b6431604bd219c6b7d83a6f8f24b43918ea988a76f93c333aa5a20991493d4eb1117e7b1'
const EXPONENT_HEX = '010001'
const TAG = 'lyasp'

const STATE = 'build/cas/state.json'
mkdirSync('build/cas', { recursive: true })

// ── 照抄 CasRsaCipher.encryptToHex：裸模幂 + 小端 + 末尾补零 + 定长十六进制 ──
function encryptToHex(plain) {
  const modulus = BigInt('0x' + MODULUS_HEX)
  const exponent = BigInt('0x' + EXPONENT_HEX)
  const chunk = 2 * Math.floor((modulus.toString(2).length - 1) / 16)
  const bytes = [...Buffer.from(plain, 'utf8')]
  while (bytes.length % chunk !== 0) bytes.push(0)
  let value = 0n
  for (let i = bytes.length - 1; i >= 0; i--) value = (value << 8n) | BigInt(bytes[i])
  let r = 1n, b = value % modulus, e = exponent
  while (e > 0n) { if (e & 1n) r = (r * b) % modulus; b = (b * b) % modulus; e >>= 1n }
  return r.toString(16).padStart(4 * (Math.floor((modulus.toString(2).length - 1) / 16) + 1), '0')
}

// ── cookie jar（持久化到 state.json，方便跨进程复现 TGT 复用）──────────────
let state = existsSync(STATE) ? JSON.parse(readFileSync(STATE, 'utf8')) : { cookies: {}, uid: null }
const save = () => writeFileSync(STATE, JSON.stringify(state, null, 1))
const cookieHeader = () => Object.entries(state.cookies).map(([k, v]) => `${k}=${v}`).join('; ')

async function req(url, init = {}, label = '') {
  const headers = { 'User-Agent': UA, ...(init.headers ?? {}) }
  const ck = cookieHeader()
  if (ck) headers['Cookie'] = ck
  const res = await fetch(url, { ...init, headers, redirect: init.redirect ?? 'manual' })
  console.log(`  → ${init.method ?? 'GET'} ${url.replace(CAS, '(cas)').replace(JWXT, '(jwxt)')} = ${res.status}`)
  for (const sc of res.headers.getSetCookie()) {
    console.log('    ⇐ Set-Cookie:', sc)
    const [pair] = sc.split(';'); const i = pair.indexOf('=')
    const name = pair.slice(0, i).trim(), value = pair.slice(i + 1).trim()
    if (!value || /expires=Thu, 01 Jan 1970/i.test(sc)) delete state.cookies[name]
    else state.cookies[name] = value
  }
  save()
  return res
}

const casFormHeaders = () => ({
  'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8',
  'X-Requested-With': 'XMLHttpRequest',
  Origin: CAS,
  Referer: `${CAS}/lyuapServer/login?service=${encodeURIComponent(SERVICE)}`,
})

async function fetchCaptcha(refresh = false) {
  if (!refresh) {
    console.log('① 打开 CAS 登录页')
    await (await req(`${CAS}/lyuapServer/login?service=${encodeURIComponent(SERVICE)}`)).text()
    console.log('② loginType')
    await (await req(`${CAS}/lyuapServer/loginType?_t=${Date.now()}`, { headers: { 'X-Requested-With': 'XMLHttpRequest' } })).text()
  }
  const uid = state.uid ?? crypto.randomUUID().replace(/-/g, '')
  console.log(refresh ? '③ 同一 uid 重新取验证码' : '③ 取验证码')
  const res = await req(`${CAS}/lyuapServer/kaptcha?uid=${uid}`, { headers: { 'X-Requested-With': 'XMLHttpRequest' } })
  const json = await res.json()
  state.uid = json.uid || uid
  save()
  const b64 = (json.content ?? '').replace(/^data:image\/\w+;base64,/, '')
  if (!b64) { console.log('  ⚠️ 没有图片内容：', JSON.stringify(json).slice(0, 400)); return }
  writeFileSync('build/cas/captcha.png', Buffer.from(b64, 'base64'))
  console.log('  uid =', state.uid, ' 图片已存 build/cas/captcha.png')
  console.log('  看图： node tools/png_to_ascii.mjs build/cas/captcha.png 0 x num')
}

async function validate(answer) {
  const res = await req(`${CAS}/lyuapServer/validateLoginCode`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json;charset=UTF-8', 'X-Requested-With': 'XMLHttpRequest', Origin: CAS, Referer: `${CAS}/lyuapServer/login?service=${encodeURIComponent(SERVICE)}` },
    body: JSON.stringify({ id: state.uid, code: answer }),
  })
  console.log('  ←', (await res.text()).slice(0, 500))
}

async function login(answer) {
  const res = await req(`${CAS}/lyuapServer/v1/tickets`, {
    method: 'POST',
    headers: casFormHeaders(),
    body: new URLSearchParams({
      username: process.env.GZUS_USER,
      password: encryptToHex(process.env.GZUS_PASS),
      service: SERVICE,
      loginType: '',
      id: state.uid ?? '',
      code: answer,
    }).toString(),
  })
  for (const [k, v] of res.headers) if (!['content-encoding', 'transfer-encoding'].includes(k)) console.log(`    ${k}: ${v.slice(0, 300)}`)
  const body = await res.text()
  console.log('  body:', body.slice(0, 1500))
  try {
    const j = JSON.parse(body)
    const tgt = j.tgt ?? j.data?.tgt
    if (tgt) { writeFileSync('build/cas/tgt.txt', tgt); console.log('  ✅ TGT:', tgt) }
    const st = j.ticket ?? j.data?.ticket
    if (st) { writeFileSync('build/cas/st.txt', st); console.log('  ✅ ST:', st) }
  } catch { }
}

async function followTicket(ticket) {
  let url = `${JWXT}/sso/lyiotlogin?ticket=${encodeURIComponent(ticket)}`
  for (let hop = 0; hop < 8; hop++) {
    const res = await req(url, { headers: { Referer: `${CAS}/` } })
    const loc = res.headers.get('location')
    console.log(`     hop${hop} → ${loc ?? '(no location)'}`)
    if (!loc) {
      const t = await res.text()
      console.log('     final body head:', t.slice(0, 300).replace(/\s+/g, ' '))
      break
    }
    url = loc.startsWith('http') ? loc : new URL(loc, url).toString()
    if (url.includes('ticketlogin') || url.includes('login_slogin')) continue
  }
  console.log('  cookie jar:', Object.keys(state.cookies).join(', '))
}

async function kb(week = '3', xnm = '2026', xqm = '3') {
  const res = await req(`${JWXT}/jwglxt/kbcx/xskbcxMobile_cxXsKb.html?gnmkdm=N2154`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8',
      'X-Requested-With': 'XMLHttpRequest',
      Origin: JWXT,
      Referer: `${JWXT}/jwglxt/kbcx/xskbcxZccx_cxXskbcxIndex.html?gnmkdm=N2154&layout=default`,
    },
    body: new URLSearchParams({ xnm, xqm, zs: week, kblx: '1', doType: 'app', xh: '' }).toString(),
  })
  const body = await res.text()
  writeFileSync(`build/cas/kb_week${week}.json`, body)
  console.log('  body:', body.slice(0, 600))
  try {
    const j = JSON.parse(body)
    console.log('  ✅ 第', j.zs, '周 课程数', j.kbList?.length, ' 学生', j.xsxx?.XM, j.xsxx?.BJMC)
    console.log('  日期:', j.rqazcList?.map(r => `${r.xqjmc}${r.rq}`).join(' '))
  } catch { console.log('  ⚠️ 不是 JSON') }
}

const [mode, arg] = process.argv.slice(2)
const run = {
  captcha: () => fetchCaptcha(false),
  refresh: () => fetchCaptcha(true),
  validate: () => validate(arg),
  login: () => login(arg),
  'login-follow': async () => { await login(arg); const st = readFileSync('build/cas/st.txt', 'utf8').trim(); await followTicket(st) },
  'tgt-reuse': async () => {
    const tgt = readFileSync('build/cas/tgt.txt', 'utf8').trim()
    console.log('复用 TGT（全新 cookie jar）：', tgt)
    state.cookies = {}; save()
    const res = await req(`${CAS}/lyuapServer/v1/tickets/${tgt}`, {
      method: 'POST', headers: casFormHeaders(),
      body: `service=${encodeURIComponent(SERVICE)}&loginToken=loginToken`,
    })
    for (const [k, v] of res.headers) if (!['content-encoding', 'transfer-encoding'].includes(k)) console.log(`    ${k}: ${v.slice(0, 300)}`)
    const t = await res.text()
    console.log('  ← 原文:', t)
    try {
      const j = JSON.parse(t)
      if (j.tgt) console.log('  ⚠️ 服务端轮换了 TGT:', j.tgt)
    } catch { }
  },
  'tgt-follow': async () => {
    const tgt = readFileSync('build/cas/tgt.txt', 'utf8').trim()
    state.cookies = {}; save()
    const r = await req(`${CAS}/lyuapServer/v1/tickets/${tgt}`, {
      method: 'POST', headers: casFormHeaders(),
      body: `service=${encodeURIComponent(SERVICE)}&loginToken=loginToken`,
    })
    const t = await r.text()
    let st = t.trim()
    try { const j = JSON.parse(t); st = j.ticket ?? j.data?.ticket ?? j.data } catch { }
    console.log('  ST =', st)
    console.log('  —— 用这个 ST 建立教务会话 ——')
    await followTicket(st)
    await kb('3')
  },
  kb: () => kb(arg ?? '3'),
}

if (!run[mode]) { console.error('未知模式:', mode, '\n可用:', Object.keys(run).join(' ')); process.exit(1) }
run[mode]().catch(e => { console.error('ERR', e); process.exit(1) })
