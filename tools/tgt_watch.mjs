// TGT 有效期观测：每隔 N 分钟用纯 TGT（无任何 Cookie）换一次 ST，记录结果
// 用法: node tools/tgt_watch.mjs [间隔分钟=15] [总时长小时=12]
import { readFileSync, appendFileSync, existsSync } from 'node:fs'

const CAS = 'https://cas.gzus.edu.cn'
const SERVICE = 'https://jwxt.gzus.edu.cn/sso/lyiotlogin'
const intervalMin = Number(process.argv[2] ?? 15)
const hours = Number(process.argv[3] ?? 12)
const LOG = 'build/cas/tgt_lifetime.log'

const tgt = readFileSync('build/cas/tgt.txt', 'utf8').trim()
const t0 = Date.now()
const line = (s) => { appendFileSync(LOG, s + '\n'); console.log(s) }
line(`# TGT 有效期观测开始 ${new Date(t0).toISOString()}  tgt=${tgt}  间隔=${intervalMin}min 上限=${hours}h`)

const deadline = t0 + hours * 3600_000
let round = 0
while (Date.now() < deadline) {
  round++
  const started = Date.now()
  const stamp = new Date(started).toISOString()
  const elapsed = ((started - t0) / 60000).toFixed(0)
  try {
    const res = await fetch(`${CAS}/lyuapServer/v1/tickets/${tgt}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8', loginToken: 'loginToken', 'X-Requested-With': 'XMLHttpRequest', Origin: CAS },
      body: `service=${encodeURIComponent(SERVICE)}&loginToken=loginToken`,
    })
    const text = (await res.text()).trim()
    const ok = /^ST-/.test(text)
    line(`+${elapsed}min  #${round}  ${stamp}  HTTP ${res.status}  ${ok ? '✅ 仍然可用 → ' + text : '❌ 失效/异常 → ' + text.slice(0, 200)}`)
    if (!ok) { line('# TGT 已失效，停止观测'); break }
  } catch (e) {
    line(`+${elapsed}min  #${round}  ${stamp}  网络异常：${e.message}`)
  }
  const wait = intervalMin * 60_000 - (Date.now() - started)
  if (Date.now() + wait >= deadline) break
  await new Promise(r => setTimeout(r, wait))
}
line(`# 观测结束 ${new Date().toISOString()}`)
