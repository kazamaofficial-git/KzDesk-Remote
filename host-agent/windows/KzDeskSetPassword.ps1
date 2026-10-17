# Ganti password Device (juga password akun Windows "KzDesk"). Jalankan sebagai Administrator.
param([string]$Password)
. (Join-Path $PSScriptRoot 'lib.ps1')
$dev = Read-KzDevice
if ($null -eq $dev) { throw 'KzDesk Host belum dikonfigurasi.' }
if (-not $Password) { $Password = New-KzPassword 10 }
if ($Password.Length -lt 8) { throw 'Password minimal 8 karakter.' }
Set-LocalUser -Name $script:Account -Password (ConvertTo-SecureString $Password -AsPlainText -Force)
$v = New-KzVerifier $Password
$dev.password = $Password; $dev.salt = $v.salt; $dev.hash = $v.hash; $dev.iter = $v.iter
$dev | Add-Member -NotePropertyName pendingVerifier -NotePropertyValue $true -Force
Save-KzDevice $dev
try { [void](Sync-KzDirectory) } catch { Write-KzLog "sinkron password ditunda: $($_.Exception.Message)" }
Write-Host "Password baru: $Password"
