# 把"走几圈"采到的数据打进正式版
#
# 用法（在仓库根目录）：
#   1) 先在手机上走几圈（设置 → 开发者 → 开发者模式 → 地图页 → 轨迹采集），
#      App 会把数据写到设备的 ManyCourse 目录：
#        - 有「所有文件访问权限」：/sdcard/ManyCourse/
#        - 没授权（回落）：/sdcard/Android/data/com.tof.manycourse/files/ManyCourse/
#   2) 把这几份文件拉到电脑（两条命令，按实际情况选一条）：
#        adb pull /sdcard/ManyCourse tools/indoor_data
#        adb pull /sdcard/Android/data/com.tof.manycourse/files/ManyCourse tools/indoor_data
#   3) 运行本脚本：
#        pwsh tools/pack_walk_data.ps1
#   4) 重新构建 APK —— 这一版就自带点位与指纹，**新用户不用先走一遍**。
#
# 只拷贝、不解析：合并与去重全部在 App 里做（`data/MapPointStore.kt` /
# `data/WifiFingerprintStore.kt`），逻辑留在有单测的 Kotlin 里，脚本保持成十行拷贝。
#
# 带 -Clean 会先清空目标目录（重新开始一份干净的内置数据）。

param(
    [string]$Source = "tools/indoor_data",
    [string]$Dest = "app/src/main/assets/indoor",
    [switch]$Clean
)

$ErrorActionPreference = "Stop"

# 认得出头部的文件才会被打包（版本号在头部，App 也靠它识别类型）
$knownHeaders = @(
    "manycourse-mappoints/1",   # 点位 + 标定锚点
    "manycourse-wifi/1"         # Wi-Fi 指纹库
)

if (-not (Test-Path $Source)) {
    Write-Host "没有找到源目录：$Source" -ForegroundColor Yellow
    Write-Host ""
    Write-Host "先把设备上的数据拉下来（二选一）：" -ForegroundColor Cyan
    Write-Host "  adb pull /sdcard/ManyCourse $Source"
    Write-Host "  adb pull /sdcard/Android/data/com.tof.manycourse/files/ManyCourse $Source"
    Write-Host ""
    Write-Host "（前者需要手机上给过「所有文件访问」权限；没给过就用后者）"
    exit 1
}

if ($Clean -and (Test-Path $Dest)) {
    Remove-Item -Recurse -Force $Dest
    Write-Host "已清空 $Dest"
}

New-Item -ItemType Directory -Force -Path $Dest | Out-Null

$copied = @()
$skipped = @()
Get-ChildItem -Path $Source -File | Sort-Object Name | ForEach-Object {
    $file = $_
    $header = (Get-Content $file.FullName -TotalCount 1)
    if ($knownHeaders -contains $header) {
        Copy-Item $file.FullName (Join-Path $Dest $file.Name) -Force
        $copied += $file
    } else {
        $skipped += $file
    }
}

Write-Host ""
if ($copied.Count -eq 0) {
    Write-Host "没有可打包的文件（头部要对得上 manycourse-mappoints/1 或 manycourse-wifi/1）" -ForegroundColor Yellow
} else {
    Write-Host "已打进 $Dest ：" -ForegroundColor Green
    $copied | ForEach-Object {
        "{0,-28} {1,8:N0} B" -f $_.Name, $_.Length | Write-Host
    }
    Write-Host ""
    Write-Host "下一步：重新构建（例如 .\gradlew.bat assembleDebug），"
    Write-Host "装到手机上打开地图页 —— 点位与 Wi-Fi 指纹会由 assets 自动载入（自己采的优先）。"
}
if ($skipped.Count -gt 0) {
    Write-Host ""
    Write-Host "跳过了 $($skipped.Count) 个认不出的文件：" -ForegroundColor DarkGray
    $skipped | ForEach-Object { Write-Host "  $($_.Name)" -ForegroundColor DarkGray }
}
