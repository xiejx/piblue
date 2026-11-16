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
    "auth": {
        # The GATT service is public, so the command layer is gated instead:
        # nothing but hello/auth/ping is answered until the phone proves it
        # knows the pairing code (/etc/piblue/token, mode 0600).
        "enabled": True,
        # Inline code, for tests and one-off runs. Leave empty in production so
        # the secret stays in the token file and not in a file people paste.
        "token": "",
        "token_file": "/etc/piblue/token",
        # Wrong codes before a cool-down. Each guess costs a full handshake.
        "max_attempts": 5,
        "lockout_s": 60,
        # Sessions end when BlueZ reports the disconnect; this is the backstop
        # for stacks where that signal never arrives. Traffic resets the clock.
        "session_idle_s": 300,
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
    "control": {
        # The control page in the app is generated from this section: an action
        # that is not listed here has no button at all. Deleting a line is the
        # way to take the button away - there is no hidden second list.
        "enabled": True,
        "allow": [
            "reboot",
            "shutdown",
            "restart-agent",
            "restart-bluetooth",
            "wifi-enable",
            "wifi-disable",
            "wifi-scan",
            "wifi-connect",
            "wifi-disconnect",
            "disk-list",
            "disk-mount",
            "disk-unmount",
            "custom",
            "custom-save",
        ],
        # Mounted devices land here, one directory per mount, named by the user
        # on the phone (/mnt/<name>). A mount point outside this directory is
        # only unmountable when the device is removable, which is what keeps /
        # and /boot out of reach.
        "mount_root": "/mnt",
        # NTFS stores no POSIX owners, so the kernel driver shows every file as
        # the uid/gid of whoever mounted the volume - root, because the agent
        # mounts through sudo. Naming the login user here mounts with
        # uid=/gid=<user>, which is what makes the stick writable from pi.
        # Set "" to keep mounted volumes root-owned.
        "mount_owner": "pi",
        # Up to five one-line commands. The app edits them and the Pi keeps them
        # in custom.json next to this file; `custom` below is only the seed used
        # until the first save. The shell deny list still applies.
        "custom": [],
        "custom_timeout_s": 20,
        # Let the phone add/remove those commands. False makes control.custom
        # read-only (hand-edited in this file only).
        "allow_custom_edit": True,
        # Pipes and redirects are allowed inside a custom command, the same way
        # they would be in a command written here by hand. The deny list (rm,
        # dd, mkfs...) still refuses the destructive ones.
        "custom_allow_meta": True,
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
