param(
    [string]$Repo = (Get-Location).Path,
    [string]$Branch = "stage9/regression-freeze",
    [string]$Apk = "",
    [string]$AdbPath = "",
    [string]$Serial = "",
    [string]$EvidenceDir = "",
    [switch]$SkipSync,
    [switch]$Install,
    [switch]$RunGradle
)

# 快速真机验收入口：不 reset、不 clean、不 force-push、不清应用数据、不记录 key。
$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
function Stop-Test([string]$Message) { Write-Error $Message; exit 2 }
function Run-Git([string[]]$CommandArgs) {
    $previousEap = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $out = & git -C $script:repo @CommandArgs 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousEap
    }
    if ($exitCode -ne 0) { Stop-Test ("git " + ($CommandArgs -join " ") + " 失败：" + [Environment]::NewLine + ($out -join [Environment]::NewLine)) }
    return @($out)
}
function Invoke-Adb([string[]]$CommandArgs) {
    $all = @($script:adbPrefix) + @($CommandArgs)
    $previousEap = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $out = & $script:adbPath @all 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousEap
    }
    if ($exitCode -ne 0) { Stop-Test ("adb " + ($all -join " ") + " 失败：" + [Environment]::NewLine + ($out -join [Environment]::NewLine)) }
    return @($out)
}
function Save-Evidence([string]$Name, [object]$Value) {
    $path = Join-Path $script:evidenceDir $Name
    if ($Value -is [byte[]]) { [IO.File]::WriteAllBytes($path, $Value) }
    else { [IO.File]::WriteAllText($path, (($Value | Out-String).Trim() + [Environment]::NewLine), [Text.UTF8Encoding]::new($false)) }
}
function Need-File([string]$Path, [string]$Description) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { Stop-Test ("缺少" + $Description + "：" + $Path) }
}

$script:repo = (Resolve-Path -LiteralPath $Repo).Path
if (-not (Test-Path -LiteralPath (Join-Path $script:repo ".git"))) { Stop-Test "缺少 Git 工作区" }
if ([string]::IsNullOrWhiteSpace($EvidenceDir)) { $EvidenceDir = Join-Path $env:TEMP ("focusflow-dsh-stage9-" + (Get-Date -Format "yyyyMMdd-HHmmss")) }
$script:evidenceDir = (New-Item -ItemType Directory -Force -Path $EvidenceDir).FullName
$report = [ordered]@{ startedAt = (Get-Date).ToString("o"); branch = $Branch; install = [bool]$Install; gradle = [bool]$RunGradle }

# A. 工作区同步：dirty 时立即停止；只做 fast-forward。
$dirty = @(Run-Git @("status", "--porcelain"))
if ($dirty.Count -gt 0) { Stop-Test ("工作区有未提交改动，拒绝自动切换/同步：" + [Environment]::NewLine + ($dirty -join [Environment]::NewLine)) }
if (-not $SkipSync) {
    Run-Git @("fetch", "origin", $Branch, "--prune") | Out-Null
    $current = ((Run-Git @("branch", "--show-current")) -join "").Trim()
    if ($current -ne $Branch) {
        & git -C $script:repo show-ref --verify --quiet ("refs/heads/" + $Branch)
        if ($LASTEXITCODE -eq 0) { Run-Git @("switch", $Branch) | Out-Null }
        else { Run-Git @("switch", "-c", $Branch, "--track", ("origin/" + $Branch)) | Out-Null }
    }
    Run-Git @("merge", "--ff-only", ("origin/" + $Branch)) | Out-Null
}
$remote = ((Run-Git @("rev-parse", ("origin/" + $Branch))) -join "").Trim()
$local = ((Run-Git @("rev-parse", "HEAD")) -join "").Trim()
if ($remote -ne $local) { Stop-Test ("本地 HEAD 与 origin/" + $Branch + " 不一致：local=" + $local + " remote=" + $remote) }
Run-Git @("diff", "--check") | Out-Null
$report.remoteHead = $remote
$report.localHead = $local
$report.headLine = ((Run-Git @("show", "-s", "--format=%h %s", "HEAD")) -join "").Trim()
Save-Evidence "git-status.txt" (Run-Git @("status", "--short", "--branch"))
Save-Evidence "git-head.txt" $report.headLine

