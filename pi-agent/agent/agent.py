"""Command dispatcher.

One instance per link.  ``feed()`` accepts raw bytes from whichever transport is
driving it (BLE notifications or stdin) and answers through ``send()``, so the
protocol logic is identical on the Pi and in the simulator.
"""

from __future__ import annotations

import asyncio
import logging
import time
from typing import Any, Awaitable, Callable

from .auth import AuthGate, AuthRejected
from .handlers import sensors, shell, sysinfo
from .protocol import LineDecoder

logger = logging.getLogger("piblue.agent")

VERSION = "1.1.0"

#: Answered before the phone has proved it knows the pairing code. ``ping`` is
#: in the list so a client can check that the link is alive while it is still
#: hunting for the code; it reveals nothing about the machine.
UNAUTHENTICATED_COMMANDS = frozenset({"hello", "auth", "ping"})

SendFn = Callable[[dict[str, Any]], Awaitable[None]]


class Agent:
    def __init__(
        self,
        config: dict[str, Any],
        send: SendFn,
        set_mtu: Callable[[Any], None] | None = None,
    ) -> None:
        self.config = config
        self._send = send
        #: notification chunking depends on the MTU the phone negotiated; the
        #: transport owns that state, the agent only parses the value.
        self._set_mtu = set_mtu
        self.auth = AuthGate(config)
        self.decoder = LineDecoder()
        self._subs: dict[str, asyncio.Task[None]] = {}
        self._handlers = {
            "hello": self._cmd_hello,
            "auth": self._cmd_auth,
            "ping": self._cmd_ping,
            "info": self._cmd_info,
            "summary": self._cmd_summary,
            "sensors": self._cmd_sensors,
            "sensor": self._cmd_sensor,
            "exec": self._cmd_exec,
            "sub": self._cmd_sub,
            "unsub": self._cmd_unsub,
            "help": self._cmd_help,
        }

    # ---------------------------------------------------------------- plumbing

    async def feed(self, chunk: bytes) -> None:
        logger.debug("chunk %r", chunk)
        for msg in self.decoder.feed(chunk):
            await self._handle(msg)

    async def _reply(
        self,
        mid: Any,
        ok: bool,
        data: Any = None,
        error: str | None = None,
        code: str | None = None,
    ) -> None:
        frame: dict[str, Any] = {"id": mid, "ok": ok}
        if ok:
            frame["data"] = data if data is not None else {}
        else:
            frame["error"] = error or "unknown error"
            if code:
                # Machine-readable, so the phone can tell "not authorised"
                # (retry after a handshake) from "that command failed".
                frame["code"] = code
        await self._send(frame)

    async def _handle(self, msg: dict[str, Any]) -> None:
        if "__parse_error__" in msg:
            await self._send({"event": "error", "error": msg["__parse_error__"]})
            return

        mid = msg.get("id")
        cmd = str(msg.get("cmd") or "").strip()

        # The gate comes before the dispatch table: an unauthenticated peer must
        # not be able to tell a real command from a made-up one.
        if cmd not in UNAUTHENTICATED_COMMANDS and not self.auth.authorized:
            logger.warning("refused %r: no valid session", cmd or "<empty>")
            await self._reply(
                mid, False, code="unauthorized",
                error="unauthorized: send {'cmd':'hello'} then {'cmd':'auth','mac':...}",
            )
            return

        handler = self._handlers.get(cmd)

        if handler is None:
            await self._reply(
                mid, False,
                error=f"unknown command '{cmd}'; send {{'cmd':'help'}} for the list",
            )
            return

        started = time.monotonic()
        logger.info("<- id=%s cmd=%s", mid, cmd or "<empty>")
        try:
            data = await handler(msg)
        except (shell.ShellRejected,) as exc:
            await self._reply(mid, False, error=exc.message)
        except AuthRejected as exc:
            await self._reply(mid, False, error=exc.message, code="unauthorized")
        except KeyError as exc:
            await self._reply(mid, False, error=str(exc.args[0] if exc.args else exc))
        except Exception as exc:  # noqa: BLE001 - never let a handler kill the link
            logger.exception("handler %s failed", cmd)
            await self._reply(mid, False, error=f"{type(exc).__name__}: {exc}")
        else:
            self.auth.touch()
            await self._reply(mid, True, data=data)
            logger.debug("%s ok in %.0f ms", cmd, (time.monotonic() - started) * 1000)

    def reset_auth(self, reason: str = "link closed") -> None:
        """Called by the transport when BlueZ reports the phone is gone."""
        self.auth.reset(reason)

    async def close(self) -> None:
        for task in list(self._subs.values()):
            task.cancel()
        self._subs.clear()

    # ---------------------------------------------------------------- commands

    async def _cmd_hello(self, msg: dict[str, Any]) -> dict[str, Any]:
        # The phone reports its MTU here, before asking for anything big. A
        # reply that does not fit in the MTU never reaches it, so this has to
        # happen before the first real response goes out.
        if self._set_mtu is not None and msg.get("mtu") is not None:
            self._set_mtu(msg["mtu"])

        # A handshake always starts from scratch: whatever session existed
        # belonged to a different connection.
        self.auth.new_session()

        return {
            "name": self.config.get("ble", {}).get("name", "PiBlue"),
            "version": VERSION,
            "hostname": __import__("socket").gethostname(),
            "commands": sorted(self._handlers),
            "auth": self.auth.describe(),
        }

    async def _cmd_auth(self, msg: dict[str, Any]) -> dict[str, Any]:
        if not self.auth.enabled:
            return {"authorized": True, "required": False}
        self.auth.verify(msg.get("mac"))
        return {"authorized": True, "session_s": self.auth.session_idle_s}

    async def _cmd_ping(self, msg: dict[str, Any]) -> dict[str, Any]:
        return {"pong": True, "t": round(time.time(), 3)}

    async def _cmd_info(self, msg: dict[str, Any]) -> dict[str, Any]:
        return await asyncio.to_thread(sysinfo.snapshot, self.config)

    async def _cmd_summary(self, msg: dict[str, Any]) -> dict[str, Any]:
        return await asyncio.to_thread(sysinfo.summary, self.config)

    async def _cmd_sensors(self, msg: dict[str, Any]) -> dict[str, Any]:
        if msg.get("read"):
            return {"sensors": await sensors.read_all(self.config)}
        return {"sensors": await asyncio.to_thread(sensors.list_sensors, self.config)}

    async def _cmd_sensor(self, msg: dict[str, Any]) -> dict[str, Any]:
        # NB: "id" is reserved for the request id, so the sensor is "sensor".
        sensor_id = msg.get("sensor") or msg.get("name")
        if not sensor_id:
            raise KeyError(
                "'sensor' is required, e.g. {\"cmd\":\"sensor\",\"sensor\":\"cpu\"}"
            )
        return await sensors.read_sensor(str(sensor_id), self.config)

    async def _cmd_exec(self, msg: dict[str, Any]) -> dict[str, Any]:
        return await shell.exec_line(msg, self.config)

    async def _cmd_sub(self, msg: dict[str, Any]) -> dict[str, Any]:
        topic = str(msg.get("topic") or "")
        if topic != "summary":
            raise KeyError("only topic 'summary' can be subscribed to for now")

        cfg = self.config.get("agent", {})
        every = float(msg.get("every") or 2.0)
        every = max(float(cfg.get("min_interval_s", 1.0)),
                    min(every, float(cfg.get("max_interval_s", 60.0))))

        await self._stop_sub(topic)
        self._subs[topic] = asyncio.create_task(self._sub_loop(topic, every))
        logger.info("subscription %s every %.1fs", topic, every)
        return {"topic": topic, "every": every}

    async def _cmd_unsub(self, msg: dict[str, Any]) -> dict[str, Any]:
        topic = str(msg.get("topic") or "")
        await self._stop_sub(topic)
        return {"topic": topic, "stopped": True}

    async def _cmd_help(self, msg: dict[str, Any]) -> dict[str, Any]:
        return {
            "commands": {
                "hello": "握手，返回名称/版本/命令列表 + 配对挑战",
                "auth": "用配对码回答挑战，参数 mac（HMAC-SHA256 十六进制）",
                "ping": "连通性探测",
                "info": "完整系统快照",
                "summary": "精简快照（温度/负载/内存/uptime）",
                "sensors": "列出可用传感器；带 read=true 读取全部",
                "sensor": "读取单个传感器，参数 sensor",
                "exec": "执行命令，参数 line（可带 timeout）",
                "sub": "订阅推送，参数 topic=summary, every=秒",
                "unsub": "取消订阅，参数 topic",
            }
        }

    # ------------------------------------------------------------ subscriptions

    async def _stop_sub(self, topic: str) -> None:
        task = self._subs.pop(topic, None)
        if task is None:
            return
        task.cancel()
        try:
            await task
        except (asyncio.CancelledError, Exception):  # noqa: BLE001
            pass

    async def _sub_loop(self, topic: str, every: float) -> None:
        try:
            while True:
                await asyncio.sleep(every)
                try:
                    data = await asyncio.to_thread(sysinfo.summary, self.config)
                    await self._send({"event": topic, "data": data})
                except asyncio.CancelledError:
                    raise
                except Exception as exc:  # noqa: BLE001
                    logger.warning("subscription tick failed: %s", exc)
        except asyncio.CancelledError:
            logger.info("subscription %s stopped", topic)
            raise
