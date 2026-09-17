# ══════════════════════════════════════════════════════════════════════════
#  应用图标资源生成：tools/icon/many_course_icon.ico → app/src/main/res/…
#
#  为什么要有这个脚本：
#    设计给的是一张 .ico（里面塞了 7 张 16～256px 的 PNG）。Android 侧要的不是
#    单个文件，而是"自适应图标 + 各密度传统位图"一整套：
#      · mipmap-*dpi/ic_launcher_foreground.png  自适应图标前景（108dp，5 档密度）
#      · mipmap-*dpi/ic_launcher.png             传统图标（48dp，5 档密度）
#      · mipmap-*dpi/ic_launcher_round.png       圆形传统图标（48dp，5 档密度）
#      · drawable-nodpi/many_course_icon.png     登录页那张原图（256px 原分辨率）
#    用 IDE 的 Image Asset 向导也能生成，但那样"图从哪来、按什么尺寸缩的"就没记录，
#    换一张设计图得重新点一遍。跑一次这个脚本即可全量重建，尺寸都写死在下面。
#
#  用法：  pwsh tools/make_launcher_icons.ps1
#  依赖：  Windows PowerShell 5.1+ / PowerShell 7（缩放位图用 System.Drawing）
#
#  注意：脚本会**删除**同目录下旧的 ic_launcher.webp / ic_launcher_round.webp ——
#        它们是模板自带的 Android 机器人图标，和新的 PNG 同名同目录，留着既
#        编译冲突又容易让人以为图标没换。
# ══════════════════════════════════════════════════════════════════════════

