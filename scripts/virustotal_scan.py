#!/usr/bin/env python3
"""Scan aset rilis KzDesk Remote ke VirusTotal (API publik v3).

Kenapa ada: laporan VirusTotal adalah bukti pihak ketiga bahwa biner rilis
tidak mengandung malware. Skrip ini memindai setiap aset rilis, lalu menulis
laporan teks apa adanya (tanpa klaim tambahan) supaya bisa ditempel sebagai
aset rilis `VIRUSTOTAL.txt`.

Catatan penting
- Kunci API dibaca dari env `VT_API_KEY` (GitHub Actions secret). Skrip TIDAK
  pernah menuliskan kunci ke berkas laporan maupun log.
- API publik dibatasi 4 request/menit -> semua request diberi jeda minimum
  16 detik lewat `_RateLimiter`.
- Berkas > 32 MB wajib lewat endpoint bigfiles (`GET /files/upload_url`).
- Tanpa dependensi eksternal (hanya pustaka standar) supaya bisa jalan di
  runner GitHub tanpa `pip install`.

Pakai:
    VT_API_KEY=... python3 scripts/virustotal_scan.py --dir vt-assets --out VIRUSTOTAL.txt
Opsi:
    --tag v0.5.29     label rilis pada laporan (default: nama direktori)
    --dry-run         hitung SHA-256 saja, tanpa memanggil API
    --strict          keluar dengan kode 1 kalau ada engine menandai malicious
"""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import mimetypes
import os
import re
import sys
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path

API = "https://www.virustotal.com/api/v3"
SMALL_UPLOAD_LIMIT = 32 * 1024 * 1024  # di atas ini pakai bigfiles
MIN_INTERVAL = 16.0                    # detik antar request (batas 4/menit)
POLL_INTERVAL = 20.0                   # jeda antar pemeriksaan hasil analisis
POLL_MAX = 45                          # ~15 menit per berkas
PENDING_MAX = 8                        # tunggu hasil tersimpan yang masih kosong
MAX_UPLOAD_BYTES = 650 * 1024 * 1024   # batas keras VirusTotal

# Aset teks/dokumen tidak perlu dipindai; APK/EXE/DLL/ZIP/MSI yang relevan.
SKIP_SUFFIX = {".txt", ".md", ".json", ".sha256"}


class RateLimiter:
    def __init__(self, interval: float) -> None:
        self.interval = interval
        self.last = 0.0

    def wait(self) -> None:
        delta = time.monotonic() - self.last
        if delta < self.interval:
            time.sleep(self.interval - delta)
        self.last = time.monotonic()


_rl = RateLimiter(MIN_INTERVAL)


def request(method: str, url: str, key: str, data: bytes | None = None,
            ctype: str | None = None, timeout: int = 900) -> dict:
    _rl.wait()
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("x-apikey", key)
    req.add_header("Accept", "application/json")
    if ctype:
        req.add_header("Content-Type", ctype)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            body = resp.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", "replace")[:300]
        raise RuntimeError(f"HTTP {exc.code} dari VirusTotal: {detail}") from exc
    return json.loads(body) if body else {}


def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def multipart(path: Path) -> tuple[bytes, str]:
    boundary = "----xyvt" + uuid.uuid4().hex
    mime = mimetypes.guess_type(path.name)[0] or "application/octet-stream"
    head = (
        f"--{boundary}\r\n"
        f'Content-Disposition: form-data; name="file"; filename="{path.name}"\r\n'
        f"Content-Type: {mime}\r\n\r\n"
    ).encode()
    tail = f"\r\n--{boundary}--\r\n".encode()
    return head + path.read_bytes() + tail, f"multipart/form-data; boundary={boundary}"


def existing_report(key: str, sha: str) -> dict | None:
    try:
        payload = request("GET", f"{API}/files/{sha}", key)
    except RuntimeError as exc:
        if "HTTP 404" in str(exc):
            return None
        raise
    return payload.get("data", {}).get("attributes")


def upload(key: str, path: Path) -> dict:
    body, ctype = multipart(path)
    if path.stat().st_size <= SMALL_UPLOAD_LIMIT:
        payload = request("POST", f"{API}/files", key, body, ctype)
    else:
        target = request("GET", f"{API}/files/upload_url", key)["data"]
        payload = request("POST", target, key, body, ctype)
    return payload["data"]


