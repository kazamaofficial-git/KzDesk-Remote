# KzDesk Host - pembersihan saat uninstall. Pengaturan Remote Desktop dibiarkan seperti semula-nya aktif.
. (Join-Path $PSScriptRoot 'lib.ps1')
$ErrorActionPreference = 'Continue'

try {
    $dev = Read-KzDevice
    if ($dev -and ($dev.PSObject.Properties.Name -contains 'hostSecret') -and $dev.hostSecret) {
        Invoke-KzApi '/api/device/unregister' @{ id = $dev.id; host_secret = $dev.hostSecret } | Out-Null
    }
} catch { }
foreach ($t in $script:TaskName, 'KzDesk Host Update') {
    Stop-ScheduledTask -TaskName $t -ErrorAction SilentlyContinue
    Unregister-ScheduledTask -TaskName $t -Confirm:$false -ErrorAction SilentlyContinue
}
foreach ($n in $script:FirewallTcp, $script:FirewallUdp) { Get-NetFirewallRule -DisplayName $n -ErrorAction SilentlyContinue | Remove-NetFirewallRule }
Remove-LocalUser -Name $script:Account -ErrorAction SilentlyContinue
Remove-ItemProperty -Path 'HKLM:\SOFTWARE\Microsoft\Windows NT\CurrentVersion\Winlogon\SpecialAccounts\UserList' -Name $script:Account -ErrorAction SilentlyContinue
Remove-Item -Path $script:DataDir -Recurse -Force -ErrorAction SilentlyContinue
