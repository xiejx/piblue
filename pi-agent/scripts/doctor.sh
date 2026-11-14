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

echo
echo "recent agent log:"
journalctl -u piblue -n 8 --no-pager 2>/dev/null | sed 's/^/  /' || note "no journal access"