def wait_for_analysis(key: str, analysis_id: str, log=print) -> None:
    for attempt in range(1, POLL_MAX + 1):
        payload = request("GET", f"{API}/analyses/{analysis_id}", key)
        status = payload.get("data", {}).get("attributes", {}).get("status")
        if status == "completed":
            return
        if attempt % 5 == 0:
            log(f"      menunggu analisis... ({attempt}x, status={status})")
        time.sleep(POLL_INTERVAL)
    raise RuntimeError(f"analisis tidak selesai setelah {POLL_MAX} percobaan")


def stats_of(attrs: dict) -> dict:
    stats = dict(attrs.get("last_analysis_stats") or attrs.get("stats") or {})
    return {k: int(v) for k, v in stats.items() if isinstance(v, (int, float))}


def flagged_of(attrs: dict) -> list[str]:
    results = attrs.get("last_analysis_results") or attrs.get("results") or {}
    out = []
    for engine, res in results.items():
        category = (res or {}).get("category")
        if category in {"malicious", "suspicious"}:
            label = (res or {}).get("result") or "(tidak ada nama)"
            out.append(f"{engine}: {label}")
    return sorted(out)


def attribut_siap(key: str, sha: str, log=print) -> dict | None:
    """Ambil atribut berkas yang sudah ada di VirusTotal.

    Kalau hash-nya dikenal tapi seluruh mesin masih nol (artinya analisis
    tersimpan belum jalan), tunggu sebentar; kalau tetap kosong kembalikan
    None supaya berkas diunggah ulang dan diproses sebagai analisis baru.
    """
    for attempt in range(PENDING_MAX):
        attrs = existing_report(key, sha)
        if attrs is None:
            return None
        if sum(stats_of(attrs).values()) > 0:
            return attrs
        if attempt == 0:
            log("      ada di VirusTotal tapi mesin belum selesai -> menunggu")
        time.sleep(POLL_INTERVAL)
    log("      hasil tersimpan masih kosong -> unggah ulang untuk analisis baru")
    return None


def scan_file(key: str, path: Path, log=print) -> dict:
    size = path.stat().st_size
    log(f"  {path.name} ({size:,} byte)")
    sha = sha256_of(path)
    record: dict = {"name": path.name, "size": size, "sha256": sha}

    attrs = attribut_siap(key, sha, log=log)
    if attrs:
        log("      sudah ada di VirusTotal -> pakai hasil yang tersimpan")
        record["source"] = "hasil tersimpan"
    else:
        if size > MAX_UPLOAD_BYTES:
            record["error"] = "melebihi batas 650 MB VirusTotal -> dilewati"
            return record
        log("      mengunggah...")
        analysis = upload(key, path)
        analysis_id = analysis.get("id")
        record["analisis"] = analysis_id
        wait_for_analysis(key, analysis_id, log=log)
        attrs = request("GET", f"{API}/files/{sha}", key).get("data", {}).get("attributes") or {}
        record["source"] = "unggah baru"

    record["stats"] = stats_of(attrs)
    record["flagged"] = flagged_of(attrs)
    log(f"      hasil: {record['stats']}")
    return record


