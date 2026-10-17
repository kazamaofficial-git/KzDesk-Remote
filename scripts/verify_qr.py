#!/usr/bin/env python3
"""Verifikasi encoder QR (host-agent/windows/qr.js) dengan decoder OpenCV.
Dipakai manual/CI: python3 scripts/verify_qr.py"""
import json, subprocess, sys
import numpy as np

try:
    import cv2
except ImportError:
    print("opencv-python tidak terpasang; lewati verifikasi decode."); sys.exit(0)

SAMPLES = [
    "kzdesk://pair?v=1&id=123456789&pw=Ab3dEfGh9k",
    "HELLO",
    "https://kzdesk.kazamasite.biz.id/host",
    "A" * 60,
    "kzdesk://pair?v=1&id=987654321&pw=xYz23456Q9&note=" + "n" * 90,   # versi >= 5
    "Ünïcode ✓ — QR 日本語",
    "Z" * 150,                                                          # versi tinggi (>= 7)
]
JS = """
const q = require('./host-agent/windows/qr.js');
const out = JSON.parse(process.argv[1]).map(([t, e]) => { const r = q.encode(t, e); return {v: r.version, m: r.modules.map(row => row.map(c => c ? 1 : 0))}; });
console.log(JSON.stringify(out));
"""
jobs = [[s, e] for s in SAMPLES for e in ("L", "M")]
res = json.loads(subprocess.check_output(["node", "-e", JS, json.dumps(jobs)], text=True))
bad = 0
det = cv2.QRCodeDetector()
for (text, ecl), r in zip(jobs, res):
    m = np.array(r["m"], dtype=np.uint8)
    img = np.where(m == 1, 0, 255).astype(np.uint8)
    img = np.pad(img, 4, constant_values=255)
    img = cv2.resize(img, None, fx=10, fy=10, interpolation=cv2.INTER_NEAREST)
    got, _, _ = det.detectAndDecode(img)
    ok = got == text
    bad += not ok
    print(("OK   " if ok else "FAIL ") + f"v{r['v']:<2} {ecl} {text[:42]!r}" + ("" if ok else f" -> {got!r}"))
sys.exit(1 if bad else 0)
