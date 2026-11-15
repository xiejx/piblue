#!/usr/bin/env bash
# Quick health check: is the stack up, is it advertising, does the config parse?
set -uo pipefail

ok()   { printf '  \033[1;32m[ok]\033[0m  %s\n' "$*"; }
bad()  { printf '  \033[1;31m[--]\033[0m  %s\n' "$*"; }
note() { printf '        %s\n' "$*"; }

echo "PiBlue diagnostics"
echo

if systemctl is-enabled --quiet bluetooth 2>/dev/null; then
  ok "bluetooth.service enabled"
else
  bad "bluetooth.service not enabled  -> sudo systemctl enable --now bluetooth"
fi

if systemctl is-active --quiet bluetooth 2>/dev/null; then
  ok "bluetooth.service running"
else
  bad "bluetooth.service not running"
fi

if command -v bluetoothctl >/dev/null; then
  if bluetoothctl show 2>/dev/null | grep -q "Powered: yes"; then
    ok "adapter powered"
  else
    bad "adapter is off  -> bluetoothctl power on"
  fi
  addr=$(bluetoothctl show 2>/dev/null | awk '/Controller/{print $2; exit}')
  [[ -n "${addr:-}" ]] && note "controller $addr"
fi

if systemctl is-active --quiet piblue 2>/dev/null; then
  ok "piblue.service running"
  systemctl show -p ActiveEnterTimestamp --value piblue 2>/dev/null | sed 's/^/        up since /'
else
  bad "piblue.service not running  -> journalctl -u piblue -n 40 --no-pager"
fi

if [[ -f /etc/piblue/config.json ]]; then
  if python3 -c 'import json,sys; json.load(open("/etc/piblue/config.json"))' 2>/dev/null; then
    ok "/etc/piblue/config.json parses"
  else
    bad "/etc/piblue/config.json is not valid JSON"
  fi
else
  bad "/etc/piblue/config.json missing"
fi

PY=/opt/piblue/venv/bin/python
if [[ -x "$PY" ]] && "$PY" -c 'import bless' 2>/dev/null; then
  ver=$("$PY" -c 'from importlib.metadata import version; print(version("bless"))' 2>/dev/null)
  ok "bless importable ($ver)"
  if [[ "$ver" == 0.1.* || "$ver" == 0.2.* ]]; then
    bad "bless < 0.3 uses txdbus, which breaks on python 3.11+"
    note "fix: sudo $PY -m pip install -U 'bless>=0.3.0'"
  fi
  if "$PY" -c 'from bless import BlessServer, GATTCharacteristicProperties, GATTAttributePermissions' 2>/dev/null; then
    ok "bless exports BlessServer + GATT enums"
  else
    bad "bless does not export the symbols the agent imports"
  fi
else
  bad "bless missing in /opt/piblue/venv"
  note "fix: cd <repo>/pi-agent && sudo ./scripts/install.sh"
fi

if command -v bluetoothctl >/dev/null; then
  if bluetoothctl list 2>/dev/null | grep -q "Controller"; then
    ok "at least one controller present"
  else
    bad "no bluetooth controller  -> on-board radio off, or no USB dongle"
  fi
fi

# Auth: without a pairing code every client is refused, which looks exactly
# like a dead link from the phone.
TOKEN=/etc/piblue/token
if [[ -f "$TOKEN" ]]; then
  mode=$(stat -c '%a' "$TOKEN" 2>/dev/null || echo "?")
  if [[ "$mode" == "600" ]]; then
    ok "pairing code present (/etc/piblue/token, 0600)"
  else
    bad "pairing code has mode $mode  -> sudo chmod 600 $TOKEN"
  fi
  if [[ -x "$PY" ]]; then
    code=$("$PY" -c '
import sys
sys.path.insert(0, "/opt/piblue")
from agent.token import pretty, read_token
t = read_token()
print(pretty(t) if t else "")
' 2>/dev/null)
    [[ -n "$code" ]] && note "code: $code  (same as the one the phone has)"
  fi
elif [[ -x "$PY" ]]; then
  bad "no pairing code - every client will be refused"
  note "fix: sudo $PY -m agent.token new"
fi

# Control page: the buttons are generated from these tools. Missing ones do not
# break anything, they just make the matching card disappear.
echo
echo "control page:"
if command -v nmcli >/dev/null; then
  ok "wifi backend: nmcli"
elif command -v wpa_cli >/dev/null; then
  ok "wifi backend: wpa_cli (no nmcli)"
else
  bad "no nmcli and no wpa_cli - the Wi-Fi card will be hidden"
fi
for tool in lsblk mount umount rfkill systemd-run; do
  if command -v "$tool" >/dev/null; then
    ok "$tool present"
  else
    bad "$tool missing - some control actions will be refused"
  fi
done
if command -v lsblk >/dev/null; then
  n=$(lsblk -J -b -o NAME,TYPE,FSTYPE 2>/dev/null | grep -c '"fstype"')
  note "$n block device(s) with a filesystem"
fi

echo
echo "recent agent log:"
journalctl -u piblue -n 8 --no-pager 2>/dev/null | sed 's/^/  /' || note "no journal access"
