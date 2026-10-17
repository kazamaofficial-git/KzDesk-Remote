#!/usr/bin/env bash
# KzDesk Desktop (Linux/macOS): sambung ke PC lewat ID Device + password.
#   kzdesk-connect 123-456-789 [password]      (password juga bisa lewat env KZDESK_PASSWORD)
set -euo pipefail

API="${KZDESK_API:-https://kzdesk.kazamasite.biz.id}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FREERDP="${KZDESK_FREERDP:-$HERE/sdl3-freerdp}"
[ -x "$FREERDP" ] || FREERDP="$(command -v sdl3-freerdp || true)"
[ -n "$FREERDP" ] || { echo "sdl3-freerdp tidak ditemukan." >&2; exit 1; }

ID="$(printf '%s' "${1:-}" | tr -d ' -')"
PW="${2:-${KZDESK_PASSWORD:-}}"
if ! printf '%s' "$ID" | grep -Eq '^[1-9][0-9]{8}$'; then echo "Pemakaian: kzdesk-connect <ID 9 digit> [password]" >&2; exit 2; fi
if [ -z "$PW" ]; then read -r -s -p "Password Device: " PW; echo; fi

# TCP connect singkat. Pakai nc bila ada; kalau tidak, /dev/tcp bawaan bash.
reachable() {
  if command -v nc >/dev/null 2>&1; then nc -z -w 2 "$1" "$2" >/dev/null 2>&1; return $?; fi
  if command -v timeout >/dev/null 2>&1; then timeout 2 bash -c "exec 3<>/dev/tcp/$1/$2" >/dev/null 2>&1; return $?; fi
  bash -c "exec 3<>/dev/tcp/$1/$2" >/dev/null 2>&1
}

esc() { printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g'; }
TMP="$(mktemp)"; trap 'rm -f "$TMP"' EXIT
CODE="$(curl -sS -m 20 -o "$TMP" -w '%{http_code}' -H 'Content-Type: application/json' \
  -d "{\"id\":\"$ID\",\"password\":\"$(esc "$PW")\"}" "$API/api/device/resolve" || true)"

case "$CODE" in
  200) ;;
  404) echo "ID Device atau password salah." >&2; exit 3 ;;
  409) echo "PC sedang offline. Pastikan KzDesk Host menyala dan terhubung internet." >&2; exit 3 ;;
  429) echo "Terlalu banyak percobaan. Coba lagi sebentar lagi." >&2; exit 3 ;;
  *)   echo "Tidak bisa menghubungi server KzDesk (HTTP ${CODE:-?})." >&2; exit 3 ;;
esac

USER_NAME="$(sed -nE 's/.*"username" *: *"([^"]*)".*/\1/p' "$TMP" | head -1)"; USER_NAME="${USER_NAME:-KzDesk}"
TARGET=""
while IFS= read -r pair; do
  H="$(printf '%s' "$pair" | sed -nE 's/.*"host" *: *"([^"]*)".*/\1/p')"
  P="$(printf '%s' "$pair" | sed -nE 's/.*"port" *: *([0-9]+).*/\1/p')"
  if [ -n "$H" ] && [ -n "$P" ] && reachable "$H" "$P"; then TARGET="$H:$P"; break; fi
done < <(grep -oE '"host" *: *"[^"]*" *, *"port" *: *[0-9]+' "$TMP")

[ -n "$TARGET" ] || { echo "PC ditemukan tetapi tidak bisa dijangkau (port RDP tertutup?). Coba lewat VPN atau buka port 3389." >&2; exit 4; }
echo "Menyambung ke $TARGET sebagai $USER_NAME ..."
exec "$FREERDP" "/v:$TARGET" "/u:$USER_NAME" "/p:$PW" /cert:tofu /sound /microphone +clipboard /dynamic-resolution /gfx