def tulis_laporan(records: list[dict], out: Path, tag: str) -> None:
    now = dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%d %H:%M UTC")
    total_mal = sum(r.get("stats", {}).get("malicious", 0) for r in records)
    total_sus = sum(r.get("stats", {}).get("suspicious", 0) for r in records)
    lines = [
        "# KzDesk Remote - Laporan Scan VirusTotal",
        "",
        f"- Rilis   : {tag}",
        f"- Waktu   : {now}",
        f"- Metode  : API VirusTotal v3 (unggah aset apa adanya, hasil ditulis tanpa penyuntingan)",
        f"- Ringkas : {len(records)} berkas dipindai - malicious {total_mal} - suspicious {total_sus}",
        "",
    ]
    for rec in records:
        lines.append(f"## {rec['name']}")
        lines.append(f"- Ukuran  : {rec['size']:,} byte")
        lines.append(f"- SHA-256 : {rec['sha256']}")
        if rec.get("error"):
            lines.append(f"- Catatan : {rec['error']}")
            lines.append("")
            continue
        lines.append(f"- Tautan  : https://www.virustotal.com/gui/file/{rec['sha256']}")
        stats = rec.get("stats", {})
        urut = ["malicious", "suspicious", "harmless", "undetected", "timeout", "failure", "type-unsupported"]
        rincian = " - ".join(f"{k} {stats[k]}" for k in urut if k in stats)
        lines.append(f"- Deteksi : {rincian or 'belum ada data'}")
        if rec.get("flagged"):
            lines.append("- Engine yang menandai:")
            lines.extend(f"    - {item}" for item in rec["flagged"])
        lines.append("")
    if total_mal:
        lines += [
            "## Catatan soal penandaan di atas",
            "",
            "Penilaian di atas datang dari mesin otomatis VirusTotal. Untuk biner Windows kecil",
            "yang belum ditandatangani secara digital, beberapa mesin heuristik/ML rutin memberi",
            "label generik (misalnya nama berakhiran `Wacatac`, `Genus`, atau `Malware.<kode>`).",
            "Yang perlu diperiksa dua hal: (1) SHA-256 di atas persis sama dengan berkas di halaman",
            "rilis, jadi berkas yang dipindai = berkas yang diunduh siapa pun; dan (2) laporan rilis",
            "lengkap ada di repositori publik. Cara membuat penandaan hilang sepenuhnya adalah",
            "menandatangani biner Windows dengan sertifikat code signing.",
            "",
        ]

    lines += [
        "## Verifikasi mandiri",
        "",
        "1. Unduh berkasnya dari halaman rilis KzDesk Remote.",
        "2. Hitung SHA-256: Windows `certutil -hashfile <berkas> SHA256`, Linux/macOS `sha256sum <berkas>`.",
        "3. Cocokkan dengan nilai di atas atau dengan `SHA256SUMS.txt` dari rilis yang sama.",
        "4. Buka tautan VirusTotal di atas untuk melihat detail per engine.",
        "",
        "Kalau hash cocok, berkas yang Anda jalankan identik dengan berkas yang dipindai di sini.",
        "",
    ]
    out.write_text("\n".join(lines), encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description="Scan aset rilis ke VirusTotal")
    parser.add_argument("--dir", help="direktori aset rilis")
    parser.add_argument("--files", nargs="*", default=[], help="daftar berkas (alternatif --dir)")
    parser.add_argument("--out", default="VIRUSTOTAL.txt", help="berkas laporan keluaran")
    parser.add_argument("--tag", default="", help="label rilis pada laporan")
    parser.add_argument("--dry-run", action="store_true", help="hanya hitung SHA-256, tanpa API")
    parser.add_argument("--strict", action="store_true", help="keluar 1 kalau ada deteksi malicious")
    args = parser.parse_args()

    key = os.environ.get("VT_API_KEY", "").strip()
    if not args.dry_run and not re.fullmatch(r"[0-9a-fA-F]{64}", key):
        print("VT_API_KEY tidak ada / tidak berbentuk kunci API 64 heksadesimal.", file=sys.stderr)
        return 0 if not args.strict else 1

    kandidat: list[Path] = []
    if args.dir:
        kandidat += sorted(p for p in Path(args.dir).iterdir()
                           if p.is_file() and p.suffix.lower() not in SKIP_SUFFIX)
    kandidat += [Path(f) for f in args.files]
    kandidat = [p for p in kandidat if p.is_file()]
    if not kandidat:
        print("Tidak ada berkas untuk dipindai.", file=sys.stderr)
        return 0

    tag = args.tag or (Path(args.dir).name if args.dir else "aset lokal")
    print(f"Memindai {len(kandidat)} berkas untuk {tag} (jeda {MIN_INTERVAL:.0f}s antar request)...")

    records: list[dict] = []
    for path in kandidat:
        if args.dry_run:
            rec = {"name": path.name, "size": path.stat().st_size, "sha256": sha256_of(path)}
            print(f"  {path.name}: {rec['sha256']}")
        else:
            try:
                rec = scan_file(key, path)
            except Exception as exc:  # satu berkas gagal tidak menggagalkan sisanya
                rec = {"name": path.name, "size": path.stat().st_size,
                       "sha256": sha256_of(path), "error": f"gagal: {exc}"}
                print(f"      GAGAL: {exc}", file=sys.stderr)
        records.append(rec)

    tulis_laporan(records, Path(args.out), tag)
    print(f"Laporan ditulis ke {args.out}")

    if args.strict and any(r.get("stats", {}).get("malicious", 0) for r in records):
        print("Ada engine menandai malicious.", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
