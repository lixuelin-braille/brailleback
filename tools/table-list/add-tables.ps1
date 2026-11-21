# 补全 tablelist.xml 并重打 translationtables.zip（依赖闭包）
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath 'D:\软件\brailleback盲文'
$tablesDir = (Resolve-Path 'service\jni\liblouiswrapper\liblouis\tables').Path
$xmlPath   = (Resolve-Path 'service\src\main\res\xml\tablelist.xml').Path
$zipPath   = (Resolve-Path 'service\src\main\res\raw\translationtables.zip').Path

# 本次新增：当前零登记语言的 6 点/8 点主表（身份已按表内 #-index-name / 文件名核实）
$add = @(
  @{id='af-g1';        locale='af_ZA'; dots=6; grade=1; file='afr-za-g1.ctb';    note='Afrikaans'},
  @{id='af-g2';        locale='af_ZA'; dots=6; grade=2; file='afr-za-g2.ctb';    note='Afrikaans'},
  @{id='ba-g1';        locale='ba';    dots=6; grade=1; file='ba.utb';           note='Bashkir'},
  @{id='be-g1';        locale='be';    dots=6; grade=1; file='bel.utb';          note='Belarusian'},
  @{id='chr-g1';       locale='chr';   dots=6; grade=1; file='chr-us-g1.ctb';    note='Cherokee'},
  @{id='cy-g1';        locale='cy';    dots=6; grade=1; file='cy-cy-g1.utb';     note='Welsh'},
  @{id='cy-g2';        locale='cy';    dots=6; grade=2; file='cy-cy-g2.ctb';     note='Welsh'},
  @{id='eo-g1';        locale='eo';    dots=6; grade=1; file='eo-g1.ctb';        note='Esperanto'},
  @{id='fa-g1';        locale='fa';    dots=6; grade=1; file='fa-ir-g1.utb';     note='Persian'},
  @{id='fa-IR-comp8';  locale='fa_IR'; dots=8; grade=$null; file='fa-ir-comp8.ctb'; note='Persian computer'},
  @{id='fil-g2';       locale='fil';   dots=6; grade=2; file='fil-g2.ctb';       note='Filipino'},
  @{id='ga-g1';        locale='ga';    dots=6; grade=1; file='ga-g1.utb';        note='Irish'},
  @{id='ga-g2';        locale='ga';    dots=6; grade=2; file='ga-g2.ctb';        note='Irish'},
  @{id='gd-g1';        locale='gd';    dots=6; grade=1; file='gd.ctb';           note='Scots Gaelic'},
  @{id='haw-g1';       locale='haw';   dots=6; grade=1; file='haw-us-g1.ctb';    note='Hawaiian'},
  @{id='he-g1';        locale='he';    dots=6; grade=1; file='he-IL.utb';        note='Hebrew'},
  @{id='hy-g1';        locale='hy';    dots=6; grade=1; file='hy.ctb';           note='Armenian'},
  @{id='iu-g1';        locale='iu';    dots=6; grade=1; file='iu-ca-g1.ctb';     note='Inuktitut'},
  @{id='ka-g1';        locale='ka';    dots=6; grade=1; file='ka.utb';           note='Georgian'},
  @{id='kk-g1';        locale='kk';    dots=6; grade=1; file='kk.utb';           note='Kazakh'},
  @{id='km-g1';        locale='km';    dots=6; grade=1; file='km-g1.utb';        note='Khmer'},
  @{id='lg-g1';        locale='lg';    dots=6; grade=1; file='lg-ug-g1.utb';     note='Luganda'},
  @{id='lo-g1';        locale='lo';    dots=6; grade=1; file='lo-g1.utb';        note='Lao'},
  @{id='mk-g1';        locale='mk';    dots=6; grade=1; file='mk-g1.utb';        note='Macedonian'},
  @{id='mn-g1';        locale='mn';    dots=6; grade=1; file='mn-MN-g1.utb';     note='Mongolian'},
  @{id='mn-g2';        locale='mn';    dots=6; grade=2; file='mn-MN-g2.ctb';     note='Mongolian'},
  @{id='ms-g2';        locale='ms';    dots=6; grade=2; file='ms-my-g2.ctb';     note='Malay'},
  @{id='mt-g1';        locale='mt';    dots=6; grade=1; file='mt.ctb';           note='Maltese'},
  @{id='my-g1';        locale='my';    dots=6; grade=1; file='my-g1.utb';        note='Burmese'},
  @{id='my-g2';        locale='my';    dots=6; grade=2; file='my-g2.ctb';        note='Burmese'},
  @{id='ny-g1';        locale='ny';    dots=6; grade=1; file='ny-mw.utb';        note='Chichewa'},
  @{id='rw-g1';        locale='rw';    dots=6; grade=1; file='rw-rw-g1.utb';     note='Kinyarwanda'},
  @{id='sw-g1';        locale='sw';    dots=6; grade=1; file='sw-ke-g1.utb';     note='Swahili'},
  @{id='sw-g2';        locale='sw';    dots=6; grade=2; file='sw-ke-g2.ctb';     note='Swahili'},
  @{id='ta-g1';        locale='ta';    dots=6; grade=1; file='ta-ta-g1.ctb';     note='Tamil'},
  @{id='th-g0';        locale='th';    dots=6; grade=0; file='th-g0.utb';        note='Thai'},
  @{id='th-g1';        locale='th';    dots=6; grade=1; file='th-g1.utb';        note='Thai'},
  @{id='th-g2';        locale='th';    dots=6; grade=2; file='th-g2.ctb';        note='Thai'},
  @{id='tt-g1';        locale='tt';    dots=6; grade=1; file='tt.utb';           note='Tatar'},
  @{id='uk-g1';        locale='uk';    dots=6; grade=1; file='uk.utb';           note='Ukrainian'},
  @{id='ur-g1';        locale='ur';    dots=6; grade=1; file='ur-pk-g1.utb';     note='Urdu'},
  @{id='ur-g2';        locale='ur';    dots=6; grade=2; file='ur-pk-g2.ctb';     note='Urdu'},
  @{id='uz-g1';        locale='uz';    dots=6; grade=1; file='uz-g1.utb';        note='Uzbek'},
  @{id='ve-g1';        locale='ve';    dots=6; grade=1; file='ve-za-g1.utb';     note='Tshivenda'},
  @{id='ve-g2';        locale='ve';    dots=6; grade=2; file='ve-za-g2.ctb';     note='Tshivenda'},
  @{id='xh-g1';        locale='xh';    dots=6; grade=1; file='xh-za-g1.utb';     note='isiXhosa'},
  @{id='xh-g2';        locale='xh';    dots=6; grade=2; file='xh-za-g2.ctb';     note='isiXhosa'},
  @{id='yi-g1';        locale='yi';    dots=6; grade=1; file='yi.utb';           note='Yiddish'},
  @{id='zu-g1';        locale='zu';    dots=6; grade=1; file='zu-za-g1.utb';     note='isiZulu'},
  @{id='zu-g2';        locale='zu';    dots=6; grade=2; file='zu-za-g2.ctb';     note='isiZulu'}
)

