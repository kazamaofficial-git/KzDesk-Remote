# KzDesk Host - pembaruan otomatis. Cek server, unduh, verifikasi SHA-256, lalu pasang diam-diam.
param([switch]$Quiet)
. (Join-Path $PSScriptRoot 'lib.ps1')

function Test-KzTrustedUrl([string]$Url, [string]$ApiBase) {
    try { $u = [Uri]$Url } catch { return $false }
    if ($u.Scheme -ne 'https' -or $u.UserInfo) { return $false }
    $h = $u.Host.ToLowerInvariant()
    $own = ([Uri]$ApiBase).Host.ToLowerInvariant()
    return ($h -eq $own) -or ($h -eq 'github.com') -or $h.EndsWith('.github.com') -or ($h -eq 'githubusercontent.com') -or $h.EndsWith('.githubusercontent.com')
}

$cfg = Get-KzConfig
$current = Get-KzVersion
$info = Invoke-RestMethod -Uri ("{0}/api/update/host?version={1}" -f $cfg.apiBase.TrimEnd('/'), $current) -TimeoutSec 20
if ($info.status -ne 'ok' -or -not $info.update_available -or -not $info.asset) {
    Write-KzLog "tidak ada update (terpasang v$current)"
    return
}
$asset = $info.asset
if (-not (Test-KzTrustedUrl $asset.url $cfg.apiBase) -or $asset.sha256 -notmatch '^[0-9a-fA-F]{64}$') {
    Write-KzLog 'update ditolak: URL/hash tidak tepercaya'
    return
}

$dir = Join-Path $script:DataDir 'updates'
if (Test-Path $dir) { Remove-Item $dir -Recurse -Force -ErrorAction SilentlyContinue }
New-Item -ItemType Directory -Path $dir -Force | Out-Null
$file = Join-Path $dir 'KzDesk-Host-Setup.exe'
Write-KzLog "mengunduh v$($info.version)"
Invoke-WebRequest -Uri $asset.url -OutFile $file -UseBasicParsing -TimeoutSec 600

$actual = (Get-FileHash -Path $file -Algorithm SHA256).Hash.ToLowerInvariant()
if ($actual -ne $asset.sha256.ToLowerInvariant()) {
    Remove-Item $file -Force -ErrorAction SilentlyContinue
    Write-KzLog 'update dibatalkan: SHA-256 tidak cocok'
    return
}

# Pasang lewat task terpisah: installer akan menghentikan task service ini, dan proses anak
# task ikut berhenti. Task "update" berdiri sendiri sehingga instalasi tidak terputus.
$tn = 'KzDesk Host Update'
$tr = '"{0}" /VERYSILENT /SUPPRESSMSGBOXES /NORESTART /SP-' -f $file
schtasks.exe /Create /TN $tn /SC ONCE /ST 00:00 /RU SYSTEM /RL HIGHEST /TR $tr /F | Out-Null
schtasks.exe /Run /TN $tn | Out-Null
Write-KzLog "pemasangan v$($info.version) dimulai"
