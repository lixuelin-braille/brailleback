# 只读：按文件名规范推断 locale/等级，列出「当前零登记的活语言」可补的表
Set-Location -LiteralPath 'D:\软件\brailleback盲文'
$t = (Resolve-Path 'service\jni\liblouiswrapper\liblouis\tables').Path
$xml = [xml](Get-Content -LiteralPath 'service\src\main\res\xml\tablelist.xml' -Raw)
$regLangs = $xml.SelectNodes('//table') | ForEach-Object { $_.locale.Split('_')[0] } | Sort-Object -Unique

# 活语言白名单（ISO 639-1），排除学术/死语言与只有字符表没有输出表的语种
$allow = @(
  'ja','th','uk','be','mk','kk','ka','hy','uz','mn','fa','ur','sw','fil','af','cy','ga','eo',
  'mi','ms','my','km','lo','ta','he','yi','tt','ba','ny','lg','rw','iu','haw','chr',
  'zu','xh','ve','nso','st','tn','gd','mt','sq','yo','ig','ha','so','mg','sn','ti','am'
)

$files = Get-ChildItem -LiteralPath $t -File | Where-Object { $_.Name -match '\.(ctb|utb)$' }

function Parse([string]$name) {
  $base = [System.IO.Path]::GetFileNameWithoutExtension($name)
  $m = [regex]::Match($base, '^([a-z]{2})(?:-([a-z]{2}))?(?:-(g\d))?(?:-(.+))?$')
  if (-not $m.Success) { return $null }
  $lang = $m.Groups[1].Value
  $reg  = $m.Groups[2].Value
  $grade= $m.Groups[3].Value
  $extra= $m.Groups[4].Value
  $dots = 6
  if ($base -match 'comp8|-8dot|8g') { $dots = 8 }
  if ($base -match 'comp6|-6dot|6g') { $dots = 6 }
  return [pscustomobject]@{ lang=$lang; reg=$reg; grade=$grade; extra=$extra; dots=$dots }
}

$out = @()
foreach ($lang in $allow) {
  if ($regLangs -contains $lang) { continue }
  $group = $files | Where-Object { $_.Name -match "^$lang-" -or $_.Name -match "^$lang\.(ctb|utb)$" }
  if (-not $group) { continue }
  foreach ($f in $group) {
    $p = Parse $f.Name
    if (-not $p) { continue }
    $out += [pscustomobject]@{ lang=$lang; file=$f.Name; bytes=$f.Length; dots=$p.dots; grade=$p.grade; extra=$p.extra; reg=$p.reg }
  }
}
Write-Output ('候选文件数: ' + $out.Count)
$out | Sort-Object lang, dots, grade, file | ForEach-Object {
  '{0,-5} {1,-26} {2,7}B dots={3} grade={4,-2} extra={5,-12} reg={6}' -f $_.lang,$_.file,$_.bytes,$_.dots,$_.grade,$_.extra,$_.reg
}
Write-Output ''
Write-Output '=== 白名单里连候选文件都没有的语言 ==='
$allow | Where-Object { $regLangs -notcontains $_ -and -not ($files | Where-Object { $_.Name -match "^($_)-" -or $_.Name -match "^$_.(ctb|utb)$" }) } | ForEach-Object { '  ' + $_ }