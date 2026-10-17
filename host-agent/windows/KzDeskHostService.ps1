# KzDesk Host - proses latar (dijalankan Task Scheduler sebagai SYSTEM saat boot).
# Tugas: heartbeat ke direktori (agar ID Device bisa ditemukan) dan cek update berkala.
. (Join-Path $PSScriptRoot 'lib.ps1')

$created = $false
$mutex = New-Object System.Threading.Mutex($true, 'Global\KzDeskHostService', [ref]$created)
if (-not $created) { exit 0 }

Write-KzLog "service mulai (v$(Get-KzVersion))"
$nextUpdate = Get-Date
while ($true) {
    try { [void](Sync-KzDirectory) } catch { Write-KzLog "sinkron gagal: $($_.Exception.Message)" }
    if ((Get-Date) -ge $nextUpdate) {
        try { & (Join-Path $PSScriptRoot 'KzDeskUpdater.ps1') -Quiet } catch { Write-KzLog "cek update gagal: $($_.Exception.Message)" }
        $nextUpdate = (Get-Date).AddHours(6)
    }
    Start-Sleep -Seconds 60
}