[CmdletBinding()]
param(
    [string]$SourceIco = (Join-Path $PSScriptRoot 'icon\many_course_icon.ico'),
    [string]$ResDir    = (Join-Path $PSScriptRoot '..\app\src\main\res'),
    [string]$WorkDir   = (Join-Path $PSScriptRoot '..\build\icon_work')
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

# 密度目录 → 倍率（1dp = 几像素）
$Densities = [ordered]@{
    'mdpi'    = 1.0
    'hdpi'    = 1.5
    'xhdpi'   = 2.0
    'xxhdpi'  = 3.0
    'xxxhdpi' = 4.0
}
$AdaptiveDp = 108   # 自适应图标画布：108dp（外圈 18dp 留给系统遮罩/视差）
$LauncherDp = 48    # 传统图标 / 圆形图标：48dp

# ── 1. 从 .ico 里取出最大的一张内嵌 PNG ─────────────────────────────────
#  .ico 是"目录 + 若干张位图"的容器：前 6 字节是头（保留位/类型/张数），
#  之后每 16 字节一条目录项（宽/高/bpp/大小/偏移）。这里挑最宽的那张。
function Get-LargestIcoPng {
    param([Parameter(Mandatory)][string]$Path)

    $bytes = [System.IO.File]::ReadAllBytes($Path)
    if ($bytes.Length -lt 6) { throw "不是有效的 ICO：$Path" }
    if ([BitConverter]::ToUInt16($bytes, 0) -ne 0 -or [BitConverter]::ToUInt16($bytes, 2) -ne 1) {
        throw "不是 ICO 文件（类型位不是 1）：$Path"
    }

    $count = [BitConverter]::ToUInt16($bytes, 4)
    $best  = $null
    for ($i = 0; $i -lt $count; $i++) {
        $o    = 6 + ($i * 16)
        $w    = [int]$bytes[$o]                 # 宽度 0 表示 256
        if ($w -eq 0) { $w = 256 }
        $size = [BitConverter]::ToUInt32($bytes, $o + 8)
        $off  = [BitConverter]::ToUInt32($bytes, $o + 12)
        if ($null -eq $best -or $w -gt $best.Width) {
            $best = [pscustomobject]@{ Width = $w; Size = $size; Offset = $off }
        }
    }
    if ($null -eq $best) { throw "ICO 里没有图：$Path" }

    $png = New-Object byte[] $best.Size
    [Array]::Copy($bytes, $best.Offset, $png, 0, $best.Size)
    Write-Host ("  源图：{0}px（{1} 字节，偏移 {2}）" -f $best.Width, $best.Size, $best.Offset)
    return , $png
}

# ── 2. 缩放 / 铺底 / 圆形遮罩 ────────────────────────────────────────────
function New-IconBitmap {
    param(
        [Parameter(Mandatory)][System.Drawing.Image]$Source,
        [Parameter(Mandatory)][int]$Size,
        [switch]$Flatten
    )

    $bmp = New-Object System.Drawing.Bitmap $Size, $Size, ([System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $g   = [System.Drawing.Graphics]::FromImage($bmp)
    try {
        $g.InterpolationMode  = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
        $g.PixelOffsetMode    = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
        $g.SmoothingMode      = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
        $g.CompositingQuality = [System.Drawing.Drawing2D.CompositingQuality]::HighQuality

        if ($Flatten) {
            #  为什么要垫一张 1.4 倍的自己：
            #    这张图是**满幅**设计 —— 蓝色渐变铺满整块，四角自带圆角、圆角外是透明。
            #    自适应图标由系统套遮罩：圆形遮罩会把四角裁掉，而偏方形的遮罩会露到
            #    四角 —— 那时就会看见设计图自己的透明圆角，透出底下的背景层，像缺了一块。
            #    先画一张放大的自己垫底，正好把四个圆角推到画布外；四角补的是同一套
            #    渐变色，1.4 倍以内的色差看不出来。
            $big = [int][math]::Round($Size * 1.4)
            $pad = [int][math]::Round(($big - $Size) / 2.0)
            $g.DrawImage($Source, -$pad, -$pad, $big, $big)
        }

        $g.DrawImage($Source, 0, 0, $Size, $Size)
    }
    finally {
        $g.Dispose()
    }
    return $bmp
}

function Set-CircleMask {
    #  圆形传统图标：按到圆心的距离把 alpha 削掉，边缘留 1px 过渡做抗锯齿。
    #  用位图运算而不是 Graphics 路径裁剪，是因为 GDI+ 的裁剪区不带抗锯齿，
    #  裁出来是一圈锯齿。
    param([Parameter(Mandatory)][System.Drawing.Bitmap]$Bitmap)

    $size = $Bitmap.Width
    $c    = ($size - 1) / 2.0
    $r    = $size / 2.0
    for ($y = 0; $y -lt $size; $y++) {
        for ($x = 0; $x -lt $size; $x++) {
            $dx  = $x - $c
            $dy  = $y - $c
            $cov = $r - [math]::Sqrt(($dx * $dx) + ($dy * $dy)) + 0.5
            if ($cov -ge 1.0) { continue }
            if ($cov -le 0.0) {
                $Bitmap.SetPixel($x, $y, [System.Drawing.Color]::FromArgb(0, 0, 0, 0))
                continue
            }
            $p = $Bitmap.GetPixel($x, $y)
            $Bitmap.SetPixel($x, $y, [System.Drawing.Color]::FromArgb([int][math]::Round($p.A * $cov), $p.R, $p.G, $p.B))
        }
    }
}

function Save-IconPng {
    param(
        [Parameter(Mandatory)][System.Drawing.Bitmap]$Bitmap,
        [Parameter(Mandatory)][string]$Path
    )
    $dir = Split-Path -Path $Path -Parent
    if (-not (Test-Path -Path $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
    $Bitmap.Save($Path, [System.Drawing.Imaging.ImageFormat]::Png)
    Write-Host ("  {0}  ({1}px)" -f $Path.Substring($ResDir.Length + 1), $Bitmap.Width)
}

# ── 3. 开工 ──────────────────────────────────────────────────────────────
if (-not (Test-Path -Path $SourceIco)) { throw "找不到源图标：$SourceIco" }
if (-not (Test-Path -Path $ResDir)) { throw "找不到资源目录：$ResDir" }
$ResDir = (Resolve-Path -Path $ResDir).Path   # 去掉路径里的 ..，下面按前缀截断打印
Write-Host "源文件：$SourceIco"

$masterPath = Join-Path $WorkDir 'many_course_icon_256.png'
New-Item -ItemType Directory -Force -Path $WorkDir | Out-Null
[System.IO.File]::WriteAllBytes($masterPath, (Get-LargestIcoPng -Path $SourceIco))

$master = [System.Drawing.Image]::FromFile($masterPath)
Write-Host "  画布：$($master.Width)x$($master.Height)"

# 旧的模板位图（Android 机器人）先删掉：与新 PNG 同名同目录，留着会冲突
Get-ChildItem -Path $ResDir -Recurse -Include 'ic_launcher.webp', 'ic_launcher_round.webp' -File |
    ForEach-Object { Write-Host "  删除旧位图：$($_.FullName)"; Remove-Item $_.FullName -Force }

Write-Host '自适应图标前景（108dp，四角铺底）：'
foreach ($d in $Densities.Keys) {
    $size = [int][math]::Round($AdaptiveDp * $Densities[$d])
    $bmp  = New-IconBitmap -Source $master -Size $size -Flatten
    Save-IconPng -Bitmap $bmp -Path (Join-Path $ResDir "mipmap-$d\ic_launcher_foreground.png")
    $bmp.Dispose()
}

Write-Host '传统图标（48dp，保留设计自带的圆角与透明）：'
foreach ($d in $Densities.Keys) {
    $size = [int][math]::Round($LauncherDp * $Densities[$d])
    $bmp  = New-IconBitmap -Source $master -Size $size
    Save-IconPng -Bitmap $bmp -Path (Join-Path $ResDir "mipmap-$d\ic_launcher.png")
    $bmp.Dispose()
}

Write-Host '圆形传统图标（48dp，圆形遮罩）：'
foreach ($d in $Densities.Keys) {
    $size = [int][math]::Round($LauncherDp * $Densities[$d])
    $bmp  = New-IconBitmap -Source $master -Size $size
    Set-CircleMask -Bitmap $bmp
    Save-IconPng -Bitmap $bmp -Path (Join-Path $ResDir "mipmap-$d\ic_launcher_round.png")
    $bmp.Dispose()
}

# 登录页那张：原分辨率 + 保留透明圆角，叠在登录页渐变背景上才融合
Write-Host '登录页图片（drawable-nodpi，原分辨率）：'
$login = New-IconBitmap -Source $master -Size $master.Width
Save-IconPng -Bitmap $login -Path (Join-Path $ResDir 'drawable-nodpi\many_course_icon.png')
$login.Dispose()

$master.Dispose()
Write-Host '完成。'