# B. 版本和凭据备份排除静态检查。
$gradleText = Get-Content (Join-Path $script:repo "app/build.gradle.kts") -Raw -Encoding UTF8
$vm = [regex]::Match($gradleText, 'versionName\s*=\s*"([^"]+)"')
$cm = [regex]::Match($gradleText, 'versionCode\s*=\s*(\d+)')
if (-not $vm.Success -or -not $cm.Success) { Stop-Test "无法读取 Gradle 版本" }
$report.versionName = $vm.Groups[1].Value
$report.versionCode = [int]$cm.Groups[1].Value
$requiredFiles = @("gradlew.bat","app/src/main/AndroidManifest.xml","app/src/main/res/xml/backup_rules.xml","app/src/main/res/xml/data_extraction_rules.xml")
foreach ($f in $requiredFiles) { Need-File (Join-Path $script:repo $f) $f }
foreach ($f in @("app/src/main/res/xml/backup_rules.xml","app/src/main/res/xml/data_extraction_rules.xml")) {
    if ((Get-Content (Join-Path $script:repo $f) -Raw -Encoding UTF8) -notmatch "focusflow_credentials\.xml") { Stop-Test ("凭据备份排除缺失：" + $f) }
}
Save-Evidence "static-version.txt" @("versionName=$($report.versionName)","versionCode=$($report.versionCode)","head=$remote","credentialsExcluded=True")

# C. 可选聚焦单测；默认关闭，快速批次不等待 Gradle。
if ($RunGradle) {
    $log = Join-Path $script:evidenceDir "gradle-test.log"
    $previousEap = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $gradleOutput = & (Join-Path $script:repo "gradlew.bat") --no-daemon :app:testDebugUnitTest --rerun-tasks 2>&1
        $gradleExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousEap
    }
    [IO.File]::WriteAllText($log, (($gradleOutput | Out-String).Trim() + [Environment]::NewLine), [Text.UTF8Encoding]::new($false))
    if ($gradleExitCode -ne 0) { Stop-Test ("Debug 聚焦单测失败，详见 " + $log) }
}

# D. adb 设备和稳定签名 APK。
if ([string]::IsNullOrWhiteSpace($AdbPath)) {
    $AdbPath = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
    if (-not (Test-Path $AdbPath)) { $AdbPath = "adb" }
}
$script:adbPath = $AdbPath
$script:adbPrefix = if ([string]::IsNullOrWhiteSpace($Serial)) { @() } else { @("-s",$Serial) }
$devices = @(Invoke-Adb @("devices"))
$online = @($devices | Where-Object { $_ -match "^\S+\s+device\s*$" })
if ($online.Count -eq 0) { Stop-Test "没有 online adb 设备；先解锁手机并确认 USB 调试授权" }
if ([string]::IsNullOrWhiteSpace($Serial)) {
    if ($online.Count -ne 1) { Stop-Test ("多个 adb 设备，请传 -Serial：" + [Environment]::NewLine + ($online -join [Environment]::NewLine)) }
    $Serial = (($online[0] -split "\s+")[0]).Trim()
    $script:adbPrefix = @("-s",$Serial)
}
$report.serial = $Serial
$report.manufacturer = (Invoke-Adb @("shell","getprop","ro.product.manufacturer")) -join ""
$report.model = (Invoke-Adb @("shell","getprop","ro.product.model")) -join ""
$report.android = (Invoke-Adb @("shell","getprop","ro.build.version.release")) -join ""
Save-Evidence "adb-devices.txt" $devices
$deviceSize = (Invoke-Adb @("shell","wm","size")) -join " "
$deviceDensity = (Invoke-Adb @("shell","wm","density")) -join " "
$deviceFingerprint = (Invoke-Adb @("shell","getprop","ro.build.fingerprint")) -join ""
Save-Evidence "device-properties.txt" @(
    "serial=$Serial"
    "manufacturer=$($report.manufacturer)"
    "model=$($report.model)"
    "android=$($report.android)"
    "fingerprint=$deviceFingerprint"
    "size=$deviceSize"
    "density=$deviceDensity"
)

