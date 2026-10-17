# Template Controller (JSON)

Template menyimpan tombol, kombinasi, makro, dan **joystick** HUD beserta posisinya.
Buka di sesi: **Panel → Template Controller**. Dari sana kamu bisa menerapkan template bawaan,
menyimpan tata letak saat ini, dan mengimpor/mengekspor JSON (berkas, clipboard, atau tempel teks).

Contoh siap pakai ada di folder [`templates/`](../templates):
`fps-joystick.json`, `racing.json`, `presentasi.json`, dan `bundle-semua.json`.

## Format

```json
{
  "format": "kzdesk.controller-template",
  "schema": 1,
  "name": "FPS Joystick",
  "author": "KazamaDev",
  "description": "Joystick WASD di kiri, kamera di kanan.",
  "keys": [
    { "id": "fps_move", "kind": "PAD_STICK", "label": "Gerak", "x": 0.06, "y": 0.56, "size": 92,
      "stickMode": "WASD" },
    { "id": "fps_look", "kind": "PAD_STICK", "label": "Kamera", "x": 0.80, "y": 0.56, "size": 92,
      "stickMode": "POINTER", "stickSens": 1.6 },
    { "id": "fps_jump", "kind": "KEY", "label": "Lompat", "code": 62, "action": "TAP",
      "x": 0.90, "y": 0.34 }
  ]
}
```

| Field | Keterangan |
|---|---|
| `format` | `kzdesk.controller-template` (opsional saat impor) |
| `schema` | `1`. Template dengan schema lebih baru ditolak |
| `name` | Wajib, maks 40 karakter |
| `author`, `description` | Opsional, maks 40 / 200 karakter |
| `keys` | 1–36 tombol |

### Tombol (`keys[]`)

| Field | Nilai |
|---|---|
| `id` | Unik di dalam template. Duplikat otomatis diberi akhiran |
| `kind` | `MOUSE_LEFT` `MOUSE_RIGHT` `MOUSE_MIDDLE` `MOUSE_SWAP` `SCROLL_UP` `SCROLL_DOWN` `SCROLL_SLIDER` `PAD_STICK` `INPUT_SWITCH` `KEY` `COMBO` `MACRO` |
| `label` | Teks di tombol (maks 10–12 karakter tampil) |
| `code` | Kode `android.view.KeyEvent` untuk `KEY` (mis. Space = 62, W = 51) |
| `combo` | 2–5 kode tombol untuk `COMBO` |
| `action` | `TAP`, `ONE_SHOT`, `HOLD`, `TOGGLE`. Tombol mouse: `TAP/HOLD/TOGGLE`. Jenis lain: `TAP` |
| `x`, `y` | Posisi 0..1 dari kiri-atas layar sesi |
| `size` | 34–96 (dp) |
| `macroText`, `macroEnter` | Untuk `MACRO` |

### Joystick (`kind: "PAD_STICK"`)

| Field | Nilai |
|---|---|
| `stickMode` | `WASD`, `ARROWS`, atau `POINTER` (gerak kursor/kamera). Tanpa field ini joystick mengikuti pengaturan global |
| `stickSens` | 0.3–3.0, sensitivitas untuk `POINTER` (bawaan 1.0) |
| `stickDead` | 0.05–0.5, deadzone radial (bawaan 0.15) |

> `POINTER` menggerakkan kursor Windows. Game yang membutuhkan *relative mouse look*
> sungguhan (kamera FPS yang mengunci kursor) bergantung pada dukungan sesi RDP-nya.

## Impor yang diterima

1. File template (di atas).
2. **Bundel**: `{"format":"kzdesk.controller-template-bundle","templates":[ ... ]}` (maks 20).
3. Ekspor tata letak lama (`{"app":"KzDesk Remote","keys":[...]}`).
4. Array polos berisi tombol.

## Keamanan impor

- Ukuran maksimum 256 KB, maksimum 36 tombol per template, 50 template tersimpan.
- Semua nilai di-*clamp* ke rentang aman; tombol tak dikenal dibuang dan dihitung.
- **Makro dari JSON impor tidak pernah menekan Enter otomatis**, dan aplikasi meminta
  konfirmasi bila template berisi makro (makro mengetik teks langsung ke PC).

## Validasi di komputer

```bash
python3 scripts/validate_templates.py templates/*.json
```
