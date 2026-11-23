# 表清单 / 表包维护脚本（tools/table-list/add-tables.ps1）
#
# 四种模式（-Mode），建议执行顺序：pack-all → sync-copies → guard-raw → verify。
#   pack-all（默认）：把 service/jni/liblouiswrapper/liblouis/tables 下全部文件打进
#     service/src/main/res/raw/translationtables.zip，条目前缀沿用 liblouis/tables/。
#     仅排除 $EXCLUDED_TABLES 列出的表；先写临时文件再 Move-Item 原子替换，避免半截 zip。
#     打包策略历史：2026-11-24 之前按清单登记表的 include 依赖闭包挑选文件；现改为
#     整目录打包 + 单表排除，避免上游新增表或隐式依赖被漏打。
#   sync-copies：先按 pack-all 生成 service 表包，再原样复制覆盖
#     addon/brailletranslate/src/main/res/raw/translationtables.zip；复制后立即比对两份
#     SHA256，不一致 exit 1。addon 那份同名 raw 是为保留两个模块各自的
#     R.raw.translationtables 常量而存在的**生成物**，不得手工修改；唯一输入是
#     tables 目录 + $EXCLUDED_TABLES。
#   guard-raw：扫全仓 */src/main/res/raw/translationtables.zip，份数 ≥1 即可；
#     所有副本必须与 service 基准包 SHA256 逐字节一致，不一致写 ERROR（列路径/字节/条目/SHA256）
#     并 exit 1；一致则逐份打印并 exit 0。以后谁手工塞一份旧包都会被拦住。
#   verify：读 tablelist.xml 每条 <table>，校验根表与其递归 include 闭包全部在 zip 内；
#     目标文件在源目录存在但被 $EXCLUDED_TABLES 排除，也算缺失（真实依赖断裂，不静默跳过）。
#     结论固定一行：校验 N 条，缺失 M 条；M>0 时逐条列出 tableId → fileName → 缺的 include 文件。
#
# 纪律：本脚本只动表清单与表包；不跑 gradle，不碰 native 源码（.c）与 jniLibs（.so）。
param(
  [ValidateSet('pack-all', 'sync-copies', 'guard-raw', 'verify')]
  [string]$Mode = 'pack-all'
)

$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath 'D:\软件\brailleback盲文'
$tablesDir   = (Resolve-Path 'service\jni\liblouiswrapper\liblouis\tables').Path
$xmlPath     = (Resolve-Path 'service\src\main\res\xml\tablelist.xml').Path
$zipPath     = (Resolve-Path 'service\src\main\res\raw\translationtables.zip').Path
$addonZipPath = Join-Path $PWD.Path 'addon\brailletranslate\src\main\res\raw\translationtables.zip'

# 显式排除的表：zhcn-cbs-full.ctb 在本版 liblouis 3.38.0 上加载失败
# （logcat: W/...: Table not found or invalid: zhcn-cbs-full.ctb；
#  原生侧回放 E/LibLouisNative: checkTable failed: table='...'），已从 tablelist.xml 移除；
# 整目录打包时同样排除。源目录内文件保留，仅不进入 zip。
$EXCLUDED_TABLES = @('zhcn-cbs-full.ctb')

$ZipPrefix = 'liblouis/tables/'

