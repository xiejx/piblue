"""Entry point: BLE in, JSON commands out.

Run as root (the BlueZ advertising API needs it), which is what the systemd
unit does.
"""

from __future__ import annotations

import asyncio
import logging
import signal
import sys
from typing import Any

from .agent import VERSION, Agent
from .ble_server import BleTransport
from .config import load_config
from .link_watch import watch
from .protocol import encode

logger = logging.getLogger("piblue")


async def serve(config: dict[str, Any]) -> None:
    inbound: asyncio.Queue[bytes] = asyncio.Queue()

    transport = BleTransport(
        name=config["ble"]["name"],
        adapter=str(config["ble"].get("adapter") or ""),
        on_data=inbound.put_nowait,
    )

    async def send(frame: dict[str, Any]) -> None:
        await transport.send(encode(frame))

    agent = Agent(config, send, set_mtu=transport.set_peer_mtu)

    await transport.start()

    if agent.auth.enabled:
        logger.info(
            "auth: pairing code required, session ends with the link "
            "(idle backstop %.0f s)", agent.auth.session_idle_s,
        )
    logger.info("PiBlue %s ready - waiting for the phone", VERSION)

    # Runs for the lifetime of the process; it degrades to a warning and the
    # idle timeout if the system bus is not reachable.
    link_task = asyncio.create_task(
        watch(lambda: agent.reset_auth("phone disconnected"))
    )

    try:
        while True:
            chunk = await inbound.get()
            await agent.feed(chunk)
    finally:
        link_task.cancel()
        await agent.close()
        await transport.stop()


async def main_async(config: dict[str, Any]) -> None:
    loop = asyncio.get_running_loop()
    task = asyncio.current_task()

    def _stop(*_: Any) -> None:
        if task is not None:
            task.cancel()

    for sig in (signal.SIGINT, signal.SIGTERM):
        try:
            loop.add_signal_handler(sig, _stop)
        except (NotImplementedError, RuntimeError):
            signal.signal(sig, _stop)

    try:
        await serve(config)
    except asyncio.CancelledError:
        logger.info("shutting down")


def main() -> int:
    config = load_config()
    log_level = str(config.get("agent", {}).get("log_level", "INFO")).upper()

    logging.basicConfig(
        level=getattr(logging, log_level, logging.INFO),
        format="%(asctime)s %(levelname)-7s %(name)s: %(message)s",
        stream=sys.stdout,
    )

    try:
        asyncio.run(main_async(config))
    except KeyboardInterrupt:
        pass
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
