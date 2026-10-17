# Tampilkan ID Device + password + QR pairing (dibuka lewat pintasan Start Menu dengan hak Administrator).
# Halaman dibuat lokal (tanpa jaringan), dibuka di browser bawaan, lalu dihapus.
. (Join-Path $PSScriptRoot 'lib.ps1')
Add-Type -AssemblyName System.Windows.Forms
$dev = Read-KzDevice
if ($null -eq $dev) { [void][System.Windows.Forms.MessageBox]::Show('KzDesk Host belum dikonfigurasi.', 'KzDesk Host'); return }

$qrFile = Join-Path $PSScriptRoot 'qr.js'
$text = "ID Device : {0}`r`nPassword  : {1}" -f $dev.displayId, $dev.password
if (-not (Test-Path $qrFile)) { [void][System.Windows.Forms.MessageBox]::Show($text, 'KzDesk Host'); return }

$payload = 'kzdesk://pair?v=1&id={0}&pw={1}' -f $dev.id, [Uri]::EscapeDataString($dev.password)
$html = @'
<!doctype html><html lang="id"><head><meta charset="utf-8"><title>KzDesk Host</title>
<meta name="viewport" content="width=device-width, initial-scale=1">
<style>
body{margin:0;background:#07070b;color:#f4f4fa;font-family:Segoe UI,system-ui,sans-serif;display:grid;place-items:center;min-height:100vh}
.card{background:#0f0f17;border:1px solid #25253a;border-radius:20px;padding:32px;max-width:420px;text-align:center}
h1{font-size:20px;margin:0 0 4px}p{color:#b4b4cc;font-size:14px;margin:6px 0}
.id{font-size:34px;font-weight:800;letter-spacing:.06em;margin:18px 0 4px}
.pw{font-family:Consolas,monospace;font-size:20px;background:#171724;border-radius:10px;padding:8px 12px;display:inline-block}
#qr{background:#fff;border-radius:14px;padding:6px;display:inline-block;margin:18px 0 6px}#qr svg{display:block}
</style></head><body><div class="card">
<h1>KzDesk Host</h1><p>Masukkan di app KzDesk Remote &rarr; ID Device + Password, atau scan QR ini.</p>
<div class="id" id="id"></div><div class="pw" id="pw"></div>
<div><div id="qr"></div></div>
<p>Halaman ini hanya ada di PC ini dan akan dihapus otomatis. Jangan bagikan QR/password ke orang lain.</p>
</div>
<script>{{QRJS}}</script>
<script>
var d = {{DATA}};
document.getElementById('id').textContent = d.id;
document.getElementById('pw').textContent = d.pw;
document.getElementById('qr').innerHTML = KzQR.toSvg(KzQR.encode(d.payload, 'M'), 2, 6);
</script></body></html>
'@
$data = ([pscustomobject]@{ id = $dev.displayId; pw = $dev.password; payload = $payload } | ConvertTo-Json -Compress)
# Hindari penutupan tag skrip secara tidak sengaja di dalam data.
$data = $data.Replace('</', '<\/')
$html = $html.Replace('{{QRJS}}', (Get-Content $qrFile -Raw)).Replace('{{DATA}}', $data)

$file = Join-Path $env:TEMP ('kzdesk-pair-{0}.html' -f [guid]::NewGuid().ToString('N'))
[IO.File]::WriteAllText($file, $html, (New-Object Text.UTF8Encoding($false)))
try {
    Start-Process $file
    Start-Sleep -Seconds 90
} catch {
    [void][System.Windows.Forms.MessageBox]::Show($text, 'KzDesk Host')
} finally {
    Remove-Item $file -Force -ErrorAction SilentlyContinue
}
