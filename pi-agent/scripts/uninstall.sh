#!/usr/bin/env bash
# Remove the systemd side of PiBlue, for when you want to go back to running
# from a checkout with scripts/dev.sh.
#
#   sudo ./scripts/uninstall.sh           # stop + disable + delete the unit
#   sudo ./scripts/uninstall.sh --purge   # also delete /opt/piblue and /etc/piblue
#
# The default is deliberately non-destructive: your config and code copy under
# /etc/piblue and /opt/piblue survive, so you can reinstall without losing them.
set -uo pipefail

PURGE=0
for arg in "$@"; do
  case "$arg" in
    --purge) PURGE=1 ;;
    -h|--help) sed -n '2,11p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown option: $arg" >&2; exit 2 ;;
  esac
done

log() { printf '\033[1;36m[rm]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[!]\033[0m %s\n' "$*"; }

[[ $EUID -eq 0 ]] || { echo "please run with sudo: sudo $0" >&2; exit 1; }

if [[ "$PURGE" == "1" ]]; then
  echo "This will permanently delete:"
  echo "  /opt/piblue   (code + venv)"
  echo "  /etc/piblue   (config.json)"
  echo
  read -r -p "type 'yes' to continue: " answer
  [[ "$answer" == "yes" ]] || { echo "aborted, nothing was deleted."; exit 1; }
fi

if systemctl list-unit-files 2>/dev/null | grep -q '^piblue\.service'; then
  log "stopping piblue.service"
  systemctl stop piblue 2>/dev/null || true
  systemctl disable piblue 2>/dev/null || true
fi

if [[ -f /etc/systemd/system/piblue.service ]]; then
  log "removing the unit file"
  rm -f /etc/systemd/system/piblue.service
  systemctl daemon-reload
  systemctl reset-failed piblue 2>/dev/null || true
fi

if [[ "$PURGE" == "1" ]]; then
  log "deleting /opt/piblue"
  rm -rf /opt/piblue
  log "deleting /etc/piblue"
  rm -rf /etc/piblue
  echo
  log "done - nothing left behind"
else
  echo
  log "service removed"
  warn "/opt/piblue and /etc/piblue were kept (that holds your config)"
  warn "delete them with:  sudo ./scripts/uninstall.sh --purge"
fi
