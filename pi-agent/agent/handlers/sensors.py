"""Sensor registry.

Built-in sources:

* ``cpu``      - SoC temperature, always present.
* ``ds18b20``  - 1-Wire probes under /sys/bus/w1/devices (no extra packages).
* ``dht``      - DHT11/DHT22 on a GPIO pin (needs adafruit-circuitpython-dht).
* ``custom``   - any command that prints a number or a JSON object.

Adding a sensor means adding an entry to the config, not touching this file.
"""

from __future__ import annotations

import asyncio
import glob
import json
import os
import time
from typing import Any

from . import sysinfo

ONEWIRE_GLOB = "/sys/bus/w1/devices/2*-*/w1_slave"


def _ds18b20_paths() -> list[str]:
    paths = []
    for path in sorted(glob.glob(ONEWIRE_GLOB)):
        family = os.path.basename(os.path.dirname(path)).split("-")[0]
        if family in ("28", "10", "22"):
            paths.append(path)
    return paths


def _ds18b20_read(path: str) -> float | None:
    try:
        with open(path, "r", encoding="utf-8", errors="replace") as fh:
            lines = fh.read().splitlines()
    except OSError:
        return None
    if not lines or not lines[0].strip().endswith("YES"):
        return None
    for line in lines[1:]:
        if "t=" in line:
            try:
                return round(int(line.split("t=")[1]) / 1000.0, 2)
            except (ValueError, IndexError):
                return None
    return None


def _dht_pin(spec: str) -> Any:
    import board  # noqa: PLC0415 - optional dependency

    return getattr(board, spec.upper())


def _read_dht(spec: str) -> dict[str, Any] | None:
    try:
        import adafruit_dht  # noqa: PLC0415 - optional dependency
    except ImportError:
        return None
    sensor_type = getattr(adafruit_dht, "DHT22", adafruit_dht.DHT11)
    dev = sensor_type(_dht_pin(spec), use_pulseio=False)
    try:
        return {
            "temp_c": dev.temperature,
            "humidity": dev.humidity,
        }
    except RuntimeError:
        return None
    finally:
        dev.exit()


async def _read_custom(entry: dict[str, Any]) -> dict[str, Any] | None:
    cmd = entry.get("cmd")
    if not cmd:
        return None
    proc = await asyncio.create_subprocess_shell(
        cmd, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE
    )
    try:
        out, _ = await asyncio.wait_for(proc.communicate(), 8)
    except asyncio.TimeoutError:
        proc.kill()
        await proc.communicate()
        return None
    text = out.decode("utf-8", "replace").strip()
    if not text:
        return None
    try:
        parsed = json.loads(text)
        if isinstance(parsed, dict):
            return parsed
    except json.JSONDecodeError:
        pass
    try:
        return {"value": float(text.splitlines()[0])}
    except ValueError:
        return None


def list_sensors(config: dict[str, Any]) -> list[dict[str, Any]]:
    """Catalogue of everything readable, cheap enough to call on demand."""
    cfg = config.get("sensors", {})
    found: list[dict[str, Any]] = [
        {"id": "cpu", "name": "SoC 温度", "type": "temperature", "unit": "°C"}
    ]

    if cfg.get("onewire", True):
        for path in _ds18b20_paths():
            serial = os.path.basename(os.path.dirname(path))
            found.append(
                {
                    "id": f"ds18b20:{serial}",
                    "name": f"DS18B20 {serial}",
                    "type": "temperature",
                    "unit": "°C",
                }
            )

    for entry in cfg.get("dht", []):
        found.append(
            {
                "id": f"dht:{entry.get('pin')}",
                "name": f"{entry.get('model', 'DHT22')} @ {entry.get('pin')}",
                "type": "climate",
                "unit": "",
            }
        )

    for entry in cfg.get("custom", []):
        found.append(
            {
                "id": entry.get("id", "custom:unnamed"),
                "name": entry.get("name", "custom"),
                "type": entry.get("type", "value"),
                "unit": entry.get("unit", ""),
            }
        )

    return found


async def read_sensor(sensor_id: str, config: dict[str, Any]) -> dict[str, Any]:
    cfg = config.get("sensors", {})
    base = {"id": sensor_id, "ts": round(time.time(), 3)}

    if sensor_id == "cpu":
        value = sysinfo.cpu_temp_c()
        return {**base, "name": "SoC 温度", "type": "temperature",
                "unit": "°C", "value": value}

    kind, _, arg = sensor_id.partition(":")

    if kind == "ds18b20":
        path = f"/sys/bus/w1/devices/{arg}/w1_slave"
        value = _ds18b20_read(path)
        return {**base, "name": f"DS18B20 {arg}", "type": "temperature",
                "unit": "°C", "value": value}

    if kind == "dht":
        values = _read_dht(arg)
        return {**base, "name": f"DHT @ {arg}", "type": "climate",
                "unit": "", "values": values}

    if kind == "custom":
        for entry in cfg.get("custom", []):
            if entry.get("id") == sensor_id:
                values = await _read_custom(entry)
                return {**base, "name": entry.get("name"), "type": entry.get("type", "value"),
                        "unit": entry.get("unit", ""), "values": values}
        raise KeyError(f"unknown custom sensor {sensor_id}")

    raise KeyError(f"unknown sensor {sensor_id}")


async def read_all(config: dict[str, Any]) -> list[dict[str, Any]]:
    out = []
    for meta in list_sensors(config):
        try:
            out.append(await read_sensor(meta["id"], config))
        except Exception as exc:  # one broken probe must not kill the rest
            out.append({"id": meta["id"], "name": meta.get("name"), "error": str(exc)})
    return out
