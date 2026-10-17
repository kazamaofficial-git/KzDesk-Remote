#!/usr/bin/env python3
"""Check APK ZIP entries and the native libcrypto payload before/after signing."""

from __future__ import annotations

import sys
import zipfile
from pathlib import Path

ABIS = ("armeabi-v7a", "arm64-v8a", "x86_64")

# Sejak v1.1.8 nama aset memakai bit arsitektur tanpa token ABI.
BIT_NAMES = {
    "KzDesk-Remote32bit.apk": "armeabi-v7a",
    "KzDesk-Remote64bit.apk": "arm64-v8a",
    "KzDesk-Remote64bit-x86.apk": "x86_64",
}


def verify(path: Path) -> None:
    if path.name in BIT_NAMES:
        abi = BIT_NAMES[path.name]
    else:
        abi_matches = [abi for abi in ABIS if abi in path.name]
        if len(abi_matches) != 1:
            raise SystemExit(f"FAIL: cannot determine a single Android ABI from {path.name}")
        abi = abi_matches[0]
    required_library = f"lib/{abi}/libcrypto.so"

    try:
        with zipfile.ZipFile(path) as apk:
            names = set(apk.namelist())
            if required_library not in names:
                raise SystemExit(f"FAIL: {path}: missing {required_library}")
            bad_entry = apk.testzip()
            if bad_entry is not None:
                raise SystemExit(f"FAIL: {path}: ZIP CRC/decompression error in {bad_entry}")
    except (OSError, zipfile.BadZipFile) as error:
        raise SystemExit(f"FAIL: {path}: invalid APK/ZIP: {error}") from error

    print(f"PASS: {path}: every ZIP entry verified; {required_library} present")


def main() -> None:
    if len(sys.argv) < 2:
        raise SystemExit("Usage: verify_android_apks.py APK [APK ...]")
    for name in sys.argv[1:]:
        verify(Path(name))


if __name__ == "__main__":
    main()