# 解析一张表文件正文里的 include 目标（liblouis 形态：`include xxx.ctb`，
# 可能带 `table:` 前缀或引号；统一取叶子文件名，递归、去重、防环由调用方负责）。
function Get-IncludeTargets([string]$body) {
  $list = New-Object System.Collections.Generic.List[string]
  foreach ($line in ($body -split "`r?`n")) {
    $m = [regex]::Match($line.Trim(), '^include\s+([^\s#]+)')
    if (-not $m.Success) { continue }
    $tok = $m.Groups[1].Value.Trim('"', "'")
    if ($tok.StartsWith('table:')) { $tok = $tok.Substring('table:'.Length) }
    $leaf = Split-Path ($tok -replace '/', '\') -Leaf
    [void]$list.Add($leaf)
  }
  return $list
}

function Read-ZipBody($zip, [string]$entryName) {
  $e = $zip.GetEntry($entryName)
  if ($null -eq $e) { return $null }
  $sr = New-Object System.IO.StreamReader($e.Open())
  try { return $sr.ReadToEnd() } finally { $sr.Dispose() }
}

function Get-ZipReport([string]$path) {
  $zip = [System.IO.Compression.ZipFile]::OpenRead($path)
  $entryCount = $zip.Entries.Count
  $zip.Dispose()
  $hash = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash
  $item = Get-Item -LiteralPath $path
  [pscustomobject]@{ Path = $path; Bytes = $item.Length; Entries = $entryCount; Sha256 = $hash }
}

function Invoke-PackAll {
  Add-Type -AssemblyName System.IO.Compression.FileSystem

  $oldBytes = -1
  $oldEntries = 0
  if (Test-Path -LiteralPath $zipPath) {
    $oldBytes = (Get-Item -LiteralPath $zipPath).Length
    $oldZip = [System.IO.Compression.ZipFile]::OpenRead($zipPath)
    $oldEntries = $oldZip.Entries.Count
    $oldZip.Dispose()
  }

  $excl = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
  foreach ($x in $EXCLUDED_TABLES) { [void]$excl.Add($x) }

  $files = @(Get-ChildItem -LiteralPath $tablesDir -Recurse -File |
    Where-Object { -not $excl.Contains($_.Name) })

  $tmp = Join-Path ([System.IO.Path]::GetTempPath()) ('tt-' + [guid]::NewGuid().ToString('N') + '.zip')
  $zip = [System.IO.Compression.ZipFile]::Open($tmp, [System.IO.Compression.ZipArchiveMode]::Create)
  try {
    foreach ($f in ($files | Sort-Object FullName)) {
      $rel = $f.FullName.Substring($tablesDir.Length).TrimStart('\', '/') -replace '\\', '/'
      [void][System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
        $zip, $f.FullName, ($ZipPrefix + $rel),
        [System.IO.Compression.CompressionLevel]::Optimal)
    }
  } finally { $zip.Dispose() }
  Move-Item -LiteralPath $tmp -Destination $zipPath -Force

  $new = Get-Item -LiteralPath $zipPath
  Write-Output ("zip 路径: " + $zipPath)
  Write-Output ("zip 字节数: " + $new.Length)
  Write-Output ("zip 条目数: " + $files.Count)
  Write-Output ("旧 zip 条目数: " + $oldEntries + "  旧字节数: " + $oldBytes)
  Write-Output ("条目增量: " + ($files.Count - $oldEntries) + "  字节增量: " + ($new.Length - $oldBytes))
  Write-Output ("排除表: " + ($EXCLUDED_TABLES -join ', '))
}

function Invoke-SyncCopies {
  # 第一步：按唯一输入（tables 目录 + 排除清单）重打 service 基准包
  Invoke-PackAll

  # 第二步：原样复制到 addon（生成物，不得手工修改）
  $addonDir = Split-Path $addonZipPath -Parent
  if (-not (Test-Path -LiteralPath $addonDir)) { New-Item -ItemType Directory -Path $addonDir -Force | Out-Null }
  Copy-Item -LiteralPath $zipPath -Destination $addonZipPath -Force

  # 第三步：逐字节一致性断言
  $ref = Get-ZipReport $zipPath
  $copy = Get-ZipReport $addonZipPath
  if ($ref.Sha256 -ne $copy.Sha256 -or $ref.Bytes -ne $copy.Bytes -or $ref.Entries -ne $copy.Entries) {
    Write-Output ("ERROR: addon 副本与 service 基准包不一致：")
    Write-Output ("  基准 " + $ref.Path + " | " + $ref.Bytes + " B | " + $ref.Entries + " 条目 | SHA256 " + $ref.Sha256)
    Write-Output ("  副本 " + $copy.Path + " | " + $copy.Bytes + " B | " + $copy.Entries + " 条目 | SHA256 " + $copy.Sha256)
    exit 1
  }
  Write-Output ("sync-copies: addon 副本已生成，与基准包逐字节一致")
  Write-Output ("  副本 " + $copy.Path + " | " + $copy.Bytes + " B | " + $copy.Entries + " 条目 | SHA256 " + $copy.Sha256)
}

function Invoke-Verify {
  Add-Type -AssemblyName System.IO.Compression.FileSystem

  $xml = [xml](Get-Content -LiteralPath $xmlPath -Raw)
  $rows = @($xml.SelectNodes('//table'))

  $excl = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
  foreach ($x in $EXCLUDED_TABLES) { [void]$excl.Add($x) }

  $diskFiles = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
  foreach ($d in (Get-ChildItem -LiteralPath $tablesDir -Recurse -File)) { [void]$diskFiles.Add($d.Name) }

  $missing = New-Object System.Collections.Generic.List[object]

  $zip = [System.IO.Compression.ZipFile]::OpenRead($zipPath)
  try {
    $entryLeaves = @{}
    foreach ($e in $zip.Entries) {
      if ($e.IsDirectory) { continue }
      $entryLeaves[(Split-Path $e.FullName -Leaf)] = $e.FullName
    }

    foreach ($row in $rows) {
      $id = $row.id
      $root = $row.fileName
      $seen = New-Object 'System.Collections.Generic.HashSet[string]'
      $stack = New-Object System.Collections.Generic.Stack[string]
      $stack.Push($root)
      while ($stack.Count -gt 0) {
        $name = $stack.Pop()
        if (-not $seen.Add($name)) { continue }
        if (-not $entryLeaves.ContainsKey($name)) {
          $reason = if ($excl.Contains($name)) {
            '在源目录存在但被排除（依赖断裂）'
          } elseif (-not $diskFiles.Contains($name)) {
            '源目录与 zip 均不存在'
          } else {
            '源目录存在但未打进 zip'
          }
          $missing.Add([pscustomobject]@{ tableId = $id; fileName = $root; missing = $name; reason = $reason })
          continue
        }
        $body = Read-ZipBody $zip $entryLeaves[$name]
        if ($null -eq $body) { continue }
        foreach ($dep in (Get-IncludeTargets $body)) { $stack.Push($dep) }
      }
    }
  } finally { $zip.Dispose() }

  $missing = @($missing | Sort-Object tableId, missing -Unique)
  Write-Output ("校验 " + $rows.Count + " 条，缺失 " + $missing.Count + " 条")
  foreach ($m in $missing) {
    Write-Output ("  " + $m.tableId + " → " + $m.fileName + " → 缺的 include 文件: " + $m.missing + "（" + $m.reason + "）")
  }
  if ($missing.Count -gt 0) { exit 1 }
}

function Invoke-GuardRaw {
  Add-Type -AssemblyName System.IO.Compression.FileSystem

  if (-not (Test-Path -LiteralPath $zipPath)) {
    Write-Output ("ERROR: service 基准包不存在: " + $zipPath)
    exit 1
  }
  $ref = Get-ZipReport $zipPath

  $hits = @(Get-ChildItem -Recurse -File -Filter 'translationtables.zip' -Path $PWD.Path |
    Where-Object { $_.FullName -notmatch '\\build\\|\\\.git\\|\\\.tmp-' })
  if ($hits.Count -lt 1) {
    Write-Output "ERROR: 全仓未找到任何 translationtables.zip"
    exit 1
  }

  $reports = @($hits | ForEach-Object { Get-ZipReport $_.FullName })
  $bad = @($reports | Where-Object { $_.Sha256 -ne $ref.Sha256 })

  if ($bad.Count -gt 0) {
    Write-Output ("ERROR: 全仓 " + $reports.Count + " 份 translationtables.zip 与 service 基准包不一致（必须逐字节相同）：")
    Write-Output ("  基准 " + $ref.Path + " | " + $ref.Bytes + " B | " + $ref.Entries + " 条目 | SHA256 " + $ref.Sha256)
    foreach ($r in $reports) {
      $mark = if ($r.Sha256 -eq $ref.Sha256) { '一致' } else { '不一致' }
      Write-Output ("  [" + $mark + "] " + $r.Path + " | " + $r.Bytes + " B | " + $r.Entries + " 条目 | SHA256 " + $r.Sha256)
    }
    exit 1
  }

  Write-Output ("guard-raw 通过：" + $reports.Count + " 份 translationtables.zip 与基准包逐字节一致")
  foreach ($r in $reports) {
    Write-Output ("  " + $r.Path + " | " + $r.Bytes + " B | " + $r.Entries + " 条目 | SHA256 " + $r.Sha256)
  }
}

switch ($Mode) {
  'pack-all'    { Invoke-PackAll }
  'sync-copies' { Invoke-SyncCopies }
  'guard-raw'   { Invoke-GuardRaw }
  'verify'      { Invoke-Verify }
}
