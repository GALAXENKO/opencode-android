param([string]$Path = "F:\проджект\buildenv\dl\opencode\package\bin\opencode")
$bytes = [IO.File]::ReadAllBytes($Path)
$s = [Text.Encoding]::Latin1.GetString($bytes)
$needles = @(
  '/etc/resolv.conf',
  '/etc/ssl/certs',
  'ca-certificates.crt',
  '/etc/hosts',
  '/etc/nsswitch.conf',
  '/proc/self/exe',
  '/etc/ssl/cert.pem',
  'SSL_CERT_FILE',
  'SSL_CERT_DIR',
  'NODE_EXTRA_CA_CERTS',
  'nameserver',
  '/etc/passwd',
  '/etc/group'
)
foreach ($n in $needles) {
  $idx = New-Object System.Collections.Generic.List[int]
  $i = $s.IndexOf($n)
  while ($i -ge 0 -and $idx.Count -lt 20) { $idx.Add($i); $i = $s.IndexOf($n, $i + 1) }
  Write-Output ("{0,-24} => {1,3}  {2}" -f $n, $idx.Count, ($idx -join ','))
}
