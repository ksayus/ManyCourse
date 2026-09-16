// PNG → ASCII 点阵（用来在纯文本环境里"看"验证码图）
// 用法: node tools/png_to_ascii.mjs <png> [阈值] [列缩放]
import { readFileSync } from 'node:fs'
import { inflateSync } from 'node:zlib'

const file = process.argv[2]
const threshold = Number(process.argv[3] ?? 160)
const buf = readFileSync(file)

let p = 8, width = 0, height = 0, bitDepth = 0, colorType = 0, palette = null, idat = []
while (p < buf.length) {
  const len = buf.readUInt32BE(p)
  const type = buf.toString('ascii', p + 4, p + 8)
  const data = buf.subarray(p + 8, p + 8 + len)
  if (type === 'IHDR') {
    width = data.readUInt32BE(0); height = data.readUInt32BE(4)
    bitDepth = data[8]; colorType = data[9]
  } else if (type === 'PLTE') palette = data
  else if (type === 'IDAT') idat.push(data)
  else if (type === 'IEND') break
  p += 12 + len
}
console.error(`# ${width}x${height} bitDepth=${bitDepth} colorType=${colorType}`)

const raw = inflateSync(Buffer.concat(idat))
const channels = { 0: 1, 2: 3, 3: 1, 4: 2, 6: 4 }[colorType]
const bpp = channels * (bitDepth / 8)
const stride = Math.ceil(width * channels * (bitDepth / 8))
const out = Buffer.alloc(height * stride)

function paeth(a, b, c) {
  const pp = a + b - c, pa = Math.abs(pp - a), pb = Math.abs(pp - b), pc = Math.abs(pp - c)
  return pa <= pb && pa <= pc ? a : pb <= pc ? b : c
}
let rp = 0
for (let y = 0; y < height; y++) {
  const filter = raw[rp++]
  const line = raw.subarray(rp, rp + stride); rp += stride
  const cur = out.subarray(y * stride, (y + 1) * stride)
  const prev = y > 0 ? out.subarray((y - 1) * stride, y * stride) : Buffer.alloc(stride)
  for (let x = 0; x < stride; x++) {
    const a = x >= bpp ? cur[x - bpp] : 0
    const b = prev[x]
    const c = x >= bpp ? prev[x - bpp] : 0
    let v = line[x]
    if (filter === 1) v += a
    else if (filter === 2) v += b
    else if (filter === 3) v += (a + b) >> 1
    else if (filter === 4) v += paeth(a, b, c)
    cur[x] = v & 0xff
  }
}

function grayAt(x, y) {
  const o = y * stride + x * (bitDepth / 8) * channels
  if (colorType === 3) {
    const idx = out[o]; return (palette[idx * 3] + palette[idx * 3 + 1] + palette[idx * 3 + 2]) / 3
  }
  if (colorType === 2 || colorType === 6) return (out[o] + out[o + 1] + out[o + 2]) / 3
  return out[o]
}

const RAMP = ' .:-=+*#%@'

if (process.argv[5] === 'num') {
  // 数字灰度图：看抗锯齿细节用（0=白 9=黑）
  for (let y = 0; y < height; y++) {
    let line = ''
    for (let x = 0; x < width; x++) {
      const d = Math.max(0, Math.min(255, 255 - grayAt(x, y)))
      line += String(Math.min(9, Math.round((d / 255) * 14)))
    }
    console.log(line)
  }
} else if (process.argv[5] === 'levels') {
  // 按"离白的距离"分级：不同字符颜色深浅不一，分级才能同时看清
  for (let y = 0; y < height; y++) {
    let line = ''
    for (let x = 0; x < width; x++) {
      const d = Math.max(0, Math.min(255, 255 - grayAt(x, y)))
      line += RAMP[Math.min(RAMP.length - 1, Math.round((d / 255) * (RAMP.length - 1) * 1.6))]
    }
    console.log(line)
  }
} else if (process.argv[5] === 'full') {
  // 原始分辨率：每个像素一个字符（字符是竖长的，看起来会被压扁）
  for (let y = 0; y < height; y++) {
    let line = ''
    for (let x = 0; x < width; x++) line += grayAt(x, y) < threshold ? '#' : ' '
    console.log(line)
  }
} else {
  // 两行合成一行（终端字符是竖长的），暗像素画成块
  for (let y = 0; y < height; y += 2) {
    let line = ''
    for (let x = 0; x < width; x++) {
      const a = grayAt(x, y) < threshold
      const b = y + 1 < height ? grayAt(x, y + 1) < threshold : false
      line += a && b ? '█' : a ? '▀' : b ? '▄' : ' '
    }
    console.log(line)
  }
}
