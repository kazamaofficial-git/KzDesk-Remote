# Control overlay, typing, and screen fitting

KzDesk Remote does not ship a toolbar and does not ship a virtual keyboard. All
controls are free-floating buttons that you place yourself, and typing uses the
phone keyboard (IME). This document describes the model as built in v0.5.2.

## 1. One button = one action

Every control on screen is a single round button (`CircleShape`, diameter in dp).
Nothing is merged into a shared widget: left click, right click, middle click,
scroll up, scroll down, keyboard, and the input-mode switch are seven separate
buttons, each with its own icon, position, size, and action.

| Property | Range | How to change |
|---|---|---|
| Position | normalised 0..1 of the session area | drag the button on screen (any time, not only in arrange mode) |
| Size | 24..140 dp (diameter, radius = half) | long-press the button, or the button list in the right panel |
| Action | Single tap / Hold / Toggle | button editor |
| Type | any catalogue entry (mouse actions, modifier, combo, key, numpad, letters, symbols) | "Change action type" in the editor, or "Add button" |
| Combo | Ctrl+C, Ctrl+Shift+Esc, Alt+Tab, Win+R, ... | catalogue group "Ready combos" |

Action semantics:

- **Single tap** – press and release (e.g. a plain letter, Enter, a combo).
- **Hold** – active for as long as you hold it (drag with the left button,
  continuous scroll).
- **Toggle** – first tap turns it on, second tap turns it off (e.g. Ctrl as a
  sticky modifier).

## 2. Where it lives

- **On screen** – the buttons themselves. Drag one to move it; long-press one to
  open its editor.
- **Arrange mode** – right panel → Buttons → "Arrange position". Shows a grid,
  keeps the buttons draggable, and puts a banner on top with "+ Button" and
  "Done".
- **Add button** – the "+ Button" chip in the arrange banner, or "Add button" in
  the right panel. Both open the catalogue (mouse, modifiers, ready combos,
  general keys, arrows, F1–F12, numpad, letters, symbols).
- **Reset** – "Restore default buttons" in the right panel returns the seven
  default buttons with their default positions and sizes.

Positions and sizes are stored per device (`$deviceId.hudkeys`), so a layout that
fits one screen does not fight with another.

## 3. Typing

There is no on-screen QWERTY, F1..F12, or numpad board and no key row above the
keyboard. Typing works with the phone keyboard:

- The keyboard button on the HUD (or Input → "Phone keyboard" in the right panel)
  shows/hides the IME.
- Characters typed on the IME are forwarded to the session as `KeyEvent`s, so the
  core KeyboardMapper resolves scancodes, modifiers, and layout. Characters
  without a keycode (emoji, accented letters) go through the unicode path.
- "Send text to remote" in the right panel pastes clipboard text and sends it as
  unicode, for anything that does not type well on the phone.

## 4. Taskbar and screen fitting

The remote desktop must fit the screen — otherwise the Windows taskbar and the
bottom edge of the desktop fall outside the visible area and look "sunk".

- **Fit whole desktop** (left panel → Screen; also in General settings) is on by
  default. After connecting, after a resolution change, after a rotation, and
  after any viewport change, the display is re-fitted so the whole desktop is
  visible.
- **Auto resolution** sends the real picture area (not the whole window) as the
  desktop size, so nothing is clipped by the system bars.
- **Resolution presets** are grouped by aspect ratio (16:9, 16:10, 21:9, 4:3,
  portrait) and "16:9 matched to screen" picks the largest standard 16:9 size
  that still fits. Changing the resolution keeps the whole desktop visible
  afterwards.

## 5. Cursor

The pointer uses the cursor shape sent by the server (arrow, hand, I-beam,
resize, ...) with the correct hotspot. If the server sends nothing, the built-in
arrow/dot is used. Pointer size and style are in the right panel under Pointer.


## Perubahan ronde 6 (v0.5.4)

- Tombol **terkunci** saat sesi dipakai. Geser/ubah hanya setelah menekan
  **Atur posisi & ukuran** di panel kanan (atau tahan lama satu tombol, yang
  otomatis membuka mode itu).
- Di mode atur posisi: **geser** = pindah tombol, **ketuk** = buka editor,
  **Selesai atur posisi** = keluar dari mode.
- **Latar tombol** dipilih per app: Gelap tipis (default), Terang tipis,
  Transparan — dipakai supaya ikon tetap kelihatan di atas desktop putih.
- **Rail kanan bawah** (tetap, tidak bisa dihapus): keyboard HP buka/tutup dan
  tombol keluar dengan konfirmasi dua kali ketuk.
