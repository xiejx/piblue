#!/usr/bin/env bash
# Install (or re-install) the PiBlue BLE agent on a Raspberry Pi.
#
#   curl-free, local usage:   sudo ./scripts/install.sh
#
# This is the *deployment* path: it copies to /opt/piblue, writes /etc/piblue
# and installs a systemd unit. For debugging, use ./scripts/dev.sh instead -
# it runs straight from this folder and touches nothing outside it.
#
# Idempotent: safe to run again after editing the Python code.
set -euo pipefail

APP_DIR=/opt/piblue
CFG_DIR=/etc/piblue
UNIT=/etc/systemd/system/piblue.service
SRC="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

log() { printf '\033[1;32m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m!!!\033[0m %s\n' "$*"; }

if [[ $EUID -ne 0 ]]; then
  echo "please run with sudo: sudo $0" >&2
  exit 1
fi

if ! command -v apt-get >/dev/null 2>&1; then
  echo "this script expects Raspberry Pi OS (apt-get not found)" >&2
  exit 1
fi

log "installing system packages"
apt-get update -qq
DEBIAN_FRONTEND=noninteractive apt-get install -y -qq \
  python3-venv python3-dbus python3-gi bluez >/dev/null

log "enabling the bluetooth stack"
systemctl enable --now bluetooth >/dev/null 2>&1 || warn "could not start bluetooth.service"
rfkill unblock bluetooth 2>/dev/null || true
sleep 1
bluetoothctl power on >/dev/null 2>&1 || warn "bluetoothctl power on failed"

log "copying code to $APP_DIR"
mkdir -p "$APP_DIR"
rm -rf "$APP_DIR/agent"
cp -r "$SRC/agent" "$APP_DIR/"
cp -r "$SRC/tools" "$APP_DIR/" 2>/dev/null || true

log "preparing the virtualenv (system site packages kept for older bless/dbus builds)"
if [[ ! -x "$APP_DIR/venv/bin/python" ]]; then
  python3 -m venv --system-site-packages "$APP_DIR/venv"
fi
"$APP_DIR/venv/bin/pip" install --quiet --upgrade pip

log "installing python requirements (this one needs the internet)"
if ! "$APP_DIR/venv/bin/pip" install --quiet -r "$SRC/requirements.txt"; then
  warn "pip could not reach PyPI. On a slow network retry with a mirror:"
  echo "      sudo PIP_INDEX_URL=https://pypi.tuna.tsinghua.edu.cn/simple $0"
  echo "    (or pre-download the wheels and use: sudo PIP_NO_INDEX=1 PIP_FIND_LINKS=/path/to/wheels $0)"
  exit 1
fi
"$APP_DIR/venv/bin/python" - <<'PY'
from importlib.metadata import version
print("    bless", version("bless"))
PY

log "installing config to $CFG_DIR/config.json"
mkdir -p "$CFG_DIR"
if [[ -f "$CFG_DIR/config.json" ]]; then
  warn "existing config kept; compare with config.example.json for new keys"
else
  cp "$SRC/config.example.json" "$CFG_DIR/config.json"
fi

# The pairing code gates the command layer: without it anyone in radio range
# could run shell commands. Generated once, never overwritten by a reinstall -
# rotating it would silently lock out a phone that already knows the old one.
log "preparing the pairing code"
PAIR_CODE="$(cd "$APP_DIR" && "$APP_DIR/venv/bin/python" - "$CFG_DIR/token" <<'PY'
import sys
from agent.token import ensure_token, pretty

token, created = ensure_token(sys.argv[1])
print(pretty(token), file=sys.stdout)
print("new" if created else "kept", file=sys.stderr)
PY
)"

log "installing systemd unit"
install -m 0644 "$SRC/systemd/piblue.service" "$UNIT"
systemctl daemon-reload
systemctl enable piblue.service >/dev/null
systemctl restart piblue.service
sleep 2

# The only thing you need after setup is the code, and nobody remembers
# "sudo /opt/piblue/venv/bin/python -m agent.token show". Short name, same tool.
log "installing the piblue-token helper"
cat > /usr/local/bin/piblue-token <<'SH'
#!/bin/sh
# Show or change the PiBlue pairing code. Needs root: /etc/piblue/token is 0600.
#   sudo piblue-token                 show the code in use
#   sudo piblue-token set MYPI-HOME-PASS-KLMN    pick your own
#   sudo piblue-token new             roll a random one
cd /opt/piblue || exit 1
exec ./venv/bin/python -m agent.token "$@"
SH
chmod 0755 /usr/local/bin/piblue-token

if systemctl is-active --quiet piblue.service; then
  log "agent is running"
else
  warn "agent failed to start - check: journalctl -u piblue -n 40 --no-pager"
  exit 1
fi

echo
log "done. from your phone, scan for a BLE device named '$(python3 - <<'PY'
import json, pathlib
p = pathlib.Path("/etc/piblue/config.json")
print(json.loads(p.read_text()).get("ble", {}).get("name", "PiBlue") if p.exists() else "PiBlue")
PY
)'"
echo "    pairing code:  $PAIR_CODE"
echo "    (enter it once in the app when it asks; it never travels over BLE)"
echo "    show again:    sudo piblue-token"
echo "    pick your own: sudo piblue-token set MYPI-HOME-PASS-KLMN"
echo "    rotate:        sudo piblue-token new"
echo "    logs:     journalctl -u piblue -f"
echo "    restart:  sudo systemctl restart piblue"
echo "    adapter:  bluetoothctl show | head -5"
