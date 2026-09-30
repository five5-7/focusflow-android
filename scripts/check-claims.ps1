# 提交前自检：把"文字与事实不符"交给机器查
#
# 为什么存在：2026-09-29 那一轮里，独立复核反复抓到同一类问题——文档里写的数字、行号、
# 状态词与实际代码/仓库不符（例如文档写"19 项"而测试有 21 项；同一个文件里一处写"已放行"、
# 另一处写"未放行"）。这类错不影响功能，但会让人（和下一个读文档的人）做出错误决定，
# 而且每次都要"改文字 → 重跑测试 → 重新提交"，白白花掉十几分钟。
#
# 用法：  pwsh -File D:\focusflow\.tools\check-claims.ps1
# 退出码：0 = 没发现问题；1 = 有发现（提交前应该先修）
#
# 检查项：
#   [1] 文档里"N 项测试"的说法 vs 被引用测试文件里真实的 @Test 个数
#   [2] 文档里提到的提交短哈希是否真的在仓库里
#   [3] 同一个文件里对同一功能同时出现"已放行/已落地"与"未放行/未做"（自相矛盾）
#   [4] 文档里 "文件名.kt:NNN" 的行号是否超出该文件实际行数（最容易过期的一类）

param(
    [string]$Repo = (Split-Path -Parent (Split-Path -Parent $PSCommandPath)),
    [string[]]$Docs = @(
        'docs\9.0-stage6-unified-scheduling-design.md',
        'docs\9.0-stage6-standalone-actions-design.md',
        'docs\9.0-stage6-manual-merge-design.md',
        'docs\9.0-stage6-course-identity-preview-design.md',
        'docs\9.0-stage6-checkpoint.md',
        'docs\9.0-stage6-test-gaps.md'
    )
)

$ErrorActionPreference = 'Continue'
$issues = New-Object System.Collections.Generic.List[string]
function Read-Utf8($p) { [Text.Encoding]::UTF8.GetString([IO.File]::ReadAllBytes($p)) }

if (-not (Test-Path $Repo)) { Write-Host "仓库不存在: $Repo"; exit 1 }
Push-Location $Repo

# ---- [1] "N 项" 的说法 vs 测试文件真实 @Test 数 -------------------------------
Write-Host '[1] 文档声明的测试项数 vs 实际 @Test 数'
foreach ($doc in $Docs) {
    $full = Join-Path $Repo $doc
    if (-not (Test-Path $full)) { continue }
    $text = Read-Utf8 $full
    # 形如：`...Test.kt`（**21 项**）  或  ...，21 项；
    $matches = [regex]::Matches($text, '([A-Za-z0-9_]+Test)\.kt[^0-9\n]{0,24}?(\d+)\s*项')
    foreach ($m in $matches) {
        $testName = $m.Groups[1].Value; $claimed = [int]$m.Groups[2].Value
        $cand = Get-ChildItem -Path (Join-Path $Repo 'app\src\test') -Recurse -Filter "$testName.kt" -ErrorAction SilentlyContinue | Select-Object -First 1
        if (-not $cand) { $issues.Add("[$doc] 提到 $testName.kt 但仓库里找不到该测试文件"); continue }
        $actual = ([regex]::Matches((Read-Utf8 $cand.FullName), '@Test')).Count
        if ($actual -ne $claimed) {
            $issues.Add("[$doc] 写 $testName.kt 有 $claimed 项，实际 $actual 项")
            Write-Host ("    x " + $testName + ": 文档 " + $claimed + " / 实际 " + $actual)
        } else {
            Write-Host ("    ok " + $testName + " = " + $actual + " 项")
        }
    }
}

# ---- [2] 文档里的提交短哈希是否存在 -----------------------------------------
Write-Host '[2] 文档提到的提交哈希是否真实存在'
$known = @{}
(git log --all --format='%h %H' 2>$null) | ForEach-Object { $p = $_ -split ' '; $known[$p[0]] = $p[1] }
foreach ($doc in $Docs) {
    $full = Join-Path $Repo $doc
    if (-not (Test-Path $full)) { continue }
    $hashes = [regex]::Matches((Read-Utf8 $full), '\b[0-9a-f]{7,40}\b') |
              ForEach-Object { $_.Value } |
              Where-Object { $_ -match '^[0-9a-f]{7}$' -and $_ -match '[0-9]' -and $_ -match '[a-f]' } |
              Sort-Object -Unique
    foreach ($h in $hashes) {
        if ($known.ContainsKey($h)) { Write-Host "    ok $h ($($known[$h].Substring(0,7)))" }
        else { $issues.Add("[$doc] 提到提交 $h,但仓库里没有这个提交"); Write-Host "    x $h 不存在" }
    }
}

# ---- [3] 同一文件里"已放行/未放行"自相矛盾 ----------------------------------
Write-Host '[3] 同一文件里"已完成"与"未放行"是否自相矛盾'
foreach ($doc in $Docs) {
    $full = Join-Path $Repo $doc
    if (-not (Test-Path $full)) { continue }
    $lines = (Read-Utf8 $full) -split "`n"
    $done = @(); $todo = @()
    for ($i = 0; $i -lt $lines.Count; $i++) {
        $l = $lines[$i]
        # 只看"带状态词的句子",按关键词归类
        if ($l -match '第三步|并入游戏提醒|改名') {
            if ($l -match '已放行|已落地|已完成|已实现') { $done += ($i + 1) }
            if ($l -match '未放行|未做|仍等|尚未') { $todo += ($i + 1) }
        }
    }
    if ($done.Count -gt 0 -and $todo.Count -gt 0) {
        # 允许"历史说明"：同一行里既有"原名/当时/此前"又有状态词 => 视为历史，跳过
        $suspicious = @()
        foreach ($t in $todo) {
            $line = $lines[$t - 1]
            if ($line -notmatch '原名|当时|此前|改名前的状态|已经不再|曾') { $suspicious += $t }
        }
        if ($suspicious.Count -gt 0) {
            $issues.Add("[$doc] 同文件里既有'已放行/已落地'(行 $($done -join ','))又有'未放行/未做'(行 $($suspicious -join ','))")
            Write-Host ("    x 完成处 " + ($done -join ',') + " / 存疑处 " + ($suspicious -join ','))
        } else { Write-Host '    ok 状态一致(未放行处均为历史说明)' }
    } else { Write-Host '    ok 状态一致' }
}