$apkPath = $Apk
if ([string]::IsNullOrWhiteSpace($apkPath)) {
    $apkPath = Join-Path $script:repo "app/build/outputs/apk/release/app-release.apk"
    if (-not (Test-Path $apkPath)) {
        $foundApk = Get-ChildItem $script:repo -Filter "FocusFlow-*.apk" -File -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($foundApk) { $apkPath = $foundApk.FullName }
    }
}
if ([string]::IsNullOrWhiteSpace($apkPath) -or -not (Test-Path $apkPath)) { Stop-Test "找不到 APK；请传 Run 551 稳定签名 APK 的 -Apk 路径" }
$apkPath = (Resolve-Path $apkPath).Path
$report.apk = $apkPath
$report.apkSha256 = (Get-FileHash $apkPath -Algorithm SHA256).Hash.ToLowerInvariant()
$bt = Join-Path $env:LOCALAPPDATA "Android\Sdk\build-tools"
$signer = Get-ChildItem $bt -Recurse -File -ErrorAction SilentlyContinue | Where-Object { $_.Name -in @("apksigner.bat","apksigner") } | Sort-Object FullName | Select-Object -Last 1
if (-not $signer) { Stop-Test "找不到 apksigner，拒绝把 APK 当成稳定包" }
$signLog = Join-Path $script:evidenceDir "apk-signing.txt"
$previousEap = $ErrorActionPreference
try {
    $ErrorActionPreference = "Continue"
    $signOutput = & $signer.FullName verify --verbose --print-certs $apkPath 2>&1
    $signExitCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $previousEap
}
[IO.File]::WriteAllText(
    $signLog,
    (($signOutput | Out-String).Trim() + [Environment]::NewLine),
    [Text.UTF8Encoding]::new($false)
)
if ($signExitCode -ne 0) { Stop-Test "APK 签名校验失败" }
if ((Get-Content $signLog -Raw -Encoding UTF8) -notmatch "650a17f2bbc6d3cf7ac436e3ce7d4cbc1381cfd29052d6a8e06e70361ef48e8e") {
    Stop-Test "APK 证书不是 FocusFlow 稳定证书，停止安装"
}
$report.signingFingerprint = "650a17f2bbc6d3cf7ac436e3ce7d4cbc1381cfd29052d6a8e06e70361ef48e8e"
if ($Install) {
    $installOut = @(Invoke-Adb @("install","-r",$apkPath))
    Save-Evidence "adb-install.txt" $installOut
    if (($installOut -join " ") -notmatch "Success") { Stop-Test "adb install 没有返回 Success" }
}

# E. 启动冒烟：不修改业务数据。
Invoke-Adb @("shell","am","force-stop","com.sakata.focusflow") | Out-Null
Invoke-Adb @("shell","monkey","-p","com.sakata.focusflow","1") | Out-Null
Start-Sleep 3
$top = Invoke-Adb @("shell","dumpsys","activity","activities")
Save-Evidence "activity-top.txt" $top
if (($top -join [Environment]::NewLine) -notmatch "com\.sakata\.focusflow") { Stop-Test "顶层 Activity 不属于 FocusFlow" }
Invoke-Adb @("shell","uiautomator","dump","/sdcard/focusflow-dsh-ui.xml") | Out-Null
Invoke-Adb @("pull","/sdcard/focusflow-dsh-ui.xml",(Join-Path $script:evidenceDir "ui-launch.xml")) | Out-Null
$uiPath = Join-Path $script:evidenceDir "ui-launch.xml"
Need-File $uiPath "UIAutomator XML"
$ui = Get-Content $uiPath -Raw -Encoding UTF8
foreach ($label in @("今日","日程","计划","设置")) { if ($ui -notmatch [regex]::Escape($label)) { Stop-Test ("启动 UI 缺少导航标签：" + $label) } }
Invoke-Adb @("shell","screencap","-p","/sdcard/focusflow-dsh-launch.png") | Out-Null
Invoke-Adb @("pull","/sdcard/focusflow-dsh-launch.png",(Join-Path $script:evidenceDir "launch.png")) | Out-Null
Save-Evidence "notification-appops.txt" (Invoke-Adb @("shell","cmd","appops","get","com.sakata.focusflow","POST_NOTIFICATION"))
$report.status = "passed"
$report.finishedAt = (Get-Date).ToString("o")
[IO.File]::WriteAllText((Join-Path $script:evidenceDir "report.json"),($report | ConvertTo-Json -Depth 5),[Text.Encoding]::UTF8)
Write-Host ("快速验收通过；证据目录：" + $script:evidenceDir)
Write-Host ("HEAD=" + $remote + " APK_SHA256=" + $report.apkSha256 + " DEVICE=" + $report.manufacturer + " " + $report.model + " Android " + $report.android)
