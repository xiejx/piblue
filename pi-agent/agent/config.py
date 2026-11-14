"""Configuration loading.

Defaults live here; a JSON file (``/etc/piblue/config.json`` by default, or the
path in ``$PIBLUE_CONFIG``) is deep-merged on top so small tweaks never require
editing code.
"""

from __future__ import annotations

import copy
import json
import os
from typing import Any

DEFAULT_CONFIG: dict[str, Any] = {
    "ble": {
        # Name shown in the phone's scan list.
        "name": "PiBlue",
        # "" lets bless pick the first adapter, which is the on-board radio on
        # every Pi model. Set it to "hci1" only if a USB dongle coexists with
        # the on-board one and you want the dongle to serve BLE.
        "adapter": "",
        # Characteristics that are not subscribed to are silently dropped.
        "log_unknown_clients": False,
    },
    "agent": {
        "log_level": "INFO",
        # How often the phone may ask for a fresh snapshot when subscribing.
        "min_interval_s": 1.0,
        "max_interval_s": 60.0,
    },
    "shell": {
        "enabled": True,
        # False: only binaries listed in "allowed" may be run (safe default).
        # True : any binary, which is effectively root on the Pi - think twice.
        "allow_any": False,
        "allowed": [
            "ls", "cat", "head", "tail", "df", "free", "uptime", "uname", "date",
            "whoami", "id", "ps", "du", "wc", "grep", "find", "ip", "ss", "ping",
            "vcgencmd", "systemctl", "journalctl", "iwgetid", "sensors",
            "i2cdetect", "gpio", "python3", "sh", "hostname", "echo", "printf",
            "pwd", "stat", "mount", "lsblk", "who", "dmesg", "cut", "sort",
        ],
        # Always rejected, even when allow_any is true.  Remove entries if you
        # really want them, but they are here to stop an accidental fat-finger
        # from bricking the card.
        "deny": ["rm", "rmdir", "dd", "mkfs", "shred", "wipefs", "fdisk", "parted"],
        # When False any shell metacharacter (; | & > < $ `) is rejected, so a
        # command cannot smuggle in something that is not on the allow list.
        "allow_meta": False,
        "timeout_s": 10,
        "max_output_bytes": 8192,
    },
    "sensors": {
        # Extra sensors on top of the auto-detected ones.
        # Each entry: {"id": "custom:foo", "name": "Foo", "type": "value",
        #              "unit": "", "cmd": "/usr/local/bin/read_foo"}
        # The command must print a single number or a JSON object, e.g.
        # {"value": 42.0} or {"temp_c": 21.5, "humidity": 44}.
        "custom": [],
        # Optional DHT11/DHT22 wired to a GPIO pin (needs adafruit-circuitpython-dht).
        "dht": [],
        # Look for DS18B20 probes on the 1-Wire bus.
        "onewire": True,
    },
    "disk": {
        "path": "/",
    },
}

CONFIG_PATH = os.environ.get("PIBLUE_CONFIG", "/etc/piblue/config.json")


def _deep_merge(base: dict[str, Any], override: dict[str, Any]) -> dict[str, Any]:
    out = copy.deepcopy(base)
    for key, value in override.items():
        if isinstance(value, dict) and isinstance(out.get(key), dict):
            out[key] = _deep_merge(out[key], value)
        else:
            out[key] = value
    return out


def load_config(path: str | None = None) -> dict[str, Any]:
    path = path or CONFIG_PATH
    try:
        with open(path, "r", encoding="utf-8") as fh:
            user_cfg = json.load(fh)
    except FileNotFoundError:
        return copy.deepcopy(DEFAULT_CONFIG)
    except (OSError, json.JSONDecodeError) as exc:
        raise SystemExit(f"cannot read config {path}: {exc}") from exc

    if not isinstance(user_cfg, dict):
        raise SystemExit(f"config {path} must contain a JSON object")
    return _deep_merge(DEFAULT_CONFIG, user_cfg)