# ---- [4] 文档里的 文件:行号 是否超出该文件实际行数 --------------------------
Write-Host '[4] 文档引用的代码行号是否越界'
foreach ($doc in $Docs) {
    $full = Join-Path $Repo $doc
    if (-not (Test-Path $full)) { continue }
    $text = Read-Utf8 $full
    $refs = [regex]::Matches($text, '([A-Za-z0-9_]+\.kt):(\d+)(?:-(\d+))?')
    foreach ($r in $refs) {
        $file = $r.Groups[1].Value; $ln = [int]$r.Groups[2].Value
        $cand = Get-ChildItem -Path (Join-Path $Repo 'app\src') -Recurse -Filter $file -ErrorAction SilentlyContinue | Select-Object -First 1
        if (-not $cand) { continue }
        $total = ((Read-Utf8 $cand.FullName) -split "`n").Count
        if ($ln -gt $total) {
            $issues.Add("[$doc] 引用 $file`:$ln 超出该文件实际行数 $total")
            Write-Host "    x $file`:$ln > $total 行"
        }
    }
    Write-Host '    (越界检查完成)'
}

# ---- [5] 文档里的 "N 类 M 项" 是否与本机最近一次测试结果一致 -----------------
Write-Host '[5] 文档声明的整体测试规模 vs 本机最近一次测试结果'
$cls = 0; $tst = 0; $haveResults = $false
foreach ($v in 'testDebugUnitTest', 'testReleaseUnitTest') {
    $dir = Join-Path $Repo "app\build\test-results\$v"
    if (-not (Test-Path $dir)) { continue }
    $xmls = Get-ChildItem $dir -Filter 'TEST-*.xml' -ErrorAction SilentlyContinue
    if ($xmls.Count -eq 0) { continue }
    $haveResults = $true
    $c = $xmls.Count; $t = 0
    foreach ($x in $xmls) {
        $raw = [Text.Encoding]::UTF8.GetString([IO.File]::ReadAllBytes($x.FullName))
        $mm = [regex]::Match($raw, 'tests="(\d+)"')
        if ($mm.Success) { $t += [int]$mm.Groups[1].Value }
    }
    if ($t -gt $tst) { $cls = $c; $tst = $t }
    Write-Host ("    $v : $c 类 / $t 项")
}
if ($haveResults) {
    Write-Host ("    以较大者为准: $cls 类 / $tst 项")
    # 带日期或"当时/历史/基线"字样的句子属历史记录，跳过（那是事实陈述，不是过期声明）
    $histPat = '2026-0[0-9]-[0-9]{2}|当时|历史|基线|曾记录|此前|早前'
    foreach ($doc in $Docs) {
        $full = Join-Path $Repo $doc
        if (-not (Test-Path $full)) { continue }
        $lines = (Read-Utf8 $full) -split "`n"
        for ($i = 0; $i -lt $lines.Count; $i++) {
            $line = $lines[$i]
            foreach ($m in [regex]::Matches($line, '\*{0,2}(\d+)\s*类\s*(\d+)\s*项')) {
                $c2 = [int]$m.Groups[1].Value; $t2 = [int]$m.Groups[2].Value
                # 跳过"对比语境"：'较 145 类 865 项'、'相比…'、'从…增加到' 这类是历史对照，不是过期声明
                $preStart = [Math]::Max(0, $m.Index - 16)
                $pre = $line.Substring($preStart, $m.Index - $preStart)
                if ($pre -match '较|相比|对照|从|此前|早前|曾|增加|新增') {
                    Write-Host ("    skip(对比) " + [IO.Path]::GetFileName($doc) + ":$($i+1) = $c2 类 $t2 项")
                    continue
                }
                if ($line -match $histPat) { Write-Host ("    skip(历史) " + [IO.Path]::GetFileName($doc) + ":$($i+1) = $c2 类 $t2 项"); continue }
                if ($c2 -ne $cls -or $t2 -ne $tst) {
                    $issues.Add("[${doc}:$($i+1)] 写'$c2 类 $t2 项',但本机最近一次结果是 $cls 类 $tst 项（数字过期）")
                    Write-Host ("    x " + [IO.Path]::GetFileName($doc) + ":$($i+1) = $c2 类 $t2 项 / 实际 $cls 类 $tst 项")
                } else { Write-Host ("    ok " + [IO.Path]::GetFileName($doc) + ":$($i+1)") }
            }
        }
    }
} else {
    Write-Host '    (本机没有测试结果 XML，跳过；先跑一次 :app:testDebugUnitTest)'
}

Pop-Location
Write-Host ''
if ($issues.Count -eq 0) {
    Write-Host '结果：没有发现问题 —— 可以提交'
    exit 0
} else {
    $head = [char]0x58F0 + [char]0x660E + [char]0x4E0E + [char]0x4E8B + [char]0x5B9E + [char]0x4E0D + [char]0x7B26   # 声明与事实不符
    Write-Host ('结果：发现 ' + $issues.Count + ' 处' + $head + ' —— 提交前应先修：')
    $issues | ForEach-Object { Write-Host ("  - " + $_) }
    exit 1
}
