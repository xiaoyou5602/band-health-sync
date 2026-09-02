<#
.SYNOPSIS
    「健康数据」fork 的一条命令覆盖安装：核对 -> 备份 -> 安装 -> 双冷启动 -> 报告。

.DESCRIPTION
    把 docs/FORK_MAINTENANCE.md「覆盖安装与验收」一节固化为可重复执行的流程。
    默认安静：每个阶段只打印一行结论；任一阶段失败才展开该阶段的全部细节并停止。
    失败一律 fail-closed —— 备份没做完或核对不过，绝不进入安装。

    永不执行：卸载、pm clear、改包名、覆盖已存在的备份目录。

.PARAMETER Apk
    要安装的 APK，默认 app\build\outputs\apk\mainline\debug\app-mainline-debug.apk

.PARAMETER Serial
    指定 adb serial。省略时自动解析；同一台手机的多条 transport 会按 ro.serialno 去重。

.PARAMETER Build
    先执行 .\gradlew.bat assembleMainlineDebug --no-parallel

.PARAMETER DryRun
    走完核对与备份后停在安装前，不改动手机。

.PARAMETER Force
    本地 APK 与设备上已装的哈希相同时仍然安装。

.PARAMETER AutoRollback
    安装后验收失败时，自动用本次备份的 installed-old.apk 覆盖回退。
    默认关闭：失败时只停下并打印回退命令，由 toge 决定。

.PARAMETER Detail
    成功时也打印全部细节。

.EXAMPLE
    .\tools\install-health.ps1
.EXAMPLE
    .\tools\install-health.ps1 -Build
.EXAMPLE
    .\tools\install-health.ps1 -DryRun
#>
[CmdletBinding()]
param(
    [string]$Apk,
    [string]$Serial,
    [string]$BackupRoot = 'D:\备份\健康数据\升级前',
    [switch]$Build,
    [switch]$DryRun,
    [switch]$Force,
    [switch]$AutoRollback,
    [switch]$Detail
)

$ErrorActionPreference = 'Stop'
$PKG        = 'nodomain.freeyourgadget.gadgetbridge.toge'
$WANT_LABEL = '健康数据'
$EXPORT_DIR = '/sdcard/Download/手环'
$HUAWEI_DB  = 'Gadgetbridge-huawei.db'
$XIAOMI_DB  = 'Gadgetbridge.db'

$RepoRoot = Split-Path -Parent $PSScriptRoot
if (-not $Apk) { $Apk = Join-Path $RepoRoot 'app\build\outputs\apk\mainline\debug\app-mainline-debug.apk' }

try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

# ---------------------------------------------------------------- 阶段输出

$script:Log       = New-Object System.Collections.Generic.List[string]
$script:Buf       = New-Object System.Collections.Generic.List[string]
$script:Stage     = ''
$script:Warns     = New-Object System.Collections.Generic.List[string]
$script:Facts     = [ordered]@{}
$script:Installed = $false
$script:BackupDir = $null
$script:Started   = Get-Date

function Start-Stage([string]$name) {
    $script:Stage = $name
    $script:Buf.Clear()
    $script:Log.Add('')
    $script:Log.Add("=== $name ===")
}

function Write-Note([string]$msg) {
    if ($null -eq $msg) { return }
    $script:Buf.Add($msg)
    $script:Log.Add("    $msg")
}

function Complete-Stage([string]$summary) {
    Write-Host ("  [ok]   {0,-10} {1}" -f $script:Stage, $summary)
    $script:Log.Add("    -> $summary")
    if ($Detail) { foreach ($l in $script:Buf) { Write-Host "         $l" -ForegroundColor DarkGray } }
}

function Write-Warn([string]$msg) {
    Write-Host ("  [warn] {0,-10} {1}" -f $script:Stage, $msg) -ForegroundColor Yellow
    $script:Warns.Add("$($script:Stage)：$msg")
    $script:Log.Add("    !! $msg")
}

function Stop-Run([string]$reason) {
    Write-Host ("  [FAIL] {0,-10} {1}" -f $script:Stage, $reason) -ForegroundColor Red
    Write-Host ''
    Write-Host "--- $($script:Stage) 阶段细节 ---" -ForegroundColor Red
    foreach ($l in $script:Buf) { Write-Host "  $l" -ForegroundColor DarkGray }
    throw "STAGE_FAILED: $reason"
}

# ---------------------------------------------------------------- 外部命令

