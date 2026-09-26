# 把「采集数据」离线打成随正式版下发的内置数据（assets/indoor/）
#
# 用法（在仓库根目录）：
#   1) 把设备上的数据拉到电脑：
#        adb pull /sdcard/ManyCourse point
#        （没给「所有文件访问」权限时：adb pull /sdcard/Android/data/com.tof.manycourse/files/ManyCourse point）
#   2) 运行本脚本：
#        pwsh tools/pack_indoor_data.ps1
#   3) 重新构建：.\gradlew.bat assembleDebug
#
# 它比 pack_walk_data.ps1 多做一件事：**认原始轨迹**。
#
#   pack_walk_data.ps1   只认 manycourse-mappoints/1 与 manycourse-wifi/1，轨迹文件跳过
#                         —— 因为它们必须"先在 App 里融合"才入库（BundledIndoorData 刻意不认）
#   本脚本               顺手把 walk-*.txt 也融了：调 App 自己的 fuseWalk（Kotlin），
#                        在 JVM 上跑一遍，产出点位/指纹，再并库去重
#
# 所以：**采集时没点「停止并融合」的那几趟，靠本脚本才进得了库。**
#
# 解析/融合的逻辑一行都不在 PowerShell 里 —— 全在 tools/indoor_pack/IndoorPack.kt，
# 它调用的又是 app 里那几个有单测的纯 JVM 类（WalkLogCodec / fuseWalk / MapPointCodec /
# WifiFingerprintCodec）。脚本只负责"编译 + 跑 + 报告"。
#
# 参数：
#   -Source            采集数据目录（默认 point）
#   -Dest              输出目录（默认 app/src/main/assets/indoor）
#   -StepLengthMeters  步长（默认 0.7，和 WalkFusion.DEFAULT_STEP_LENGTH_METERS 一致）
#   -Clean             先清空输出目录（重新开始一份干净的内置数据）
#   -KotlinHome        指定 Kotlin 编译器所在目录（默认自动从 Gradle 缓存里找）

param(
    [string]$Source = "point",
    [string]$Dest = "app/src/main/assets/indoor",
    [double]$StepLengthMeters = 0.7,
    [switch]$Clean,
    [string]$KotlinHome = ""
)

$ErrorActionPreference = "Stop"

# 控制台按 UTF-8 出，否则中文报告在 GBK 代码页下会变成乱码
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

$root = Split-Path -Parent $PSScriptRoot
$workDir = Join-Path $root "build/indoor_pack"
$classesDir = Join-Path $workDir "classes"

# ── 要编译的源文件 ────────────────────────────────────────────────────────
#
# 只有这几个文件是**纯 JVM** 的（不 import android./androidx.），所以能脱离 Android 跑。
# 它们构成一个闭环：MapPoint 提供标定的数学，WalkFusion 用标定把轨迹推成位置，
# 三个 *Codec 负责读写文件格式。哪一个漏了，编译器会直接报 unresolved reference。
$kotlinSources = @(
    "app/src/main/java/com/tof/manycourse/data/MapPoint.kt",        # 点位/锚点/标定（投影、最小二乘）
    "app/src/main/java/com/tof/manycourse/data/MapPointCodec.kt",   # manycourse-mappoints/1
    "app/src/main/java/com/tof/manycourse/data/WalkLog.kt",         # manycourse-walk/1
    "app/src/main/java/com/tof/manycourse/data/WalkFusion.kt",      # ★ fuseWalk：轨迹 → 点位 + 指纹
    "app/src/main/java/com/tof/manycourse/data/WifiFingerprint.kt", # manycourse-wifi/1 + kNN
    "tools/indoor_pack/IndoorPack.kt"                               # 本工具自己的入口
)

function Fail($message) {
    Write-Host ""
    Write-Host $message -ForegroundColor Red
    exit 1
}

