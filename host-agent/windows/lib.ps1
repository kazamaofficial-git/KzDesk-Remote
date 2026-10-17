# KzDesk Host - fungsi bersama (dipakai service, setup, updater, dan skrip kontrol).
# Kompatibel Windows PowerShell 5.1.
Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$script:DataDir    = Join-Path $env:ProgramData 'KzDesk'
$script:InstallDir = Join-Path $env:ProgramFiles 'KzDesk Host'
$script:DeviceFile = Join-Path $script:DataDir 'device.json'
$script:ConfigFile = Join-Path $script:DataDir 'config.json'
$script:LogFile    = Join-Path $script:DataDir 'logs\host.log'
$script:Account    = 'KzDesk'
$script:TaskName   = 'KzDesk Host Service'
$script:FirewallTcp = 'KzDesk Host RDP (TCP)'
$script:FirewallUdp = 'KzDesk Host RDP (UDP)'

function Write-KzLog([string]$Message) {
    try {
        $dir = Split-Path $script:LogFile
        if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
        if ((Test-Path $script:LogFile) -and ((Get-Item $script:LogFile).Length -gt 1MB)) {
            Move-Item $script:LogFile ($script:LogFile + '.old') -Force
        }
        Add-Content -Path $script:LogFile -Value ('{0:yyyy-MM-dd HH:mm:ss}  {1}' -f (Get-Date), $Message)
    } catch { }
}

function Get-KzConfig {
    if (Test-Path $script:ConfigFile) { return (Get-Content $script:ConfigFile -Raw | ConvertFrom-Json) }
    return [pscustomobject]@{ apiBase = 'https://kzdesk.kazamasite.biz.id' }
}

function Get-KzVersion {
    $f = Join-Path $script:InstallDir 'VERSION'
    if (Test-Path $f) { return (Get-Content $f -Raw).Trim() }
    return '0.0.0'
}

function Write-KzJson([string]$Path, $Object) {
    $json = $Object | ConvertTo-Json -Depth 6
    [IO.File]::WriteAllText($Path, $json, (New-Object Text.UTF8Encoding($false)))
}

function Read-KzDevice {
    if (Test-Path $script:DeviceFile) { return (Get-Content $script:DeviceFile -Raw | ConvertFrom-Json) }
    return $null
}

function Save-KzDevice($Device) { Write-KzJson $script:DeviceFile $Device }

# Sandi acak tanpa karakter yang mirip (0/O, 1/l/I). Rejection sampling agar tanpa bias.
function New-KzPassword([int]$Length = 10) {
    $chars = 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789'
    $limit = 256 - (256 % $chars.Length)
    $rng = New-Object System.Security.Cryptography.RNGCryptoServiceProvider
    $buf = New-Object byte[] 1
    $out = New-Object System.Text.StringBuilder
    while ($out.Length -lt $Length) {
        $rng.GetBytes($buf)
        if ($buf[0] -lt $limit) { [void]$out.Append($chars[$buf[0] % $chars.Length]) }
    }
    $rng.Dispose()
    return $out.ToString()
}

function New-KzDeviceId {
    $rng = New-Object System.Security.Cryptography.RNGCryptoServiceProvider
    $b = New-Object byte[] 4
    $rng.GetBytes($b)
    $rng.Dispose()
    $n = [BitConverter]::ToUInt32($b, 0)
    return [string](100000000 + ($n % 900000000))
}

function Format-KzId([string]$Id) { return ('{0}-{1}-{2}' -f $Id.Substring(0, 3), $Id.Substring(3, 3), $Id.Substring(6, 3)) }

# Verifier PBKDF2-SHA256 (sama dengan yang diverifikasi server). Password tidak pernah dikirim.
function New-KzVerifier([string]$Password) {
    $salt = New-Object byte[] 16
    $rng = New-Object System.Security.Cryptography.RNGCryptoServiceProvider
    $rng.GetBytes($salt); $rng.Dispose()
    $kdf = New-Object System.Security.Cryptography.Rfc2898DeriveBytes($Password, $salt, 100000, [System.Security.Cryptography.HashAlgorithmName]::SHA256)
    $hash = $kdf.GetBytes(32)
    $kdf.Dispose()
    return @{ salt = [Convert]::ToBase64String($salt); hash = [Convert]::ToBase64String($hash); iter = 100000 }
}

function Get-KzAddresses {
    $list = @()
    try {
        $list = @(Get-NetIPAddress -AddressFamily IPv4 -ErrorAction Stop |
            Where-Object { $_.IPAddress -notlike '127.*' -and $_.IPAddress -notlike '169.254.*' -and $_.PrefixOrigin -ne 'WellKnown' } |
            ForEach-Object { $_.IPAddress } | Select-Object -First 8)
    } catch { }
    return [string[]]$list
}

function Get-KzRdpPort {
    try { return [int](Get-ItemProperty 'HKLM:\SYSTEM\CurrentControlSet\Control\Terminal Server\WinStations\RDP-Tcp').PortNumber } catch { return 3389 }
}

function Get-KzHttpStatus($ErrorRecord) {
    try { return [int]$ErrorRecord.Exception.Response.StatusCode } catch { return 0 }
}

function Invoke-KzApi([string]$Path, [hashtable]$Body) {
    $cfg = Get-KzConfig
    $json = $Body | ConvertTo-Json -Depth 6 -Compress
    return Invoke-RestMethod -Uri ($cfg.apiBase.TrimEnd('/') + $Path) -Method Post -ContentType 'application/json' -Body $json -TimeoutSec 20
}

# Daftar (pertama kali) atau kirim heartbeat. Mengembalikan $true bila berhasil.
function Sync-KzDirectory {
    $dev = Read-KzDevice
    if ($null -eq $dev) { return $false }
    $body = @{
        hostname = $env:COMPUTERNAME
        lan      = [string[]](Get-KzAddresses)
        port     = (Get-KzRdpPort)
        version  = (Get-KzVersion)
    }
    $hasSecret = ($dev.PSObject.Properties.Name -contains 'hostSecret') -and $dev.hostSecret
    if (-not $hasSecret) {
        for ($i = 0; $i -lt 5; $i++) {
            $reg = $body.Clone()
            $reg.id = $dev.id; $reg.salt = $dev.salt; $reg.hash = $dev.hash; $reg.iter = $dev.iter
            try {
                $r = Invoke-KzApi '/api/device/register' $reg
                $dev | Add-Member -NotePropertyName hostSecret -NotePropertyValue $r.host_secret -Force
                $dev.id = $r.id; $dev.displayId = $r.display_id
                Save-KzDevice $dev
                Write-KzLog "terdaftar sebagai $($dev.displayId)"
                return $true
            } catch {
                if ((Get-KzHttpStatus $_) -eq 409) { $dev.id = New-KzDeviceId; $dev.displayId = Format-KzId $dev.id; Save-KzDevice $dev; continue }
                throw
            }
        }
        return $false
    }
    $body.id = $dev.id
    $body.host_secret = $dev.hostSecret
    $pending = ($dev.PSObject.Properties.Name -contains 'pendingVerifier') -and $dev.pendingVerifier
    if ($pending) { $body.salt = $dev.salt; $body.hash = $dev.hash; $body.iter = $dev.iter }
    Invoke-KzApi '/api/device/heartbeat' $body | Out-Null
    if ($pending) { $dev.pendingVerifier = $false; Save-KzDevice $dev }
    return $true
}
