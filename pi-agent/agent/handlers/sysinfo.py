"""System snapshot for the Pi.

Deliberately stdlib-only (no psutil) so it works on a fresh Raspberry Pi OS
image with nothing installed.
"""

from __future__ import annotations

import os
import platform
import socket
import struct
import subprocess
import time
from typing import Any

try:  # not available on Windows; only used for the interface address lookup
    import fcntl
except ImportError:  # pragma: no cover
    fcntl = None  # type: ignore[assignment]

_socket = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
_IFREQ_STRUCT = struct.Struct("256s")


def _read(path: str) -> str | None:
    try:
        with open(path, "r", encoding="utf-8", errors="replace") as fh:
            return fh.read()
    except OSError:
        return None


def _run(argv: list[str], timeout: float = 2.0) -> str | None:
    try:
        out = subprocess.run(
            argv, capture_output=True, timeout=timeout, check=False, text=True
        )
    except (OSError, subprocess.SubprocessError):
        return None
    if out.returncode != 0:
        return None
    return out.stdout.strip()


def cpu_temp_c() -> float | None:
    raw = _read("/sys/class/thermal/thermal_zone0/temp")
    if raw:
        try:
            return round(int(raw.strip()) / 1000.0, 1)
        except ValueError:
            pass
    out = _run(["vcgencmd", "measure_temp"])
    if out and "=" in out:
        try:
            return round(float(out.split("=")[1].split("'")[0]), 1)
        except ValueError:
            pass
    return None


def memory() -> dict[str, Any] | None:
    raw = _read("/proc/meminfo")
    if not raw:
        return None
    fields: dict[str, int] = {}
    for line in raw.splitlines():
        key, _, rest = line.partition(":")
        parts = rest.split()
        if parts and parts[0].isdigit():
            fields[key] = int(parts[0])
    total = fields.get("MemTotal", 0)
    available = fields.get("MemAvailable", fields.get("MemFree", 0))
    if not total:
        return None
    used = total - available
    return {
        "total_mb": round(total / 1024, 1),
        "used_mb": round(used / 1024, 1),
        "available_mb": round(available / 1024, 1),
        "used_pct": round(used * 100.0 / total, 1),
    }


def disk(path: str = "/") -> dict[str, Any] | None:
    if not hasattr(os, "statvfs"):  # Windows, for the simulator
        return None
    try:
        st = os.statvfs(path)
    except OSError:
        return None
    total = st.f_blocks * st.f_frsize
    free = st.f_bavail * st.f_frsize
    if not total:
        return None
    used = total - free
    return {
        "path": path,
        "total_gb": round(total / 1e9, 1),
        "used_gb": round(used / 1e9, 1),
        "free_gb": round(free / 1e9, 1),
        "used_pct": round(used * 100.0 / total, 1),
    }


def load() -> list[float]:
    raw = _read("/proc/loadavg")
    if not raw:
        return [0.0, 0.0, 0.0]
    parts = raw.split()[:3]
    try:
        return [round(float(p), 2) for p in parts]
    except ValueError:
        return [0.0, 0.0, 0.0]


def uptime_s() -> int:
    raw = _read("/proc/uptime")
    if raw:
        try:
            return int(float(raw.split()[0]))
        except (ValueError, IndexError):
            pass
    return int(time.monotonic())


def _iface_ipv4(name: str) -> str | None:
    """Address of one interface via SIOCGIFADDR - works with no network."""
    if fcntl is None:
        return None
    try:
        ifreq = _IFREQ_STRUCT.pack(name.encode()[:15])
        res = fcntl.ioctl(_socket.fileno(), 0x8915, ifreq)  # SIOCGIFADDR
        return socket.inet_ntoa(res[20:24])
    except OSError:
        return None


def primary_ipv4() -> dict[str, str | None]:
    """Pick the interface that owns the default route, then its address."""
    routes = _read("/proc/net/route") or ""
    best: str | None = None
    for line in routes.splitlines()[1:]:
        cols = line.split()
        if len(cols) < 4:
            continue
        iface, dest, _gw, flags = cols[0], cols[1], cols[2], cols[3]
        if dest == "00000000" and int(flags, 16) & 0x2:
            best = iface
            break

    candidates = [c for c in (best, "wlan0", "eth0") if c]
    for name in candidates:
        ip = _iface_ipv4(name)
        if ip:
            return {"iface": name, "ipv4": ip}

    # Fallback: the UDP-connect trick (works when a route exists at all).
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.settimeout(0.2)
            s.connect(("8.8.8.8", 80))
            return {"iface": best, "ipv4": s.getsockname()[0]}
    except OSError:
        return {"iface": best, "ipv4": None}


def _dotted_int(text: str) -> int | None:
    """Parse a /proc/net/wireless number, which is printed as ``61.``."""
    try:
        return int(text.rstrip("."))
    except ValueError:
        return None


