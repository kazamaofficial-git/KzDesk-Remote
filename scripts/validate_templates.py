#!/usr/bin/env python3
"""Validasi berkas Template Controller (JSON) terhadap aturan yang sama dengan aplikasi.

Cermin dari ControllerTemplates.parse + HudKey.fromJson (Kotlin). Pakai:
    python3 scripts/validate_templates.py templates/*.json
Keluar dengan kode 1 bila ada berkas yang tidak valid.
"""
import json
import sys

FORMAT = "kzdesk.controller-template"
BUNDLE = "kzdesk.controller-template-bundle"
SCHEMA = 1
MAX_BYTES, MAX_KEYS, MAX_BUNDLE = 256 * 1024, 36, 20
KINDS = {"MOUSE_LEFT", "MOUSE_RIGHT", "MOUSE_MIDDLE", "MOUSE_SWAP", "SCROLL_UP", "SCROLL_DOWN",
         "SCROLL_SLIDER", "PAD_STICK", "INPUT_SWITCH", "KEYBOARD", "KEY", "COMBO", "MACRO"}
ACTIONS = {"TAP", "ONE_SHOT", "HOLD", "TOGGLE"}
ALLOWED = {"KEY": ACTIONS, "MOUSE_LEFT": {"TAP", "HOLD", "TOGGLE"},
           "MOUSE_RIGHT": {"TAP", "HOLD", "TOGGLE"}, "MOUSE_MIDDLE": {"TAP", "HOLD", "TOGGLE"}}
STICK_MODES = {"WASD", "ARROWS", "POINTER"}
SIZE = (34.0, 96.0)
SENS = (0.3, 3.0)
DEAD = (0.05, 0.5)


def check_key(k, where, errors, ids):
    def err(msg): errors.append(f"{where}: {msg}")
    if not isinstance(k, dict): return err("bukan objek")
    kid = k.get("id")
    if not isinstance(kid, str) or not kid.strip(): return err("id kosong")
    if kid in ids: err(f"id ganda '{kid}'")
    ids.add(kid)
    if kid.startswith("aux_"): err("id 'aux_*' dicadangkan")
    kind = k.get("kind")
    if kind not in KINDS: return err(f"kind tidak dikenal: {kind!r}")
    if kind == "KEYBOARD": err("kind KEYBOARD tidak diimpor")
    action = k.get("action", "TAP")
    if action not in ACTIONS: err(f"action tidak dikenal: {action!r}")
    elif action not in ALLOWED.get(kind, {"TAP"}): err(f"action {action} tidak diizinkan untuk {kind}")
    for axis in ("x", "y"):
        v = k.get(axis, 0.5)
        if not isinstance(v, (int, float)) or not 0 <= v <= 1: err(f"{axis} harus 0..1 (nilai {v!r})")
    s = k.get("size", 46)
    if not isinstance(s, (int, float)) or not SIZE[0] <= s <= SIZE[1]: err(f"size harus {SIZE[0]}..{SIZE[1]}")
    if kind in ("KEY",) and not isinstance(k.get("code"), int): err("KEY butuh 'code' (Android KeyEvent)")
    if kind == "COMBO":
        c = k.get("combo")
        if not (isinstance(c, list) and 2 <= len(c) <= 5 and all(isinstance(i, int) for i in c)): err("COMBO butuh 'combo' 2..5 kode")
    if kind == "MACRO" and not k.get("macroText"): err("MACRO butuh 'macroText'")
    if "stickMode" in k:
        if kind != "PAD_STICK": err("stickMode hanya untuk PAD_STICK")
        elif k["stickMode"] not in STICK_MODES: err(f"stickMode harus salah satu {sorted(STICK_MODES)}")
    for field, (lo, hi) in (("stickSens", SENS), ("stickDead", DEAD)):
        if field in k and not (isinstance(k[field], (int, float)) and lo <= k[field] <= hi):
            err(f"{field} harus {lo}..{hi}")


def check_template(t, where, errors):
    if not isinstance(t, dict): return errors.append(f"{where}: bukan objek")
    if t.get("format") not in (FORMAT, None): errors.append(f"{where}: format harus '{FORMAT}'")
    if t.get("schema", 1) > SCHEMA: errors.append(f"{where}: schema lebih baru dari {SCHEMA}")
    for f, mx in (("name", 40), ("author", 40), ("description", 200)):
        if f in t and (not isinstance(t[f], str) or len(t[f]) > mx): errors.append(f"{where}: {f} maks {mx} karakter")
    if not str(t.get("name", "")).strip(): errors.append(f"{where}: name wajib")
    keys = t.get("keys")
    if not isinstance(keys, list) or not keys: return errors.append(f"{where}: keys kosong")
    if len(keys) > MAX_KEYS: errors.append(f"{where}: maksimal {MAX_KEYS} tombol (ada {len(keys)})")
    ids = set()
    for i, k in enumerate(keys): check_key(k, f"{where}.keys[{i}]", errors, ids)


def validate(path):
    errors = []
    raw = open(path, "rb").read()
    if len(raw) > MAX_BYTES: return ["berkas > 256 KB"]
    try: data = json.loads(raw)
    except json.JSONDecodeError as e: return [f"JSON tidak valid: {e}"]
    if isinstance(data, list): check_template({"name": "x", "keys": data}, "array", errors)
    elif data.get("format") == BUNDLE:
        ts = data.get("templates", [])
        if not ts or len(ts) > MAX_BUNDLE: errors.append(f"bundel harus 1..{MAX_BUNDLE} template")
        for i, t in enumerate(ts): check_template(t, f"templates[{i}]", errors)
    else: check_template(data, "template", errors)
    return errors


if __name__ == "__main__":
    bad = 0
    for p in sys.argv[1:]:
        e = validate(p)
        print(("OK   " if not e else "FAIL ") + p)
        for line in e: print("   -", line)
        bad += bool(e)
    sys.exit(1 if bad else 0)
