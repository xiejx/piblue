"""BLE transport built on BlueZ via ``bless``.

Two characteristics, Nordic-UART style:

===================  ==========  =========================================
UUID suffix          direction   properties
===================  ==========  =========================================
``...0001`` service  -           primary service, advertised by name
``...0002`` RX       phone->Pi   write
``...0003`` TX       Pi->phone   notify + read
===================  ==========  =========================================

Why BLE instead of classic Bluetooth SPP?  SPP needs BlueZ's deprecated
compatibility mode (``bluetoothd -C``) plus an ``rfcomm`` bind, which has been
getting more fragile with every Raspberry Pi OS release.  A GATT server is the
supported, future-proof path and needs no pairing ritual on the phone.
"""

from __future__ import annotations

import asyncio
import inspect
import logging
from typing import Any, Callable

logger = logging.getLogger("piblue.ble")

SERVICE_UUID = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
RX_UUID = "6e400002-b5a3-f393-e0a9-e50e24dcca9e"
TX_UUID = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"


class BleTransport:
    #: ATT caps a notify PDU at the negotiated MTU: 1 byte opcode + 2 bytes
    #: handle + payload. A longer Value is not truncated by bluetoothd, it is
    #: dropped at the ATT layer while the D-Bus call still reports success -
    #: which is why an oversized reply looks exactly like a dead Pi. 23 is the
    #: only MTU we can assume before the peer tells us what it negotiated.
    MIN_CHUNK = 20
    MAX_CHUNK = 512
    #: Gap between the notifications of one frame. bluetoothd queues a few
    #: ACL packets, but they are unacknowledged, so a tight loop can overflow
    #: the controller buffer and lose a chunk in the middle of a frame.
    CHUNK_PACE_S = 0.015

    def __init__(self, name: str, adapter: str, on_data: Callable[[bytes], None]) -> None:
        self.name = name
        self.adapter = adapter
        self._on_data = on_data
        self._server: Any = None
        self._tx: Any = None
        self._send_lock = asyncio.Lock()
        self._chunk = self.MIN_CHUNK

    async def start(self) -> None:
        try:
            from bless import (  # noqa: PLC0415 - imported late for a clear error
                BlessServer,
                GATTAttributePermissions,
                GATTCharacteristicProperties,
            )
        except ImportError as exc:  # pragma: no cover - install problem
            raise SystemExit(
                "bless is not installed (or is too old). Run:\n"
                "  sudo /opt/piblue/venv/bin/pip install -U 'bless>=0.3.0'\n"
                "bless >=0.3.0 speaks D-Bus through dbus-next (pure python) and\n"
                "is the only branch that works on python 3.11+ / BlueZ 5.6x.\n"
            ) from exc

        kwargs: dict[str, Any] = {"name": self.name}
        # Do not rely on inspecting the signature: BlessServer declares
        # ``**kwargs`` and forwards it to the backend, so a key-by-key check
        # silently drops the setting. An empty adapter means "let the backend
        # pick the first one", which is right on a stock Pi - a USB dongle
        # would be hci1 and guessing wrong makes bless fail with
        # "Could not locate bluetooth adapter".
        if self.adapter:
            kwargs["adapter"] = self.adapter

        self._server = BlessServer(**kwargs)
        self._server.read_request_func = self._on_read
        self._server.write_request_func = self._on_write

        logger.info("registering GATT service %s", SERVICE_UUID)
        await self._server.add_new_service(SERVICE_UUID)

        rx_props = (
            GATTCharacteristicProperties.write
            | GATTCharacteristicProperties.write_without_response
        )
        await self._server.add_new_characteristic(
            SERVICE_UUID, RX_UUID, rx_props, bytearray(b""),
            GATTAttributePermissions.writeable,
        )

        tx_props = (
            GATTCharacteristicProperties.notify | GATTCharacteristicProperties.read
        )
        await self._server.add_new_characteristic(
            SERVICE_UUID, TX_UUID, tx_props, bytearray(b'{"event":"ready"}\n'),
            GATTAttributePermissions.readable,
        )

        self._tx = self._server.get_characteristic(TX_UUID)
        started = await self._server.start()
        if started is False:
            raise RuntimeError(
                "bless refused to start - is another GATT server already "
                "registered with bluetoothd? (sudo systemctl restart bluetooth)"
            )
        logger.info("advertising as '%s' on %s", self.name, self.adapter or "first adapter")

    def set_peer_mtu(self, mtu: Any) -> None:
        """Learn the negotiated ATT MTU from the phone.

        BlueZ does not expose the per-connection MTU over D-Bus and bless does
        not either, so the only party that knows it is the client. It reports
        the value in ``hello`` and we size the notifications accordingly.
        """
        try:
            value = int(mtu)
        except (TypeError, ValueError):
            logger.warning("ignoring bogus mtu %r", mtu)
            return
        if not 23 <= value <= 517:
            logger.warning("ignoring out-of-range mtu %d", value)
            return
        self._chunk = max(self.MIN_CHUNK, min(value - 3, self.MAX_CHUNK))
        logger.info("peer mtu %d -> %d-byte notify chunks", value, self._chunk)

    async def send(self, data: bytes) -> None:
        if self._tx is None or self._server is None:
            logger.error("send() before the GATT server is ready - %d bytes dropped", len(data))
            return
        async with self._send_lock:
            for offset in range(0, len(data), self._chunk):
                piece = data[offset: offset + self._chunk]
                self._tx.value = bytearray(piece)
                try:
                    result = self._server.update_value(SERVICE_UUID, TX_UUID)
                    if inspect.isawaitable(result):
                        result = await result
                except Exception as exc:  # noqa: BLE001 - no subscriber is not an error
                    # Was DEBUG: a broken notify path then looked exactly like a
                    # Pi that never answered, with nothing in the journal.
                    logger.warning("notify failed at byte %d/%d: %s", offset, len(data), exc)
                    await self._abandon_frame()
                    return
                if result is False:
                    logger.warning("update_value() could not find TX %s", TX_UUID)
                    return
                if offset + self._chunk < len(data):
                    await asyncio.sleep(self.CHUNK_PACE_S)
            logger.debug("tx %d bytes in %d chunk(s)", len(data), -(-len(data) // self._chunk))

    async def _abandon_frame(self) -> None:
        """Terminate a half-sent frame so the phone's line buffer resynchronises.

        The client reassembles on newlines; a frame abandoned mid-way would
        otherwise poison every following frame until its 64 KiB guard trips.
        A bare newline makes the parser throw the garbage away.
        """
        if self._tx is None or self._server is None:
            return
        try:
            self._tx.value = bytearray(b"\n")
            result = self._server.update_value(SERVICE_UUID, TX_UUID)
            if inspect.isawaitable(result):
                await result
        except Exception as exc:  # noqa: BLE001
            logger.debug("could not resynchronise the frame: %s", exc)

    async def stop(self) -> None:
        if self._server is None:
            return
        try:
            result = self._server.stop()
            if inspect.isawaitable(result):
                await result
        except Exception as exc:  # noqa: BLE001
            logger.debug("stop failed: %s", exc)

    # ------------------------------------------------------------- callbacks

    def _on_read(self, characteristic: Any, **_: Any) -> bytearray:
        value = getattr(characteristic, "value", None) or b"{}"
        return bytearray(value)

    def _on_write(self, characteristic: Any, value: Any, **_: Any) -> None:
        uuid = str(getattr(characteristic, "uuid", "")).lower()
        if uuid != RX_UUID:
            # Never swallow silently: if bless hands us an unexpected uuid the
            # link looks alive but every command is dropped on the floor.
            logger.warning("write ignored, uuid is %s not RX %s", uuid or "<none>", RX_UUID)
            return
        if not value:
            logger.warning("empty write on RX")
            return
        logger.info("rx %d bytes", len(value))
        self._on_data(bytes(value))
