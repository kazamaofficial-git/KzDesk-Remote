# KzDesk Desktop (Windows): sambung ke PC lewat ID Device + password.
#   .\kzdesk-connect.ps1 123-456-789 [password]
param([Parameter(Mandatory = $true)][string]$Id, [string]$Password = $env:KZDESK_PASSWORD)
$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$api = if ($env:KZDESK_API) { $env:KZDESK_API } else { 'https://kzdesk.kazamasite.biz.id' }
$digits = ($Id -replace '[\s-]', '')
if ($digits -notmatch '^[1-9]\d{8}$') { Write-Error 'ID Device harus 9 digit.'; exit 2 }
if (-not $Password) { $sec = Read-Host 'Password Device' -AsSecureString; $Password = [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR($sec)) }

$freerdp = if ($env:KZDESK_FREERDP) { $env:KZDESK_FREERDP } else { Join-Path $PSScriptRoot 'sdl3-freerdp.exe' }
if (-not (Test-Path $freerdp)) { Write-Error 'sdl3-freerdp.exe tidak ditemukan.'; exit 1 }

try {
    $r = Invoke-RestMethod -Uri "$api/api/device/resolve" -Method Post -ContentType 'application/json' -TimeoutSec 20 `
        -Body (@{ id = $digits; password = $Password } | ConvertTo-Json -Compress)
} catch {
    $code = 0; try { $code = [int]$_.Exception.Response.StatusCode } catch { }
    switch ($code) {
        404 { Write-Error 'ID Device atau password salah.' }
        409 { Write-Error 'PC sedang offline. Pastikan KzDesk Host menyala.' }
        429 { Write-Error 'Terlalu banyak percobaan. Coba lagi sebentar lagi.' }
        default { Write-Error 'Tidak bisa menghubungi server KzDesk.' }
    }
    exit 3
}
$target = $null
foreach ($c in $r.candidates) {
    $tcp = New-Object Net.Sockets.TcpClient
    try { if ($tcp.ConnectAsync($c.host, [int]$c.port).Wait(2000) -and $tcp.Connected) { $target = '{0}:{1}' -f $c.host, $c.port; break } } catch { } finally { $tcp.Close() }
}
if (-not $target) { Write-Error 'PC ditemukan tetapi tidak bisa dijangkau (port RDP tertutup?). Coba lewat VPN.'; exit 4 }
$user = if ($r.username) { $r.username } else { 'KzDesk' }
Write-Host "Menyambung ke $target sebagai $user ..."
& $freerdp "/v:$target" "/u:$user" "/p:$Password" /cert:tofu /sound /microphone +clipboard /dynamic-resolution /gfx
