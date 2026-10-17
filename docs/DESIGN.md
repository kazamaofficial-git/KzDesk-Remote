# KzDesk Remote Android — UI direction

## Product feel

Calm, precise, tool-first. The remote desktop remains the main content; controls should be legible without looking like an overlay dashboard. Keep KzDesk's existing typography, small rounded surfaces, 4/8 dp spacing rhythm, and restrained status color. Do not add glow, gradients, or decorative glass surfaces.

## Theme and surfaces

- App pages, settings, dialogs, editor panels, and text use the semantic `DarkScheme` / `LightScheme` tokens from `ui/theme/KzDeskTheme.kt`.
- Session controls sit over arbitrary remote pixels, so their button plates stay neutral dark translucent in every app theme. Offer strong, soft, and transparent dark treatments; never use an opaque white disc behind an icon.
- Editing chrome follows the app theme. The HUD remains circular, round, and visually compact; scroll-up and scroll-down controls remain independently movable.
- Status colors are reserved for active/latched/error states. Text and icons must retain readable contrast over both light and dark remote desktops.

## Interaction and accessibility

- Touch targets should be at least 44 dp where layout permits.
- Size sliders update the preview and persisted session layout continuously; provide a readable value and accessibility progress semantics.
- Explicit layout mode remains the only way to drag controls; a long press must not toggle edit mode.
- Preserve per-device layout preferences and clamp values to supported ranges. Avoid overwriting custom sizes unless the user invokes an explicit “apply to all” action.
- Support Bahasa Indonesia and English through existing `kz()`/localization paths. Do not log clipboard contents.

## Brand and assets

Use the existing KzDesk-owned inline icons and the exact attribution `Powered by KazamaDev` in About. Keep one app brand constant; do not add third-party icon/font assets without a compatible license entry in `THIRD_PARTY_NOTICES.md`.