# 跑 java，把 stdout 与 stderr 一起收下来；退出码放在 $script:javaExitCode。
#
# 为什么不直接写 `& java ... 2>&1`：java 往 stderr 写的**任何一行**（哪怕只是
# "sun.misc.Unsafe 已废弃"这种无害警告）在 $ErrorActionPreference = 'Stop' 下会被
# PowerShell 当成终止性错误，脚本当场中断，而且只报一个 NativeCommandError，
# 完全看不出是"编译警告"还是"编译真的失败了"。
function Invoke-Java([string[]]$Arguments) {
    $previous = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $output = & java @Arguments 2>&1
        $script:javaExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previous
    }
    return $output | ForEach-Object { "$_" }
}

if (-not (Test-Path (Join-Path $root $Source))) {
    Fail "没有找到来源目录：$Source（先 adb pull /sdcard/ManyCourse $Source）"
}

# ── 找 Kotlin 编译器 ──────────────────────────────────────────────────────
#
# 不要求装 kotlinc：Gradle 为了自己编译 Kotlin 早就把一个完整的编译器下载到缓存里了
# （org.jetbrains.kotlin:kotlin-compiler-embeddable）。直接拿它用，**不新增任何依赖**。
function Find-Jar([string]$cachesRoot, [string]$pattern) {
    if (-not (Test-Path $cachesRoot)) { return $null }
    Get-ChildItem -Path $cachesRoot -Recurse -Filter $pattern -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -notmatch 'sources|javadoc' } |
        Sort-Object FullName -Descending |
        Select-Object -First 1 -ExpandProperty FullName
}

# ★ 编译器那一串 jar **必须同版本**：混用（比如 stdlib 2.2.10 配 reflect 2.3.20）
# 会在启动时炸 NoClassDefFoundError。所以先按版本精确找，找不到才退回"任意版本"。
function Find-KotlinJar([string]$cachesRoot, [string]$artifact, [string]$version) {
    $exact = Find-Jar $cachesRoot "$artifact-$version.jar"
    if ($exact) { return $exact }
    return Find-Jar $cachesRoot "$artifact-*.jar"
}

# 按 group 目录找。不能只按文件名找：`annotations-*.jar` 在缓存里同时有
# com.android.tools/annotations 和 com.google.android/annotations，
# 按路径排序会先撞上它们 —— 而编译器要的是 org.jetbrains:annotations
# （少了它，代码生成阶段会炸 `NoClassDefFoundError: org/jetbrains/annotations/NotNull`）。
function Find-GroupJar([string]$cachesRoot, [string]$groupPath, [string]$artifact, [string]$version) {
    $groupDir = Join-Path $cachesRoot $groupPath
    if (-not (Test-Path $groupDir)) { return $null }
    $exact = Get-ChildItem -Path $groupDir -Recurse -Filter "$artifact-$version.jar" -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -notmatch 'sources|javadoc' } |
        Select-Object -First 1 -ExpandProperty FullName
    if ($exact) { return $exact }
    return Get-ChildItem -Path $groupDir -Recurse -Filter "$artifact-*.jar" -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -notmatch 'sources|javadoc' } |
        Select-Object -First 1 -ExpandProperty FullName
}

$version = "2.2.10" # 与 gradle/libs.versions.toml 的 kotlin 版本一致
$compilerJar = $null
$stdlibJar = $null
$modulesRoot = $null

if ($KotlinHome -ne "") {
    $compilerJar = Find-KotlinJar $KotlinHome "kotlin-compiler-embeddable" $version
    $stdlibJar = Find-KotlinJar $KotlinHome "kotlin-stdlib" $version
    $modulesRoot = $KotlinHome
}

if (-not $compilerJar) {
    $searchRoots = @()
    if ($env:GRADLE_USER_HOME) { $searchRoots += (Join-Path $env:GRADLE_USER_HOME "caches/modules-2/files-2.1") }
    $searchRoots += (Join-Path $env:USERPROFILE ".gradle/caches/modules-2/files-2.1")
    $searchRoots += (Join-Path $root ".gradle/caches/modules-2/files-2.1")

    foreach ($searchRoot in $searchRoots) {
        $compilerJar = Find-KotlinJar $searchRoot "kotlin-compiler-embeddable" $version
        if ($compilerJar) {
            $modulesRoot = $searchRoot
            $stdlibJar = Find-KotlinJar $searchRoot "kotlin-stdlib" $version
            break
        }
    }
}

