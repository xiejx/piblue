#!/usr/bin/env bash
# Development runner - runs the agent straight out of this folder.
#
#   sudo ./scripts/dev.sh            # prepare (first run only) + run in the foreground
#   sudo ./scripts/dev.sh --setup    # only prepare ./venv and ./config.dev.json
#   sudo ./scripts/dev.sh --rebuild  # throw the venv away and recreate it
#
# Nothing is written outside this directory: no /opt/piblue, no /etc/piblue,
# no systemd unit. Ctrl-C stops the agent.
#
# Root is still required - BlueZ only lets privileged clients register a GATT
# application, so there is no way around it at debug time either.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VENV="$HERE/venv"
CFG="$HERE/config.dev.json"
MODE="run"

for arg in "$@"; do
  case "$arg" in
    --setup)   MODE="setup" ;;
    --rebuild) MODE="rebuild" ;;
    -h|--help) sed -n '2,10p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown option: $arg (try --help)" >&2; exit 2 ;;
  esac
done

log() { printf '\033[1;36m[dev]\033[0m %s\n' "$*"; }
die() { printf '\033[1;31m[dev]\033[0m %s\n' "$*" >&2; exit 1; }

[[ $EUID -eq 0 ]] || die "BLE advertising needs root: sudo $0 $*"

# A running systemd copy already owns the GATT registration; ours would then
# fail with "Could not locate bluetooth adapter" or a RegisterApplication error.
if systemctl is-active --quiet piblue 2>/dev/null; then
  die "piblue.service is running and owns the advertisement.
      stop it first:  sudo systemctl stop piblue
      remove it for good:  sudo ./scripts/uninstall.sh"
fi

prepare() {
  if ! command -v python3 >/dev/null 2>&1; then
    die "python3 not found"
  fi

  if ! python3 -c 'import venv' 2>/dev/null; then
    log "apt: python3-venv"
    apt-get update -qq
    DEBIAN_FRONTEND=noninteractive apt-get install -y -qq python3-venv >/dev/null
  fi

  if ! command -v bluetoothctl >/dev/null 2>&1; then
    log "apt: bluez"
    apt-get update -qq
    DEBIAN_FRONTEND=noninteractive apt-get install -y -qq bluez >/dev/null
  fi

  if [[ ! -x "$VENV/bin/python" ]]; then
    log "creating $VENV"
    python3 -m venv "$VENV"
  fi

  log "pip: -r requirements.txt (needs the internet, use PIP_INDEX_URL for a mirror)"
  "$VENV/bin/pip" install --quiet --upgrade pip
  "$VENV/bin/pip" install --quiet -r "$HERE/requirements.txt"
  "$VENV/bin/python" - <<'PY'
from importlib.metadata import version
print("    bless", version("bless"))
PY

  if [[ ! -f "$CFG" ]]; then
    cp "$HERE/config.example.json" "$CFG"
    log "wrote $CFG"
  fi
}

run_agent() {
  # Best effort: bring the radio up. Harmless if it is already on.
  systemctl start bluetooth 2>/dev/null || true
  rfkill unblock bluetooth 2>/dev/null || true
  bluetoothctl power on >/dev/null 2>&1 || true

  if command -v bluetoothctl >/dev/null 2>&1; then
    if ! bluetoothctl list 2>/dev/null | grep -q "Controller"; then
      die "no bluetooth controller found.
      Pi 1/2 and the first Zero have no radio - plug in a USB dongle.
      If you do have one: sudo rfkill unblock bluetooth && sudo systemctl start bluetooth"
    fi
  fi

  log "code    $HERE"
  log "config  $CFG   (PIBLUE_CONFIG)"
  log "stop    Ctrl-C"
  echo

  cd "$HERE"
  # exec so the python process gets Ctrl-C directly, no wrapper left behind.
  PIBLUE_CONFIG="$CFG" exec "$VENV/bin/python" -m agent.main
}

case "$MODE" in
  setup)
    prepare
    log "done - now run: sudo ./scripts/dev.sh"
    ;;
  rebuild)
    log "removing $VENV"
    rm -rf "$VENV"
    prepare
    ;;
  run)
    if [[ ! -x "$VENV/bin/python" ]]; then
      log "first run - preparing the venv"
      prepare
    fi
    run_agent
    ;;
esac