# 1) 校验：文件必须存在、id 不可与现有重复
$xml = [xml](Get-Content -LiteralPath $xmlPath -Raw)
$existingIds = $xml.SelectNodes('//table') | ForEach-Object { $_.id }
$dup = $add | Where-Object { $existingIds -contains $_.id }
if ($dup) { throw ('id 冲突: ' + ($dup.id -join ', ')) }
foreach ($a in $add) {
  if (-not (Test-Path -LiteralPath (Join-Path $tablesDir $a.file))) { throw ('表文件缺失: ' + $a.file) }
}

# 2) 追加 <table> 条目（保持现有缩进风格，按语言分组加注释）
$sb = New-Object System.Text.StringBuilder
[void]$sb.AppendLine('')
[void]$sb.AppendLine('    <!-- 以下为补全：liblouis 上游已支持、本清单此前零登记的语言 -->')
$last = ''
foreach ($a in ($add | Sort-Object { $_.locale })) {
  if ($a.note -ne $last) { [void]$sb.AppendLine(''); [void]$sb.AppendLine('    <!-- ' + $a.note + ' -->'); $last = $a.note }
  [void]$sb.AppendLine('    <table')
  [void]$sb.AppendLine('         id="' + $a.id + '"')
  [void]$sb.AppendLine('         locale="' + $a.locale + '"')
  [void]$sb.AppendLine('         dots="' + $a.dots + '"')
  if ($null -ne $a.grade) { [void]$sb.AppendLine('         grade="' + $a.grade + '"') }
  [void]$sb.AppendLine('         fileName="' + $a.file + '" />')
}
$raw = [System.IO.File]::ReadAllText($xmlPath)
$idx = $raw.LastIndexOf('</table-list>')
if ($idx -lt 0) { throw 'tablelist.xml 缺少 </table-list>' }
$newRaw = $raw.Substring(0, $idx) + $sb.ToString() + $raw.Substring($idx)
[System.IO.File]::WriteAllText($xmlPath, $newRaw, (New-Object System.Text.UTF8Encoding($false)))