def _wifi_link() -> tuple[str, int | None] | None:
    """Return ``(iface, link_quality)`` from /proc/net/wireless, or None.

    ``link`` is the 0..70 quality scale the status page shows next to the SSID,
    and it is the only scale this file has: the same line's ``level`` column is
    dBm, but the two are not convertible, so the app prints the quality and the
    control page (nmcli) prints dBm.

    Do not reach for ``iwgetid --signal`` here. wireless-tools never shipped
    that flag - ``iwgetid --help`` lists only -a/-c/-f/-m/-p/-r/-s - so the
    command exits 1 with "unrecognized option" and the value silently turned
    into a very convincing 0 on every device.
    """
    raw = _read("/proc/net/wireless")
    if not raw:
        return None
    # Two header lines, then one line per interface:
    #   wlan0: 0000   61.  -49.  -256        0      0      0    624      0
    #                 ^link ^level ^noise (and -256 means "no reading")
    for line in raw.splitlines()[2:]:
        name, _, rest = line.partition(":")
        fields = rest.split()
        if len(fields) < 2:  # status + link are the minimum
            continue
        quality = _dotted_int(fields[1])
        # 0 is what a driver prints when it has no quality reading at all, so
        # it means "unknown" rather than "the worst possible link". Keeping it
        # would put a plausible-looking 0 back in front of the user.
        if quality is not None and quality <= 0:
            quality = None
        return name.strip(), quality
    return None


def wifi() -> dict[str, Any] | None:
    ssid = _run(["iwgetid", "-r"])
    if not ssid:
        return None
    link = _wifi_link()
    # None when no interface line is there at all (rfkill blocked, driver
    # unloading) - the app renders that as a bare SSID rather than "(0/70)".
    signal = link[1] if link else None
    return {"ssid": ssid, "signal": signal}


_THROTTLE_BITS = {
    0: "under-voltage now",
    1: "arm frequency capped now",
    2: "currently throttled",
    3: "soft temperature limit now",
    16: "under-voltage occurred",
    17: "arm frequency capped occurred",
    18: "throttled occurred",
    19: "soft temperature limit occurred",
}


def throttled() -> dict[str, Any] | None:
    out = _run(["vcgencmd", "get_throttled"])
    if not out or "=" not in out:
        return None
    try:
        raw = int(out.split("=")[1], 16)
    except ValueError:
        return None
    flags = [label for bit, label in _THROTTLE_BITS.items() if raw & (1 << bit)]
    return {"raw": f"0x{raw:x}", "flags": flags, "ok": raw == 0}


def model() -> str | None:
    for path in ("/proc/device-tree/model", "/sys/firmware/devicetree/base/model"):
        raw = _read(path)
        if raw:
            return raw.replace("\x00", "").strip()
    return None


def clock() -> dict[str, Any]:
    """The Pi's wall clock, in the Pi's own timezone.

    Both a formatted string and an epoch go over the wire on purpose: the phone
    may sit in another timezone, and reformatting the epoch with the phone's
    zone would hide exactly the offset the user is looking for ("is the Pi an
    hour behind?", "did NTP ever reach it?").
    """
    now = time.time()
    local = time.localtime(now)
    return {
        "epoch": round(now, 3),
        "local": time.strftime("%Y-%m-%d %H:%M:%S", local),
        "tz": time.strftime("%Z", local) or "",
        "utc_offset": time.strftime("%z", local) or "",
        "zone": (_read("/etc/timezone") or "").strip() or None,
        "synced": _clock_synced(),
    }


def _clock_synced() -> bool | None:
    """``timedatectl`` says whether NTP has actually set the clock.

    A Pi without a network and without an RTC boots to a 1970-ish date, and the
    status page is the one place that would show it. ``None`` when the tool is
    missing (dev machines, non-systemd images).
    """
    out = _run(["timedatectl", "show", "-p", "NTPSynchronized", "--value"])
    if out is None:
        return None
    return out.strip().lower() == "yes"


def snapshot(config: dict[str, Any]) -> dict[str, Any]:
    """Everything the status screen needs, in one round trip."""
    net = primary_ipv4()
    return {
        "hostname": socket.gethostname(),
        "model": model(),
        "kernel": platform.release(),
        "os": platform.platform(),
        "uptime_s": uptime_s(),
        "cpu_temp_c": cpu_temp_c(),
        "cpu_count": os.cpu_count(),
        "load": load(),
        "mem": memory(),
        "disk": disk(config.get("disk", {}).get("path", "/")),
        "net": net,
        "wifi": wifi(),
        "throttled": throttled(),
        "time": clock(),
        "ts": round(time.time(), 3),
    }


def summary(config: dict[str, Any]) -> dict[str, Any]:
    """A tiny payload for the 1-2 s live subscription."""
    snap = snapshot(config)
    return {
        "cpu_temp_c": snap["cpu_temp_c"],
        "load1": (snap["load"] or [0.0])[0],
        "mem_used_pct": (snap["mem"] or {}).get("used_pct"),
        "uptime_s": snap["uptime_s"],
        "time": snap["time"],
        "ts": snap["ts"],
    }
