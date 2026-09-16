// 解析 N2154 周次课表响应：node tools/kb_analyze.mjs <res.txt>
import { readFileSync } from 'node:fs'

const txt = readFileSync(process.argv[2], 'utf8')
const body = txt.slice(txt.indexOf('\n\n') + 2).trim()
const json = JSON.parse(body)

console.log('顶层字段:', Object.keys(json).join(', '))
console.log('\nxsxx:', JSON.stringify(json.xsxx, null, 1))
console.log('\nrqazcList:', JSON.stringify(json.rqazcList))
console.log('\nsjfwkg:', json.sjfwkg, ' xskbsfxstkzt:', json.xskbsfxstkzt, ' qsxqj:', json.qsxqj)

console.log('\nkbList 条数:', json.kbList?.length)
for (const k of json.kbList ?? []) {
  console.log(JSON.stringify({
    kcmc: k.kcmc, xm: k.xm, lh: k.lh, cdmc: k.cdmc, xqmc: k.xqmc,
    xqj: k.xqj, jcs: k.jcs, jc: k.jc, zcd: k.zcd, zcmc: k.zcmc,
    kcxszc: k.kcxszc, zs: k.zs, oldzc: k.oldzc, oldjc: k.oldjc,
    xkbz: k.xkbz, pkbj: k.pkbj, sxbj: k.sxbj, kcbj: k.kcbj,
  }))
}
console.log('\nkbList[0] 全字段:', Object.keys(json.kbList?.[0] ?? {}).sort().join(', '))
console.log('\nsjkList 条数:', json.sjkList?.length)
console.log('unknown top keys w/ values:')
for (const [k, v] of Object.entries(json)) {
  if (!['xsxx', 'kbList', 'sjkList', 'rqazcList'].includes(k)) console.log(' ', k, '=', JSON.stringify(v).slice(0, 200))
}
