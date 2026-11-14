"""Line-delimited JSON framing.

BLE gives us an unreliable *packet* view of the world: a write arrives as
whatever chunk size the phone's MTU allows and a notification is delivered in
MTU-sized pieces.  To stay independent of that we treat the link as a byte
stream where every message is one UTF-8 JSON object followed by ``\\n``.

The same framing is used by the stdio simulator, so the protocol can be
exercised on a laptop without any Bluetooth at all.
"""

from __future__ import annotations

import json
from typing import Any

#: Anything longer than this without a newline is treated as garbage.  A shell
#: command is the biggest thing we ever receive and 16 KiB is plenty.
MAX_LINE_BYTES = 16 * 1024


class LineDecoder:
    """Reassembles arbitrary byte chunks into JSON objects."""

    def __init__(self, max_line: int = MAX_LINE_BYTES) -> None:
        self._buf = bytearray()
        self._max = max_line
        #: number of frames dropped because they exceeded ``max_line``
        self.overflow = 0

    def feed(self, chunk: bytes) -> list[dict[str, Any]]:
        if chunk:
            self._buf.extend(chunk)

        messages: list[dict[str, Any]] = []
        while True:
            idx = self._buf.find(b"\n")
            if idx < 0:
                if len(self._buf) > self._max:
                    self._buf.clear()
                    self.overflow += 1
                return messages

            raw = bytes(self._buf[:idx]).strip()
            del self._buf[: idx + 1]
            if not raw:
                continue

            try:
                obj = json.loads(raw.decode("utf-8", "replace"))
            except json.JSONDecodeError as exc:
                messages.append({"__parse_error__": f"{exc.msg} @{exc.pos}"})
                continue

            if isinstance(obj, dict):
                messages.append(obj)
            else:
                messages.append({"__parse_error__": "message must be a JSON object"})

    def reset(self) -> None:
        self._buf.clear()


def encode(obj: Any) -> bytes:
    """Serialise one message into a wire frame."""
    payload = json.dumps(obj, ensure_ascii=False, separators=(",", ":"))
    return (payload + "\n").encode("utf-8")
