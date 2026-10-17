#!/usr/bin/env python3
"""Ensure release APKs agree across files, SHA256SUMS, and signature report."""

from __future__ import annotations

import hashlib
import re
import sys
from pathlib import Path

ABIS = ("armeabi-v7a", "arm64-v8a", "x86_64")
REPORT_ENTRY = re.compile(
    r"(?m)^(?P<abi>armeabi-v7a|arm64-v8a|x86_64)\s+v[^:\s]+:\s+PASS[^\r\n]*"
    r"\r?\n[ \t]+APK SHA-256:\s*(?P<sha>[0-9a-f]{64})[ \t]*$"
)


def verify(directory: Path) -> None:
    sums_path = directory / "SHA256SUMS.txt"
    report_path = directory / "SIGNATURE-VERIFICATION.txt"
    if not sums_path.is_file() or not report_path.is_file():
        raise SystemExit(f"FAIL: {directory} must contain SHA256SUMS.txt and SIGNATURE-VERIFICATION.txt")

    manifest: dict[str, str] = {}
    for line in sums_path.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        fields = line.split(maxsplit=1)
        if len(fields) != 2 or not re.fullmatch(r"[0-9a-fA-F]{64}", fields[0]):
            raise SystemExit(f"FAIL: malformed SHA256SUMS entry: {line}")
        manifest[fields[1].lstrip("* ")] = fields[0].lower()

    reported: dict[str, str] = {}
    for match in REPORT_ENTRY.finditer(report_path.read_text(encoding="utf-8")):
        abi = match.group("abi")
        if abi in reported:
            raise SystemExit(f"FAIL: duplicate {abi} APK hash in signature report")
        reported[abi] = match.group("sha")

    if set(reported) != set(ABIS):
        raise SystemExit(
            "FAIL: signature report must contain one passing APK SHA-256 for each ABI; "
            f"found {', '.join(sorted(reported)) or 'none'}"
        )

    # Sejak v1.1.8 nama aset memakai bit arsitektur, bukan nama ABI.
    NAMES = {
        "armeabi-v7a": "KzDesk-Remote32bit.apk",
        "arm64-v8a": "KzDesk-Remote64bit.apk",
        "x86_64": "KzDesk-Remote64bit-x86.apk",
    }
    for abi in ABIS:
        filename = NAMES[abi]
        apk_path = directory / filename
        if not apk_path.is_file():
            raise SystemExit(f"FAIL: missing {apk_path}")
        if filename not in manifest:
            raise SystemExit(f"FAIL: {filename} missing from SHA256SUMS.txt")
        actual = hashlib.sha256(apk_path.read_bytes()).hexdigest()
        values = {
            "APK file": actual,
            "SHA256SUMS.txt": manifest[filename],
            "SIGNATURE-VERIFICATION.txt": reported[abi],
        }
        if len(set(values.values())) != 1:
            details = "; ".join(f"{source}={digest}" for source, digest in values.items())
            raise SystemExit(f"FAIL: {filename} hash mismatch: {details}")
        print(f"PASS: {filename}: APK, SHA256SUMS.txt, and signature report agree ({actual})")


def main() -> None:
    directory = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("release-apks")
    verify(directory)


if __name__ == "__main__":
    main()
