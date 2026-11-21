Set-Location -LiteralPath 'D:\软件\brailleback盲文'
$t = (Resolve-Path 'service\jni\liblouiswrapper\liblouis\tables').Path
$chosen = @(
 'afr-za-g1.ctb','afr-za-g2.ctb','ba.utb','bel.utb','chr-us-g1.ctb','cy-cy-g1.utb','cy-cy-g2.ctb',
 'eo-g1.ctb','fa-ir-g1.utb','fa-ir-comp8.ctb','fil-g2.ctb','ga-g1.utb','ga-g2.ctb','gd.ctb','haw-us-g1.ctb',
 'he-IL.utb','hy.ctb','iu-ca-g1.ctb','ka.utb','kk.utb','km-g1.utb','lg-ug-g1.utb','lo-g1.utb','mk-g1.utb',
 'mn-MN-g1.utb','mn-MN-g2.ctb','ms-my-g2.ctb','mt.ctb','my-g1.utb','my-g2.ctb','ny-mw.utb','rw-rw-g1.utb',
 'sw-ke-g1.utb','sw-ke-g2.ctb','ta-ta-g1.ctb','th-g0.utb','th-g1.utb','th-g2.ctb','tt.utb','uk.utb',
 'ur-pk-g1.utb','ur-pk-g2.ctb','uz-g1.utb','ve-za-g1.utb','ve-za-g2.ctb','xh-za-g1.utb','xh-za-g2.ctb',
 'yi.utb','zu-za-g1.utb','zu-za-g2.ctb'
)
foreach ($f in $chosen) {
  $p = Join-Path $t $f
  if (-not (Test-Path -LiteralPath $p)) { Write-Output ("缺文件: " + $f); continue }
  $idx=''; $lang=''; $reg=''; $grade=''; $type=''
  foreach ($line in [System.IO.File]::ReadLines($p)) {
    if ($line -match '^#-index-name:\s*(.+)$') { $idx=$Matches[1].Trim() }
    elseif ($line -match '^#\+language:\s*(.+)$') { if(-not $lang){$lang=$Matches[1].Trim()} }
    elseif ($line -match '^#\+region:\s*(.+)$')   { if(-not $reg){$reg=$Matches[1].Trim()} }
    elseif ($line -match '^#\+grade:\s*(.+)$')    { $grade=$Matches[1].Trim() }
    elseif ($line -match '^#\+type:\s*(.+)$')     { $type=$Matches[1].Trim() }
    if ($idx -and $lang -and $grade) { break }
  }
  '{0,-22} | lang={1,-6} reg={2,-4} grade={3,-3} type={4,-12} | {5}' -f $f,$lang,$reg,$grade,$type,$idx
}