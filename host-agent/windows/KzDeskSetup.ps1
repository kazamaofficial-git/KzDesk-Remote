# KzDesk Host - konfigurasi sekali jalan (dipanggil installer; aman dijalankan ulang/idempoten).
param(
    [string]$ApiBase = 'https://kzdesk.kazamasite.biz.id',
    [switch]$AllowPublic,
    [switch]$ResetCredentials
)
. (Join-Path $PSScriptRoot 'lib.ps1')

$principal = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) { throw 'Butuh hak Administrator.' }

$caption = (Get-CimInstance Win32_OperatingSystem).Caption
if ($caption -match '\bHome\b') {
    Write-KzLog "edisi tidak didukung: $caption (Windows Home tidak bisa menjadi host RDP)"
    throw 'Windows Home tidak mendukung Remote Desktop host. Gunakan Windows Pro/Enterprise/Server.'
}

# ---- folder data (hanya SYSTEM + Administrators; pakai SID agar tidak bergantung bahasa Windows) ----
New-Item -ItemType Directory -Path $script:DataDir -Force | Out-Null
icacls.exe $script:DataDir /inheritance:r /grant:r '*S-1-5-18:(OI)(CI)F' '*S-1-5-32-544:(OI)(CI)F' | Out-Null
Write-KzJson $script:ConfigFile ([pscustomobject]@{ apiBase = $ApiBase })

# ---- Remote Desktop + audio (suara PC -> HP dan mikrofon HP -> PC) ----
$ts = 'HKLM:\SYSTEM\CurrentControlSet\Control\Terminal Server'
Set-ItemProperty -Path $ts -Name 'fDenyTSConnections' -Value 0 -Type DWord
Set-ItemProperty -Path "$ts\WinStations\RDP-Tcp" -Name 'UserAuthentication' -Value 1 -Type DWord   # NLA
$pol = 'HKLM:\SOFTWARE\Policies\Microsoft\Windows NT\Terminal Services'
if (-not (Test-Path $pol)) { New-Item -Path $pol -Force | Out-Null }
Set-ItemProperty -Path $pol -Name 'fDisableAudioCapture' -Value 0 -Type DWord   # izinkan perekaman audio (mikrofon redirect)
Set-ItemProperty -Path $pol -Name 'fDisableCam' -Value 0 -Type DWord
foreach ($svc in 'TermService', 'Audiosrv', 'AudioEndpointBuilder') {
    try { Set-Service -Name $svc -StartupType Automatic; if ((Get-Service $svc).Status -ne 'Running') { Start-Service $svc } }
    catch { Write-KzLog "layanan $svc : $($_.Exception.Message)" }
}

# ---- firewall ----
$port = Get-KzRdpPort
$profiles = 'Private,Domain'
if ($AllowPublic) { $profiles = 'Private,Domain,Public' }
foreach ($n in $script:FirewallTcp, $script:FirewallUdp) { Get-NetFirewallRule -DisplayName $n -ErrorAction SilentlyContinue | Remove-NetFirewallRule }
New-NetFirewallRule -DisplayName $script:FirewallTcp -Direction Inbound -Protocol TCP -LocalPort $port -Action Allow -Profile $profiles | Out-Null
New-NetFirewallRule -DisplayName $script:FirewallUdp -Direction Inbound -Protocol UDP -LocalPort $port -Action Allow -Profile $profiles | Out-Null

# ---- ID Device + password ----
$dev = Read-KzDevice
if ($ResetCredentials -or $null -eq $dev) {
    $id = New-KzDeviceId
    $pw = New-KzPassword 10
    $v = New-KzVerifier $pw
    $dev = [pscustomobject]@{
        id = $id; displayId = (Format-KzId $id); password = $pw
        salt = $v.salt; hash = $v.hash; iter = $v.iter; pendingVerifier = $false
    }
    Save-KzDevice $dev
}

# ---- akun Windows khusus (dipakai mode ID Device) ----
$secure = ConvertTo-SecureString $dev.password -AsPlainText -Force
$existing = Get-LocalUser -Name $script:Account -ErrorAction SilentlyContinue
if ($existing) {
    Set-LocalUser -Name $script:Account -Password $secure
    Enable-LocalUser -Name $script:Account
} else {
    New-LocalUser -Name $script:Account -Password $secure -PasswordNeverExpires -UserMayNotChangePassword -AccountNeverExpires -Description 'KzDesk Remote (dikelola otomatis)' | Out-Null
}
$rdpUsers = Get-LocalGroup -SID 'S-1-5-32-555'
try { Add-LocalGroupMember -Group $rdpUsers -Member $script:Account -ErrorAction Stop } catch { }
$ul = 'HKLM:\SOFTWARE\Microsoft\Windows NT\CurrentVersion\Winlogon\SpecialAccounts\UserList'
if (-not (Test-Path $ul)) { New-Item -Path $ul -Force | Out-Null }
Set-ItemProperty -Path $ul -Name $script:Account -Value 0 -Type DWord   # sembunyikan dari layar login

# ---- service latar (Task Scheduler, SYSTEM, mulai saat boot, restart otomatis) ----
Get-ScheduledTask -TaskName $script:TaskName -ErrorAction SilentlyContinue | ForEach-Object {
    Stop-ScheduledTask -TaskName $_.TaskName -ErrorAction SilentlyContinue
    Unregister-ScheduledTask -TaskName $_.TaskName -Confirm:$false
}
$script1 = Join-Path $script:InstallDir 'KzDeskHostService.ps1'
$action = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument ('-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "{0}"' -f $script1)
$trigger = New-ScheduledTaskTrigger -AtStartup
$tprincipal = New-ScheduledTaskPrincipal -UserId 'SYSTEM' -LogonType ServiceAccount -RunLevel Highest
$settings = New-ScheduledTaskSettingsSet -RestartCount 999 -RestartInterval (New-TimeSpan -Minutes 1) -ExecutionTimeLimit ([TimeSpan]::Zero) -StartWhenAvailable -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -MultipleInstances IgnoreNew
Register-ScheduledTask -TaskName $script:TaskName -Action $action -Trigger $trigger -Principal $tprincipal -Settings $settings -Description 'KzDesk Host: heartbeat direktori + update otomatis' | Out-Null
Start-ScheduledTask -TaskName $script:TaskName

# Daftar sekarang (best-effort) supaya ID langsung bisa dipakai; service akan mengulang bila gagal.
try { [void](Sync-KzDirectory) } catch { Write-KzLog "pendaftaran awal ditunda: $($_.Exception.Message)" }
Write-KzLog "setup selesai (v$(Get-KzVersion), port $port, profil firewall: $profiles)"
