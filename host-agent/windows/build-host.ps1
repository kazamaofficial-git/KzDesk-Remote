# Bangun KzDesk-Host-Setup.exe (dipakai workflow rilis). Butuh Inno Setup 6.3+ (iscc.exe).
param([Parameter(Mandatory = $true)][string]$Version)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
Set-Content -Path (Join-Path $PSScriptRoot 'VERSION') -Value $Version -NoNewline -Encoding ASCII
$env:KZ_VERSION = $Version
$iscc = (Get-Command iscc.exe -ErrorAction SilentlyContinue).Source
if (-not $iscc) { $iscc = "${env:ProgramFiles(x86)}\Inno Setup 6\ISCC.exe" }
& $iscc 'kzdesk-host.iss'
if ($LASTEXITCODE -ne 0) { throw "ISCC gagal ($LASTEXITCODE)" }
Get-Item (Join-Path $PSScriptRoot '..\..\dist\KzDesk-Host-Setup.exe')