function Invoke-Native {
    param(
        [Parameter(Mandatory = $true)][string]$File,
        [string[]]$Arguments = @(),
        [string]$OutFile
    )
    if ($OutFile) { $tmpOut = $OutFile } else { $tmpOut = [IO.Path]::GetTempFileName() }
    $tmpErr = [IO.Path]::GetTempFileName()
    $quoted = @()
    foreach ($a in $Arguments) {
        if ($a -match '[\s"]') { $quoted += '"' + ($a -replace '"', '\"') + '"' } else { $quoted += $a }
    }
    if ($quoted.Count -eq 0) { $quoted = @('') }
    $p = Start-Process -FilePath $File -ArgumentList $quoted -NoNewWindow -Wait -PassThru `
                       -RedirectStandardOutput $tmpOut -RedirectStandardError $tmpErr
    $errText = ''
    if (Test-Path $tmpErr) { $errText = Get-Content $tmpErr -Raw -Encoding UTF8 -ErrorAction SilentlyContinue }
    $outText = ''
    if (-not $OutFile) {
        if (Test-Path $tmpOut) { $outText = Get-Content $tmpOut -Raw -Encoding UTF8 -ErrorAction SilentlyContinue }
        Remove-Item $tmpOut -Force -ErrorAction SilentlyContinue
    }
    Remove-Item $tmpErr -Force -ErrorAction SilentlyContinue
    if ($null -eq $outText) { $outText = '' }
    if ($null -eq $errText) { $errText = '' }
    [pscustomobject]@{
        ExitCode = $p.ExitCode
        Out      = $outText.TrimEnd()
        Err      = $errText.TrimEnd()
    }
}

function Invoke-Adb {
    param([string[]]$Arguments, [string]$OutFile)
    Invoke-Native -File $script:AdbExe -Arguments (@('-s', $script:Serial) + $Arguments) -OutFile $OutFile
}

function Invoke-Shell([string]$cmd) {
    (Invoke-Adb -Arguments @('shell', $cmd)).Out
}

# apksigner 的行首因签名方案而异：'Signer #1 certificate ...' 或 'V2 Signer: certificate ...'
function Get-CertSha256([string]$apkPath) {
    $out = Invoke-Native -File $script:ApkSigner -Arguments @('verify', '--print-certs', $apkPath)
    $m = [regex]::Match($out.Out, '(?im)certificate SHA-256 digest:\s*([0-9a-fA-F]{64})')
    if ($m.Success) { return $m.Groups[1].Value.ToLower() }
    # apksigner 正常输出里 WARNING 极多，失败时只回显有意义的行
    foreach ($l in ($out.Out -split "`r?`n" | Where-Object { $_ -and ($_ -notmatch '^WARNING:') } | Select-Object -First 20)) { Write-Note $l.TrimEnd() }
    Write-Lines $out.Err 'stderr: ' 10
    return ''
}

function Write-Lines([string]$text, [string]$prefix = '', [int]$max = 0) {
    if (-not $text) { return }
    $lines = $text -split "`r?`n" | Where-Object { $_.Trim() }
    if ($max -gt 0) { $lines = $lines | Select-Object -Last $max }
    foreach ($l in $lines) { Write-Note "$prefix$($l.TrimEnd())" }
}

# ---------------------------------------------------------------- 0 环境

Write-Host ''
Write-Host '健康数据 · 覆盖安装' -ForegroundColor Cyan
Write-Host ("  仓库 {0}" -f $RepoRoot) -ForegroundColor DarkGray
Write-Host ''

try {

Start-Stage '环境'
$adbCmd = Get-Command adb -ErrorAction SilentlyContinue
if (-not $adbCmd) { Stop-Run 'PATH 里找不到 adb' }
$script:AdbExe = $adbCmd.Source
Write-Note "adb         $($script:AdbExe)"

$sdk = $null
$lp = Join-Path $RepoRoot 'local.properties'
if (Test-Path $lp) {
    $m = Select-String -Path $lp -Pattern '^\s*sdk\.dir\s*=\s*(.+)$' | Select-Object -First 1
    if ($m) { $sdk = ($m.Matches[0].Groups[1].Value.Trim() -replace '\\\\', '\') -replace '\\:', ':' }
}
if (-not $sdk -or -not (Test-Path $sdk)) { $sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
Write-Note "sdk.dir     $sdk"

$btRoot = Join-Path $sdk 'build-tools'
if (-not (Test-Path $btRoot)) { Stop-Run "build-tools 目录不存在：$btRoot" }
$bt = Get-ChildItem $btRoot -Directory |
      Where-Object { $_.Name -match '^\d+(\.\d+)*' } |
      Sort-Object { [version]($_.Name -replace '[^0-9.].*$', '') } -Descending |
      Select-Object -First 1
if (-not $bt) { Stop-Run "build-tools 下没有可用版本：$btRoot" }
$script:Aapt2     = Join-Path $bt.FullName 'aapt2.exe'
$script:ApkSigner = Join-Path $bt.FullName 'apksigner.bat'
foreach ($t in @($script:Aapt2, $script:ApkSigner)) {
    if (-not (Test-Path $t)) { Stop-Run "缺少构建工具：$t" }
}
Write-Note "build-tools $($bt.Name)"

$sq = Join-Path (Split-Path -Parent $script:AdbExe) 'sqlite3.exe'
if (-not (Test-Path $sq)) {
    $c = Get-Command sqlite3 -ErrorAction SilentlyContinue
    if ($c) { $sq = $c.Source } else { $sq = $null }
}
$script:Sqlite3 = $sq
if ($sq) { Write-Note "sqlite3     $sq" }

$script:TarExe = Join-Path $env:SystemRoot 'System32\tar.exe'
if (-not (Test-Path $script:TarExe)) { $script:TarExe = $null }

$script:Work = Join-Path $env:TEMP ('gb-install-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Path $script:Work -Force | Out-Null
Write-Note "临时目录    $($script:Work)"

$envSummary = "adb / build-tools $($bt.Name) 就绪"
if (-not $sq) { $envSummary += '（无 sqlite3，将跳过 integrity_check）' }
Complete-Stage $envSummary
if (-not $sq) { Write-Warn 'sqlite3 不可用，数据库 integrity_check 会被跳过' }

# ---------------------------------------------------------------- 1 构建

if ($Build) {
    Start-Stage '构建'
    $gw = Join-Path $RepoRoot 'gradlew.bat'
    if (-not (Test-Path $gw)) { Stop-Run "找不到 $gw" }
    Push-Location $RepoRoot
    try {
        $r = Invoke-Native -File $gw -Arguments @('assembleMainlineDebug', '--no-parallel')
    } finally { Pop-Location }
    Write-Lines $r.Out '' 40
    Write-Lines $r.Err 'stderr: ' 20
    if ($r.ExitCode -ne 0) { Stop-Run "gradle 退出码 $($r.ExitCode)" }
    Complete-Stage 'assembleMainlineDebug 通过'
}

# ---------------------------------------------------------------- 2 APK 身份

Start-Stage 'APK'
if (-not (Test-Path $Apk)) { Stop-Run "APK 不存在：$Apk（需要先构建？加 -Build）" }
$ApkItem = Get-Item $Apk
$ApkSha  = (Get-FileHash $ApkItem.FullName -Algorithm SHA256).Hash
Write-Note "路径     $($ApkItem.FullName)"
Write-Note ("大小     {0:N0} bytes" -f $ApkItem.Length)
Write-Note "构建时间 $($ApkItem.LastWriteTime.ToString('yyyy-MM-dd HH:mm:ss'))"
Write-Note "SHA-256  $ApkSha"

$badge = Invoke-Native -File $script:Aapt2 -Arguments @('dump', 'badging', $ApkItem.FullName)
if ($badge.ExitCode -ne 0) { Write-Lines $badge.Err; Stop-Run 'aapt2 dump badging 失败' }
foreach ($l in ($badge.Out -split "`r?`n")) {
    # application-label-<locale> 有上百条，只留默认的那条
    if ($l -match "^(package:|application-label:|launchable-activity:)") { Write-Note $l.TrimEnd() }
}

$pkgName = ''; $vCode = ''; $vName = ''
if ($badge.Out -match "package: name='([^']+)' versionCode='([^']*)' versionName='([^']*)'") {
    $pkgName = $Matches[1]; $vCode = $Matches[2]; $vName = $Matches[3]
}
$appLabel = ''
if ($badge.Out -match "(?m)^application-label:'([^']*)'") { $appLabel = $Matches[1] }
$actLabel = ''
if ($badge.Out -match "launchable-activity: name='([^']+)'\s+label='([^']*)'") { $actLabel = $Matches[2] }

if ($pkgName -ne $PKG) { Stop-Run "包名是 '$pkgName'，不是 $PKG —— 绝不装到别的身份上" }
if ($appLabel -ne $WANT_LABEL) { Write-Warn "application-label 是 '$appLabel'，预期「$WANT_LABEL」" }
if ($actLabel -ne $WANT_LABEL) { Write-Warn "launcher label 是 '$actLabel'，预期「$WANT_LABEL」" }

$newCert = Get-CertSha256 $ApkItem.FullName
Write-Note "签名证书 $newCert"
if (-not $newCert) { Stop-Run 'apksigner 没能读出签名证书' }

# 源码状态：脏工作区意味着这个 APK 不只对应那个 commit，报告里必须说清
$srcState = ''
$gitCmd = Get-Command git -ErrorAction SilentlyContinue
if ($gitCmd) {
    Push-Location $RepoRoot
    try {
        $head  = (Invoke-Native -File $gitCmd.Source -Arguments @('rev-parse', '--short', 'HEAD')).Out.Trim()
        $dirty = @((Invoke-Native -File $gitCmd.Source -Arguments @('status', '--porcelain')).Out -split "`r?`n" |
                   Where-Object { $_.Trim() })
        if ($head) {
            $srcState = $head
            if ($dirty.Count -gt 0) { $srcState += "（工作区有 $($dirty.Count) 项未提交改动，APK 不只对应该 commit）" }
        }
    } finally { Pop-Location }
    Write-Note "源码状态 $srcState"
}
$script:Facts['src_state']   = $srcState
$script:Facts['apk_path']    = $ApkItem.FullName
$script:Facts['apk_size']    = $ApkItem.Length
$script:Facts['apk_sha256']  = $ApkSha
$script:Facts['apk_version'] = "versionCode $vCode / versionName $vName"
$script:Facts['apk_cert']    = $newCert
Complete-Stage "$PKG  v$vName($vCode)  「$appLabel」  $($ApkSha.Substring(0, 16))…"

# ---------------------------------------------------------------- 3 新鲜度

Start-Stage '新鲜度'
$srcNewest = $null
foreach ($p in @('app\src', 'app\build.gradle.kts', 'build.gradle.kts', 'gradle')) {
    $full = Join-Path $RepoRoot $p
    if (-not (Test-Path $full)) { continue }
    $n = Get-ChildItem $full -Recurse -File -ErrorAction SilentlyContinue |
         Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($n -and ((-not $srcNewest) -or ($n.LastWriteTime -gt $srcNewest.LastWriteTime))) { $srcNewest = $n }
}
if ($srcNewest) {
    Write-Note "最新源码 $($srcNewest.FullName)"
    Write-Note "修改时间 $($srcNewest.LastWriteTime.ToString('yyyy-MM-dd HH:mm:ss'))"
    if ($srcNewest.LastWriteTime -gt $ApkItem.LastWriteTime) {
        Complete-Stage '源码比 APK 新'
        Write-Warn '源码比 APK 新，这个 APK 可能不含最新改动（加 -Build 重新构建）'
    } else {
        Complete-Stage '源码不比 APK 新'
    }
} else {
    Complete-Stage '未找到可比对的源码时间戳'
}

# ---------------------------------------------------------------- 4 设备

Start-Stage '设备'
$dev = Invoke-Native -File $script:AdbExe -Arguments @('devices', '-l')
Write-Lines $dev.Out
$transports = @()
foreach ($line in ($dev.Out -split "`r?`n")) {
    if ($line -match '^List of devices') { continue }
    if ($line -match '^(.+?)\s+device(\s|$)') { $transports += $Matches[1].Trim() }
}
if ($transports.Count -eq 0) { Stop-Run '没有处于 device 状态的 adb 设备（手机没连上、没授权，或 adb 没连无线）' }

if ($Serial) {
    if ($transports -notcontains $Serial) { Stop-Run "指定的 serial '$Serial' 不在可用设备里" }
    $script:Serial = $Serial
    Write-Note "使用指定 serial $Serial"
} else {
    # 同一台手机可能同时有 TCP 与 mDNS 两条 transport，按 ro.serialno 去重
    $byHw = @{}
    foreach ($t in $transports) {
        $r = Invoke-Native -File $script:AdbExe -Arguments @('-s', $t, 'shell', 'getprop', 'ro.serialno')
        if ($r.ExitCode -ne 0 -or -not $r.Out.Trim()) { Write-Note "跳过无响应 transport：$t"; continue }
        $hw = $r.Out.Trim()
        if (-not $byHw.ContainsKey($hw)) { $byHw[$hw] = @() }
        $byHw[$hw] += $t
    }
    if ($byHw.Keys.Count -eq 0) { Stop-Run '所有 transport 都没有响应 getprop ro.serialno' }
    if ($byHw.Keys.Count -gt 1) {
        foreach ($k in $byHw.Keys) { Write-Note "ro.serialno=$k -> $($byHw[$k] -join ', ')" }
        Stop-Run "接了 $($byHw.Keys.Count) 台不同的手机，请用 -Serial 指定要装哪台"
    }
    $hw = @($byHw.Keys)[0]
    $cands = @($byHw[$hw])
    $pick = $cands | Where-Object { $_ -match '^\d+\.\d+\.\d+\.\d+:\d+$' } | Select-Object -First 1
    if (-not $pick) { $pick = $cands[0] }
    $script:Serial = $pick
    Write-Note "ro.serialno=$hw，transport $($cands.Count) 条（$($cands -join ' | ')），选用 $pick"
}

$model   = (Invoke-Shell 'getprop ro.product.model').Trim()
$android = (Invoke-Shell 'getprop ro.build.version.release').Trim()
Write-Note "机型 $model / Android $android / serial $($script:Serial)"

$dump = Invoke-Shell "dumpsys package $PKG"
$instVCode = ''; $instVName = ''; $instUpdated = ''
if ($dump -match 'versionCode=(\d+)')   { $instVCode   = $Matches[1] }
if ($dump -match 'versionName=(\S+)')   { $instVName   = $Matches[1] }
if ($dump -match 'lastUpdateTime=(.+)') { $instUpdated = $Matches[1].Trim() }

$instPath = ''
$pmPath = (Invoke-Shell "pm path $PKG").Trim()
if ($pmPath -match 'package:(.+)') { $instPath = $Matches[1].Trim() }

$preSha = ''
if (-not $instPath) {
    Complete-Stage "$model  $PKG 尚未安装"
    Write-Warn "$PKG 当前未安装 —— 这是首次安装，没有可回退的旧 APK"
} else {
    $preSha = ((Invoke-Shell "sha256sum '$instPath'").Trim() -split '\s+')[0]
    Write-Note "已装 v$instVName($instVCode)  lastUpdateTime $instUpdated"
    Write-Note "已装路径 $instPath"
    Write-Note "已装 SHA-256 $preSha"
}

# 安装前的设置快照，安装后逐项比对
$prefsFile = "shared_prefs/$($PKG)_preferences.xml"
$WATCH_KEYS = @(
    'health_connect_enabled', 'health_connect_sync_on_event', 'health_connect_devices_multiselect',
    'health_connect_last_granted_permissions', 'last_device_addresses',
    'auto_export_enabled', 'auto_export_location', 'auto_export_interval', 'auto_export_on_sync',
    'auto_fetch_enabled'
)
function Get-PrefSnapshot {
    $xml = (Invoke-Adb -Arguments @('exec-out', "run-as $PKG cat $prefsFile")).Out
    $hc  = (Invoke-Adb -Arguments @('exec-out', "run-as $PKG cat shared_prefs/health_connect_settings.xml")).Out
    $snap = [ordered]@{}
    foreach ($k in $WATCH_KEYS) {
        $esc = [regex]::Escape($k)
        $v = '(缺失)'
        if ($xml -match "name=`"$esc`"\s+value=`"([^`"]*)`"") { $v = $Matches[1] }
        elseif ($xml -match "name=`"$esc`"\s*>([^<]*)<") { $v = $Matches[1] }
        $snap[$k] = $v
    }
    $v = '(缺失)'
    if ($hc -match 'name="health_connect_initial_sync_start_ts"\s+value="([^"]*)"') { $v = $Matches[1] }
    $snap['health_connect_initial_sync_start_ts'] = $v
    $snap['databases/Gadgetbridge 大小'] = (Invoke-Shell "run-as $PKG stat -c %s databases/Gadgetbridge").Trim()
    $snap
}
$preSnap = $null
if ($instPath) {
    $preSnap = Get-PrefSnapshot
    foreach ($k in $preSnap.Keys) { Write-Note ("pre  {0,-42} {1}" -f $k, $preSnap[$k]) }
    if ($preSnap['databases/Gadgetbridge 大小'] -notmatch '^\d+$') {
        Stop-Run 'run-as 读不到应用私有数据，无法做安装前快照与备份'
    }
    Complete-Stage "$model  已装 v$instVName($instVCode)  $($preSha.Substring(0, 16))…"
}

$script:Facts['device']      = "$model / Android $android / $($script:Serial)"
$script:Facts['pre_version'] = "v$instVName($instVCode)"
$script:Facts['pre_sha256']  = $preSha

if ($preSha -and ($preSha.ToLower() -eq $ApkSha.ToLower())) {
    if (-not $Force) {
        Write-Host ''
        Write-Host '  设备上已经是同一个 APK（哈希一致），无需安装。要强制重装加 -Force。' -ForegroundColor Yellow
        Write-Host ''
        exit 0
    }
    Start-Stage '设备'
    Write-Warn '本地 APK 与已装哈希相同，因 -Force 继续'
}

# ---------------------------------------------------------------- 5 备份

Start-Stage '备份'
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$script:BackupDir = Join-Path $BackupRoot $stamp
if (Test-Path $script:BackupDir) { Stop-Run "备份目录已存在，拒绝覆盖：$($script:BackupDir)" }
New-Item -ItemType Directory -Path $script:BackupDir -Force | Out-Null
Write-Note "目录 $($script:BackupDir)"

$backupItems = @()

# 5a 旧 APK（回退目标）
if ($instPath) {
    $oldApk = Join-Path $script:BackupDir 'installed-old.apk'
    $null = Invoke-Adb -Arguments @('exec-out', "cat '$instPath'") -OutFile $oldApk
    if (-not (Test-Path $oldApk)) { Stop-Run '旧 APK 没有拉下来' }
    $oldSize = (Get-Item $oldApk).Length
    $oldSha  = (Get-FileHash $oldApk -Algorithm SHA256).Hash
    Write-Note ("installed-old.apk {0:N0} bytes  SHA-256 {1}" -f $oldSize, $oldSha)
    if ($oldSha.ToLower() -ne $preSha.ToLower()) {
        Write-Note "设备端 $preSha"
        Stop-Run '拉下来的旧 APK 哈希与设备端不一致，备份不可信'
    }
    $oldCert = Get-CertSha256 $oldApk
    Write-Note "旧 APK 签名证书 $oldCert"
    if ($oldCert -and $newCert -and ($oldCert -ne $newCert)) {
        Stop-Run "签名证书不同（旧 $oldCert / 新 $newCert），覆盖安装必然失败，且不得靠卸载解决"
    }
    $backupItems += [pscustomobject]@{ Name = 'installed-old.apk'; Size = $oldSize; Extra = "SHA-256 ``$oldSha``，签名证书 ``$oldCert``" }
    $script:Facts['rollback_apk'] = $oldApk
}

# 5b 应用私有数据
$tar = Join-Path $script:BackupDir 'appdata.tar'
$r = Invoke-Adb -Arguments @('exec-out', "run-as $PKG tar -c databases shared_prefs files no_backup 2>/dev/null") -OutFile $tar
if (-not (Test-Path $tar) -or (Get-Item $tar).Length -lt 1024) {
    Write-Lines $r.Err 'stderr: '
    Stop-Run 'appdata.tar 为空或过小，run-as 备份失败'
}
$tarSize = (Get-Item $tar).Length
$tarEntries = @()
if ($script:TarExe) {
    $t = Invoke-Native -File $script:TarExe -Arguments @('-tf', $tar)
    $tarEntries = @($t.Out -split "`r?`n" | ForEach-Object { $_.Trim() } | Where-Object { $_ -and ($_ -notmatch '/$') })
}
Write-Note ("appdata.tar {0:N0} bytes，{1} 个文件" -f $tarSize, $tarEntries.Count)
foreach ($f in $tarEntries) { Write-Note "  tar: $f" }

$devFiles = @((Invoke-Adb -Arguments @('exec-out', "run-as $PKG find databases shared_prefs files no_backup -type f 2>/dev/null")).Out `
              -split "`r?`n" | ForEach-Object { $_.Trim() } | Where-Object { $_ })
if ($script:TarExe -and $devFiles.Count -gt 0) {
    $missing = @()
    foreach ($f in $devFiles) {
        if ($f -match '(-journal|-wal|-shm)$') { continue }   # 瞬时文件，可能在 tar 之后才出现
        if ($tarEntries -notcontains $f) { $missing += $f }
    }
    if ($missing.Count -gt 0) {
        foreach ($f in $missing) { Write-Note "缺失: $f" }
        Stop-Run "手机上有 $($missing.Count) 个私有文件没进备份"
    }
    Write-Note "与手机私有目录逐项一致（瞬时的 -journal/-wal/-shm 不计）"
    $extra = "$($tarEntries.Count) 项，与设备私有目录逐项一致"
} else {
    $extra = "$($tarEntries.Count) 项"
}
# tar 是从外部顺序复制正在被写的文件，理论上能抓到撕裂状态。把私有库连同它的 journal
# 一起解出来校验——SQLite 会先做 journal 回滚，得到的正是回退时真正会拿到的那个状态。
# 这份是回退时最要紧的文件，坏了必须在安装前就知道。
$privVerdict = ''
if ($script:TarExe -and $script:Sqlite3) {
    $members = @($tarEntries | Where-Object { $_ -match '^databases/Gadgetbridge(-journal|-wal|-shm)?$' })
    if ($members.Count -eq 0) {
        Stop-Run '备份里没有 databases/Gadgetbridge，私有库没进 tar'
    }
    $dbTmp = Join-Path $script:Work 'privdb'
    New-Item -ItemType Directory -Path $dbTmp -Force | Out-Null
    $x = Invoke-Native -File $script:TarExe -Arguments (@('-xf', $tar, '-C', $dbTmp) + $members)
    if ($x.ExitCode -ne 0) { Write-Lines $x.Err 'stderr: '; Stop-Run '从 appdata.tar 解出私有库失败' }
    $priv = Join-Path $dbTmp 'databases\Gadgetbridge'
    if (-not (Test-Path $priv)) { Stop-Run "解出后找不到 $priv" }
    Write-Note ("私有库 解出 $($members.Count) 个文件（$($members -join '、')）")
    $q = Invoke-Native -File $script:Sqlite3 -Arguments @($priv, 'PRAGMA integrity_check;')
    $privCheck = $q.Out.Trim()
    if ($privCheck -ne 'ok') {
        Write-Lines $q.Out
        Write-Lines $q.Err 'stderr: '
        Stop-Run "备份中的私有数据库 integrity_check = $privCheck —— 这份坏了就没有可信回退点"
    }
    $privVerdict = 'integrity_check = ok'
    $c = Invoke-Native -File $script:Sqlite3 -Arguments @($priv, 'SELECT COUNT(*) FROM HUAWEI_ACTIVITY_SAMPLE;')
    if ($c.ExitCode -eq 0 -and $c.Out.Trim() -match '^\d+$') {
        $privVerdict += "，HUAWEI_ACTIVITY_SAMPLE $('{0:N0}' -f [int64]$c.Out.Trim()) 行"
    }
    Write-Note "私有库 $privVerdict"
    Remove-Item $dbTmp -Recurse -Force -ErrorAction SilentlyContinue
} else {
    Write-Warn '缺少 tar 或 sqlite3，备份中的私有数据库未校验'
}
if ($privVerdict) { $extra += "；私有库 $privVerdict" }
$backupItems += [pscustomobject]@{ Name = 'appdata.tar'; Size = $tarSize; Extra = $extra }

# 5c 导出快照：先拉到 ASCII 临时路径做 sqlite 检查，通过后再移进备份目录
function Backup-Db([string]$remoteName, [string]$localName) {
    $devSize = (Invoke-Shell "stat -c %s '$EXPORT_DIR/$remoteName' 2>/dev/null").Trim()
    if ($devSize -notmatch '^\d+$') {
        Write-Note "$remoteName 在设备上不存在，跳过"
        return $null
    }
    $tmp = Join-Path $script:Work $localName
    $null = Invoke-Adb -Arguments @('exec-out', "cat '$EXPORT_DIR/$remoteName'") -OutFile $tmp
    if (-not (Test-Path $tmp)) { Stop-Run "$remoteName 没有拉下来" }
    $size = (Get-Item $tmp).Length
    if ($size -ne [int64]$devSize) { Stop-Run "$remoteName 大小不符：本地 $size / 设备 $devSize" }
    $integrity = 'n/a'
    $rows = ''
    if ($script:Sqlite3) {
        $q = Invoke-Native -File $script:Sqlite3 -Arguments @($tmp, 'PRAGMA integrity_check;')
        $integrity = $q.Out.Trim()
        if ($integrity -ne 'ok') { Write-Lines $q.Err; Stop-Run "$remoteName integrity_check = $integrity" }
        $tbl = ''
        if ($remoteName -eq $HUAWEI_DB) { $tbl = 'HUAWEI_ACTIVITY_SAMPLE' }
        if ($remoteName -eq $XIAOMI_DB) { $tbl = 'XIAOMI_ACTIVITY_SAMPLE' }
        if ($tbl) {
            $c = Invoke-Native -File $script:Sqlite3 -Arguments @($tmp, "SELECT COUNT(*) FROM $tbl;")
            if ($c.ExitCode -eq 0 -and $c.Out.Trim() -match '^\d+$') { $rows = "$tbl $('{0:N0}' -f [int64]$c.Out.Trim()) 行" }
        }
    }
    Move-Item $tmp (Join-Path $script:BackupDir $localName) -Force
    Write-Note ("{0} {1:N0} bytes  integrity_check={2}  {3}" -f $localName, $size, $integrity, $rows)
    $ex = "integrity_check = $integrity"
    if ($rows) { $ex += "，$rows" }
    [pscustomobject]@{ Name = $localName; Size = $size; Extra = $ex }
}
$b = Backup-Db $HUAWEI_DB 'Gadgetbridge-huawei.db'
if ($b) { $backupItems += $b } else { Write-Warn "$HUAWEI_DB 不在 $EXPORT_DIR，未备份（自动导出可能未开启）" }
$b = Backup-Db $XIAOMI_DB 'Gadgetbridge-xiaomi-snapshot.db'
if ($b) { $backupItems += $b } else { Write-Warn "$XIAOMI_DB 不在 $EXPORT_DIR，未备份（旧小米快照缺失）" }

$manifest = @()
$manifest += "# 升级前备份 $stamp"
$manifest += "设备: $model / Android $android / $($script:Serial)"
$manifest += "包名: $PKG"
$manifest += "安装前: v$instVName($instVCode)  SHA-256 $preSha"
$manifest += "待装:   v$vName($vCode)  SHA-256 $ApkSha"
$manifest += ''
foreach ($i in $backupItems) { $manifest += ("{0}  {1:N0} bytes  {2}" -f $i.Name, $i.Size, ($i.Extra -replace '`', '')) }
($manifest -join "`r`n") | Set-Content (Join-Path $script:BackupDir 'manifest.txt') -Encoding UTF8
$script:Facts['backup_dir'] = $script:BackupDir
Complete-Stage "$($backupItems.Count) 项已核对 -> $stamp"

if ($DryRun) {
    ($script:Log -join "`r`n") | Set-Content (Join-Path $script:BackupDir 'install.log') -Encoding UTF8
    Write-Host ''
    Write-Host '  -DryRun：核对与备份完成，按要求停在安装前，手机未被改动。' -ForegroundColor Cyan
    Write-Host "  备份目录 $($script:BackupDir)"
    Write-Host ''
    exit 0
}

# ---------------------------------------------------------------- 6 安装

Start-Stage '安装'
$devTimeBefore = (Invoke-Shell "date '+%m-%d %H:%M:%S.000'").Trim()
Write-Note "安装前设备时间 $devTimeBefore"
$r = Invoke-Adb -Arguments @('install', '-r', '--no-streaming', $ApkItem.FullName)
Write-Note "adb install 退出码 $($r.ExitCode)"
Write-Lines $r.Out
Write-Lines $r.Err 'stderr: '
if ($r.ExitCode -ne 0 -or (($r.Out + $r.Err) -notmatch 'Success')) {
    Stop-Run 'adb install 失败 —— 不得用卸载或 pm clear 绕过，先看上面的原因'
}
$script:Installed = $true
Complete-Stage 'adb install -r --no-streaming 成功'

Start-Stage '装后核对'
$newPath = ''
$pmPath2 = (Invoke-Shell "pm path $PKG").Trim()
if ($pmPath2 -match 'package:(.+)') { $newPath = $Matches[1].Trim() }
if (-not $newPath) { Stop-Run '装后 pm path 读不到包' }
$postSha = ((Invoke-Shell "sha256sum '$newPath'").Trim() -split '\s+')[0]
Write-Note "设备端路径   $newPath"
Write-Note "设备端 SHA-256 $postSha"
Write-Note "本地   SHA-256 $ApkSha"
if ($postSha.ToLower() -ne $ApkSha.ToLower()) {
    Stop-Run '设备上跑的不是刚才这个 APK —— 版本号答不了这个问题，只有哈希能答'
}
$dump2 = Invoke-Shell "dumpsys package $PKG"
$newUpdated = ''
if ($dump2 -match 'lastUpdateTime=(.+)') { $newUpdated = $Matches[1].Trim() }
Write-Note "lastUpdateTime $instUpdated -> $newUpdated"
$script:Facts['post_sha256'] = $postSha
$script:Facts['last_update'] = $newUpdated
Complete-Stage "设备端哈希与本地构建一致，lastUpdateTime=$newUpdated"
if ($instUpdated -and ($newUpdated -eq $instUpdated)) { Write-Warn 'lastUpdateTime 没有变化' }

# ---------------------------------------------------------------- 7 双冷启动

Start-Stage '冷启动'
$act = ''
$ra = (Invoke-Shell "cmd package resolve-activity --brief $PKG").Trim()
foreach ($l in ($ra -split "`r?`n")) { if ($l.Trim() -match "^$([regex]::Escape($PKG))/") { $act = $l.Trim() } }
if (-not $act) { $act = "$PKG/nodomain.freeyourgadget.gadgetbridge.activities.ControlCenterv2" }
$actShort = ($act -split '\.')[-1]
Write-Note "launcher activity $act"
$wake = (Invoke-Shell "dumpsys power | grep -m1 'mWakefulness='").Trim()
Write-Note "屏幕状态 $wake"

$boots = @()
for ($i = 1; $i -le 2; $i++) {
    Write-Note "--- 第 $i 次 force-stop -> 冷启动 ---"
    $null = Invoke-Shell "am force-stop $PKG"
    Start-Sleep -Seconds 2
    $start = Invoke-Shell "am start -W -n $act"
    Write-Lines $start '  '
    $status = ''
    if ($start -match '(?m)^Status:\s*(\S+)') { $status = $Matches[1] }
    $lstate = ''
    if ($start -match '(?m)^LaunchState:\s*(\S+)') { $lstate = $Matches[1] }
    Start-Sleep -Seconds 3
    $procId = (Invoke-Shell "pidof $PKG").Trim()
    # 灭屏（mWakefulness=Dozing）时没有 topResumedActivity=，但仍有 ResumedActivity:，
    # 两种写法都要认，否则会把正常启动误判为失败。
    $fgRaw = Invoke-Shell "dumpsys activity activities | grep -iE 'ResumedActivity'"
    $fgLines = @($fgRaw -split "`r?`n" | ForEach-Object { $_.Trim() } | Where-Object { $_ })
    $fg = ''
    foreach ($l in $fgLines) { if (($l -match [regex]::Escape($PKG)) -and (-not $fg)) { $fg = $l } }
    Write-Note "  PID  $procId"
    if ($fg) {
        Write-Note "  前台 $fg"
    } else {
        Write-Note "  前台 未命中本应用，当前 ResumedActivity："
        foreach ($l in $fgLines) { Write-Note "    $l" }
        Write-Note "  屏幕 $((Invoke-Shell "dumpsys power | grep -m1 'mWakefulness='").Trim())"
    }
    $ok = ($status -eq 'ok') -and $procId -and $fg
    $boots += [pscustomobject]@{ N = $i; Status = $status; State = $lstate; ProcId = $procId; Ok = $ok }
}
foreach ($b2 in $boots) {
    $verdict = 'ok'
    if (-not $b2.Ok) { $verdict = 'FAIL' }
    Write-Note ("第 {0} 次: Status={1} LaunchState={2} PID={3} -> {4}" -f $b2.N, $b2.Status, $b2.State, $b2.ProcId, $verdict)
}
$script:Facts['boots'] = (($boots | ForEach-Object { "第 $($_.N) 次 Status=$($_.Status) PID=$($_.ProcId)" }) -join '；')
if (@($boots | Where-Object { -not $_.Ok }).Count -gt 0) { Stop-Run '冷启动验收未通过' }
$script:Facts['boots'] += "（$wake）"
Complete-Stage "两次均 Status: ok，PID $($boots[0].ProcId) / $($boots[1].ProcId)，前台为 $actShort"
if ($boots[0].ProcId -and ($boots[0].ProcId -eq $boots[1].ProcId)) { Write-Warn '两次 PID 相同，第二次可能不是真正的冷启动' }

Start-Stage '崩溃'
$crash = (Invoke-Adb -Arguments @('logcat', '-b', 'crash', '-d', '-t', $devTimeBefore)).Out
$mainE = (Invoke-Adb -Arguments @('logcat', '-b', 'main', '-d', '-t', $devTimeBefore, '*:E')).Out
$crashLines = @($crash -split "`r?`n" | Where-Object { $_.Trim() })
Write-Note "crash 缓冲区 $($crashLines.Count) 行（自 $devTimeBefore）"
foreach ($l in ($crashLines | Select-Object -First 60)) { Write-Note "crash: $l" }
$hits = @(($crash + "`n" + $mainE) -split "`r?`n" |
          Where-Object { ($_ -match 'AndroidRuntime|FATAL EXCEPTION') -and ($_ -match 'gadgetbridge') })
foreach ($l in $hits) { Write-Note "命中: $l" }
if ($hits.Count -gt 0) { Stop-Run "安装后时间窗内有 $($hits.Count) 条本应用的 AndroidRuntime / FATAL" }
$script:Facts['crash'] = "安装后时间窗（自 $devTimeBefore）内 crash 与 main 缓冲区均无本应用 ``AndroidRuntime`` / ``FATAL``"
Complete-Stage '安装后时间窗内无本应用崩溃'

# ---------------------------------------------------------------- 8 数据与设置保留

Start-Stage '数据保留'
if ($preSnap) {
    $postSnap = Get-PrefSnapshot
    $changed = @()
    foreach ($k in $preSnap.Keys) {
        $a = $preSnap[$k]
        $b3 = $postSnap[$k]
        Write-Note ("{0,-42} {1}  ->  {2}" -f $k, $a, $b3)
        if ($a -ne $b3) { $changed += "$k：$a -> $b3" }
    }
    $lost = @($changed | Where-Object { $_ -match '->\s*\(缺失\)$' })
    if ($lost.Count -gt 0) {
        foreach ($l in $lost) { Write-Note "丢失: $l" }
        Stop-Run "有 $($lost.Count) 项设置在安装后丢失"
    }
    if ($changed.Count -eq 0) {
        $script:Facts['prefs'] = '监视的全部设置与私有数据库大小逐项不变'
        Complete-Stage $script:Facts['prefs']
    } else {
        $script:Facts['prefs'] = "$($changed.Count) 项有变化：$($changed -join '；')"
        Complete-Stage "$($changed.Count) 项有变化，无一丢失"
        foreach ($c in $changed) { Write-Warn "变化：$c" }
    }
} else {
    Complete-Stage '首次安装，无可比对的安装前快照'
}

# ---------------------------------------------------------------- 9 报告

Start-Stage '报告'
$dur = [int]((Get-Date) - $script:Started).TotalSeconds
$today = Get-Date -Format 'yyyy-MM-dd'
$rep = @()
$rep += "## $today — 覆盖安装「健康数据」"
$rep += ''
$rep += "- 设备：$($script:Facts['device'])"
if ($script:Facts['src_state']) { $rep += "- 源码：$($script:Facts['src_state'])" }
$rep += "- APK：``$($script:Facts['apk_path'])``"
$rep += ("  $($script:Facts['apk_version'])，{0:N0} bytes，SHA-256 ``{1}``" -f $script:Facts['apk_size'], $script:Facts['apk_sha256'])
$rep += "  签名证书 ``$($script:Facts['apk_cert'])``"
$rep += "- 升级前备份：``$($script:Facts['backup_dir'])``"
foreach ($i in $backupItems) { $rep += ("  - ``{0}`` {1:N0} bytes，{2}" -f $i.Name, $i.Size, $i.Extra) }
if ($script:Facts['pre_sha256']) {
    $rep += "- 安装前为 $($script:Facts['pre_version'])，SHA-256 ``$($script:Facts['pre_sha256'])``"
}
$rep += "- ``adb install -r --no-streaming`` 成功；设备端 APK SHA-256 与本地构建一致，" +
        "``lastUpdateTime=$($script:Facts['last_update'])``"
$rep += "- 两次 ``force-stop`` → 冷启动：$($script:Facts['boots'])，前台均为 ``$actShort``"
$rep += "- $($script:Facts['crash'])"
if ($script:Facts['prefs']) { $rep += "- 数据与设置：$($script:Facts['prefs'])" }
if ($script:Warns.Count -gt 0) {
    $rep += '- 告警：'
    foreach ($w in $script:Warns) { $rep += "  - $w" }
}
$rep += '- 未发生回退。'
$rep += '- **本次验收的边界**：以上只证明这个 APK 装上了、连续两次冷启动能到达并保持前台、' +
        '安装时间窗内本应用无崩溃、监视的设置与私有库大小未丢。' +
        '**不证明**本次代码改动的功能行为——那需要针对该改动的实机验收，另行记录。'
$rep += ''
$repPath = Join-Path $script:BackupDir 'report.md'
($rep -join "`r`n") | Set-Content $repPath -Encoding UTF8
($script:Log -join "`r`n") | Set-Content (Join-Path $script:BackupDir 'install.log') -Encoding UTF8
Complete-Stage 'report.md / install.log 已写入备份目录'

Write-Host ''
Write-Host "全部通过，用时 ${dur}s" -ForegroundColor Green
if ($script:Warns.Count -gt 0) {
    Write-Host "有 $($script:Warns.Count) 条告警：" -ForegroundColor Yellow
    foreach ($w in $script:Warns) { Write-Host "  - $w" -ForegroundColor Yellow }
}
Write-Host ''
Write-Host '可直接追加到 docs/iteration-log.md 的段落：' -ForegroundColor Cyan
Write-Host ''
foreach ($l in $rep) { Write-Host "  $l" }
Write-Host "报告 $repPath" -ForegroundColor DarkGray
Write-Host ''

}
catch {
    $msg = $_.Exception.Message -replace '^STAGE_FAILED:\s*', ''
    Write-Host ''
    Write-Host "中止：$msg" -ForegroundColor Red
    if ($script:BackupDir -and (Test-Path $script:BackupDir)) {
        ($script:Log -join "`r`n") | Set-Content (Join-Path $script:BackupDir 'install.log') -Encoding UTF8
        Write-Host "本次备份与完整日志保留在 $($script:BackupDir)" -ForegroundColor DarkGray
    }
    if ($script:Installed -and $script:Facts['rollback_apk']) {
        $rb = $script:Facts['rollback_apk']
        if ($AutoRollback) {
            Write-Host ''
            Write-Host '  -AutoRollback：用备份的旧 APK 覆盖回退……' -ForegroundColor Yellow
            $r = Invoke-Adb -Arguments @('install', '-r', '--no-streaming', $rb)
            Write-Host "  $($r.Out)"
            if ($r.ExitCode -eq 0 -and (($r.Out + $r.Err) -match 'Success')) {
                $back = ((Invoke-Shell "sha256sum `$(pm path $PKG | sed 's/^package://')").Trim() -split '\s+')[0]
                Write-Host "  回退完成，设备端 SHA-256 $back" -ForegroundColor Green
                Write-Host '  请手动冷启动一次确认可用。' -ForegroundColor Yellow
            } else {
                Write-Host '  回退失败，手机当前处于新版本，请立即人工处理。' -ForegroundColor Red
            }
        } else {
            Write-Host ''
            Write-Host '  已安装但验收未过。回退命令（不要卸载，不要 pm clear）：' -ForegroundColor Yellow
            Write-Host ''
            Write-Host "  adb -s $($script:Serial) install -r --no-streaming `"$rb`"" -ForegroundColor White
            Write-Host ''
            Write-Host '  下次可加 -AutoRollback 让脚本自动回退。' -ForegroundColor DarkGray
        }
    } elseif (-not $script:Installed) {
        Write-Host '手机未被改动（尚未进入安装步骤）。' -ForegroundColor DarkGray
    }
    Write-Host ''
    exit 1
}
