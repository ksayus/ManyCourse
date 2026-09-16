// HAR 条目原文导出：node tools/har_extract.mjs <har> <index[,index...]> <outDir>
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs'
import { join } from 'node:path'

const [, , file, indices, outDir] = process.argv
const har = JSON.parse(readFileSync(file, 'utf8'))
mkdirSync(outDir, { recursive: true })

for (const raw of indices.split(',')) {
  const i = Number(raw)
  const e = har.log.entries[i]
  if (!e) { console.log(`#${i} 不存在`); continue }
  const u = new URL(e.request.url)
  const slug = `${String(i).padStart(3, '0')}_${e.request.method}_${u.pathname.replace(/[^\w.-]+/g, '_')}`
  writeFileSync(join(outDir, slug + '.req.txt'), [
    e.request.method + ' ' + e.request.url,
    ...(e.request.headers ?? []).map(h => h.name + ': ' + h.value),
    '',
    e.request.postData?.text ?? '',
  ].join('\n'))
  writeFileSync(join(outDir, slug + '.res.txt'), [
    'HTTP ' + e.response.status + ' ' + (e.response.statusText ?? ''),
    ...(e.response.headers ?? []).map(h => h.name + ': ' + h.value),
    '',
    e.response.content?.text ?? '',
  ].join('\n'))
  console.log(`${slug}  req=${(e.request.postData?.text ?? '').length}B res=${(e.response.content?.text ?? '').length}B`)
}