if (-not $compilerJar -or -not $stdlibJar) {
    Fail "找不到 Kotlin 编译器（kotlin-compiler-embeddable / kotlin-stdlib）。`n" +
         "它本该在 Gradle 缓存里 —— 先跑一次 .\gradlew.bat compileDebugKotlin 把它拉下来，`n" +
         "或者用 -KotlinHome <目录> 指定一个装了 kotlinc 的目录。"
}

# 编译器自己还要这几个才跑得起来（少了会 ClassNotFoundException / NoClassDefFoundError）。
# 同样按版本对齐；annotations 必须限定在 org.jetbrains 组下（见 Find-GroupJar）。
$extraJars = @()
if ($modulesRoot) {
    $extras = @(
        @("org.jetbrains.kotlin", "kotlin-reflect"),
        @("org.jetbrains.kotlin", "kotlin-script-runtime"),
        @("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm"),
        @("org.jetbrains", "annotations")
    )
    foreach ($extra in $extras) {
        $jar = Find-GroupJar $modulesRoot $extra[0] $extra[1] $version
        if (-not $jar) { $jar = Find-KotlinJar $modulesRoot $extra[1] $version }
        if ($jar) { $extraJars += $jar }
    }
}

$compilerClasspath = (@($compilerJar, $stdlibJar) + $extraJars) -join ";"

Write-Host "Kotlin 编译器：$(Split-Path -Leaf $compilerJar)" -ForegroundColor DarkGray

# ── 编译 ──────────────────────────────────────────────────────────────────
if ($Clean -and (Test-Path $Dest)) {
    Remove-Item -Recurse -Force $Dest
    Write-Host "已清空 $Dest" -ForegroundColor DarkGray
}
Remove-Item -Recurse -Force $classesDir -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $classesDir | Out-Null

$sourcePaths = $kotlinSources | ForEach-Object { Join-Path $root $_ }
foreach ($path in $sourcePaths) {
    if (-not (Test-Path $path)) { Fail "源文件不见了：$path" }
}

Write-Host "编译…" -ForegroundColor DarkGray
# 先把参数拼好再传。（写成 `Invoke-Java -Arguments @(...) + $sourcePaths` 会被解析成
# "调用完再把数组加起来"，源文件根本没传进去 —— 编译器拿不到源文件就退回 REPL，
# 报的是一句莫名其妙的 "Kotlin REPL is deprecated"。）
$compileArguments = @(
    "-cp", $compilerClasspath,
    "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
    "-no-stdlib", "-nowarn",
    "-cp", $stdlibJar,
    "-d", $classesDir
)
$compileArguments += $sourcePaths

$compilerOutput = Invoke-Java -Arguments $compileArguments
$compilerOutput | Where-Object {
    $_ -notmatch 'sun\.misc\.Unsafe|WARNING: Please consider|WARNING: A terminally'
} | ForEach-Object { Write-Host $_ }

if ($script:javaExitCode -ne 0) { Fail "编译失败（exit $script:javaExitCode）" }

# ── 跑融合 ────────────────────────────────────────────────────────────────
Write-Host ""
$runArguments = @(
    "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8",
    "-cp", "$classesDir;$stdlibJar",
    "com.tof.manycourse.tools.IndoorPackKt", $Source, $Dest, "$StepLengthMeters"
)
Invoke-Java -Arguments $runArguments | ForEach-Object { Write-Host $_ }

if ($script:javaExitCode -ne 0) { Fail "融合失败（exit $script:javaExitCode）" }

# 收尾话由 Kotlin 那边打（它知道写了几条点位/指纹，也知道复核过没过），这里不重复。
