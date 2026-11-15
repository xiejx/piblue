"""End the auth session when BlueZ says the phone hung up.

``hello`` starts a fresh session, so a *new* phone can never inherit an old
authorisation - but the reverse case is the dangerous one: the owner connects,
gets authorised, walks away, and the session is still open for anyone who
connects next. Only the disconnect itself can close that window in time.

bless does not expose per-connection events, so this module listens to the raw
system-bus signals instead:

* ``PropertiesChanged`` on ``org.bluez.Device1`` with ``Connected = false``
  - the normal disconnect path, the object stays known to BlueZ
* ``InterfacesRemoved`` carrying ``org.bluez.Device1``
  - the device was forgotten entirely

The same signal also tells us *which* phone connected: BlueZ puts the peer
address in the object path (``/org/bluez/hci0/dev_AA_BB_CC_DD_EE_FF``), so the
link up/down lines name it. Do not mistake that for an identity - handsets use
resolvable private addresses, so the address changes and cannot be used as an
allow-list.

There is no introspection and no method call other than ``AddMatch``: the
handler only looks at message headers and bodies. If anything here fails the
agent keeps running on the ``session_idle_s`` backstop, and says so in the log.
"""

from __future__ import annotations

import asyncio
import logging
import re
from typing import Any, Callable

logger = logging.getLogger("piblue.link")

#: Keeps the connection referenced for the lifetime of the process: a bus that
#: is garbage collected closes, and the handler would silently stop firing.
_bus: Any = None

_ADDRESS_IN_PATH = re.compile(r"/dev_([0-9A-Fa-f]{2}(?:_[0-9A-Fa-f]{2}){5})$")

#: Device1 properties worth putting in the log. Anything else (RSSI, UUIDs...)
#: changes too often to be useful.
_INTERESTING = ("Address", "AddressType", "Name", "Alias")


def _address_from_path(path: Any) -> str | None:
    """``/org/bluez/hci0/dev_A4_C1_38_12_34_56`` -> ``A4:C1:38:12:34:56``."""
    match = _ADDRESS_IN_PATH.search(str(path or ""))
    if not match:
        return None
    return ":".join(match.group(1).split("_")).upper()


def _plain(value: Any) -> Any:
    """Unwrap dbus-next's ``Variant`` (and tolerate plain values)."""
    return getattr(value, "value", value)


def _picked(changed: dict[str, Any]) -> dict[str, Any]:
    return {k: _plain(changed[k]) for k in _INTERESTING if k in changed}


def _describe(address: str | None, props: dict[str, Any]) -> str:
    parts = [address or "<unknown device>"]
    if props.get("AddressType"):
        parts.append(f"({props['AddressType']})")
    name = props.get("Name") or props.get("Alias")
    if name:
        parts.append(f"name={name!r}")
    return " ".join(parts)


def _link_event(message: Any) -> tuple[str, str | None, dict[str, Any]] | None:
    """Return ``("up"|"down", address, props)``, or None for other signals."""
    # Compare by enum name so this module does not need dbus at import time.
    if str(getattr(getattr(message, "message_type", None), "name", "")) != "SIGNAL":
        return None

    member = str(getattr(message, "member", "") or "")
    body = getattr(message, "body", None) or []
    path_address = _address_from_path(getattr(message, "path", ""))

    if member == "PropertiesChanged" and len(body) >= 2:
        if body[0] != "org.bluez.Device1":
            return None
        changed = body[1] or {}
        if "Connected" not in changed:
            # Attribute-only updates (RSSI moves constantly, Name arrives
            # separately) are not link events - logging them floods the journal.
            return None
        props = _picked(changed)
        address = str(props.get("Address") or path_address or "") or None
        return ("up" if _plain(changed["Connected"]) else "down", address, props)

    if member == "InterfacesRemoved" and len(body) >= 2:
        if "org.bluez.Device1" in (body[1] or []):
            return ("down", path_address, {})

    return None


async def watch(on_disconnect: Callable[[], None]) -> None:
    """Block forever, calling ``on_disconnect`` whenever a central goes away."""
    global _bus

    try:
        from dbus_next import BusType, Message  # noqa: PLC0415
        from dbus_next.aio import MessageBus  # noqa: PLC0415
    except ImportError:
        try:
            from dbus_fast import BusType, Message  # noqa: PLC0415
            from dbus_fast.aio import MessageBus  # noqa: PLC0415
        except ImportError:
            logger.warning(
                "no dbus-next/dbus-fast available - auth sessions can only end on "
                "hello or the idle timeout"
            )
            return

    try:
        bus = await MessageBus(bus_type=BusType.SYSTEM).connect()
    except Exception as exc:  # noqa: BLE001 - never take the agent down over this
        logger.warning("system bus unavailable (%s) - disconnect events will be missed", exc)
        return

    def handler(message: Any) -> bool:
        try:
            event = _link_event(message)
            if event is None:
                return False
            kind, address, props = event
            if kind == "up":
                logger.info("link up: %s", _describe(address, props))
            else:
                logger.info("link down: %s", _describe(address, props))
                on_disconnect()
        except Exception as exc:  # noqa: BLE001
            logger.debug("link event handler error: %s", exc)
        return False  # never claim the message, bless may need it

    bus.add_message_handler(handler)

    try:
        reply = await bus.call(
            Message(
                destination="org.freedesktop.DBus",
                path="/org/freedesktop/DBus",
                interface="org.freedesktop.DBus",
                member="AddMatch",
                signature="s",
                body=["type='signal',sender='org.bluez'"],
            )
        )
        if getattr(reply, "message_type", None) is not None and reply.message_type.name == "ERROR":
            logger.warning("AddMatch refused (%s) - relying on the idle timeout", reply.body)
            return
    except Exception as exc:  # noqa: BLE001
        logger.warning("could not subscribe to bluez signals (%s) - idle timeout only", exc)
        return

    _bus = bus
    logger.info("watching org.bluez for disconnects - sessions end with the link")
    await asyncio.Event().wait()