# 3) 依赖闭包：从每张登记表出发解析 include 链
function Get-Closure([string]$root) {
  $seen = New-Object 'System.Collections.Generic.HashSet[string]'
  $stack = New-Object System.Collections.Generic.Stack[string]
  $stack.Push($root)
  $missing = @()
  while ($stack.Count -gt 0) {
    $name = $stack.Pop()
    if (-not $seen.Add($name)) { continue }
    $p = Join-Path $tablesDir $name
    if (-not (Test-Path -LiteralPath $p)) { $missing += $name; continue }
    foreach ($line in [System.IO.File]::ReadLines($p)) {
      $m = [regex]::Match($line, '^\s*include\s+([^\s#]+)')
      if ($m.Success) { $stack.Push([System.IO.Path]::GetFileName($m.Groups[1].Value)) }
    }
  }
  return @{ Files = $seen; Missing = $missing }
}

$xml2 = [xml](Get-Content -LiteralPath $xmlPath -Raw)
$roots = $xml2.SelectNodes('//table') | ForEach-Object { $_.fileName }
$all = New-Object 'System.Collections.Generic.HashSet[string]'
$missAll = @()
foreach ($r in $roots) {
  $c = Get-Closure $r
  foreach ($f in $c.Files) { [void]$all.Add($f) }
  $missAll += $c.Missing
}
$missAll = $missAll | Sort-Object -Unique
if ($missAll) { throw ('include 链缺文件: ' + ($missAll -join ', ')) }
foreach ($r in $roots) { [void]$all.Add($r) }

# 4) 重打 zip（条目沿用 liblouis/tables/ 前缀）
$tmp = Join-Path ([System.IO.Path]::GetTempPath()) ('tt-' + [guid]::NewGuid().ToString('N') + '.zip')
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open($tmp, [System.IO.Compression.ZipArchiveMode]::Create)
try {
  foreach ($n in ($all | Sort-Object)) {
    [void][System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
      $zip, (Join-Path $tablesDir $n), ('liblouis/tables/' + $n),
      [System.IO.Compression.CompressionLevel]::Optimal)
  }
} finally { $zip.Dispose() }
Move-Item -LiteralPath $tmp -Destination $zipPath -Force

Write-Output ('新增条目: ' + $add.Count)
Write-Output ('清单总条目: ' + $xml2.SelectNodes('//table').Count)
Write-Output ('zip 内文件: ' + $all.Count)
Write-Output ('zip 大小 MB: ' + [math]::Round((Get-Item $zipPath).Length / 1MB, 2))