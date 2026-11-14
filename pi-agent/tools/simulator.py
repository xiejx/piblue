"""Drive the agent over stdin/stdout - no Bluetooth, no Raspberry Pi needed.

Useful to iterate on the protocol from a laptop:

    echo '{"id":1,"cmd":"ping"}' | python tools/simulator.py
    python tools/simulator.py            # then type JSON lines by hand

On the Pi itself this also works, which makes it the fastest way to check that
the handlers behave before blaming the Bluetooth link.
"""

from __future__ import annotations

import asyncio
import os
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from agent.agent import Agent  # noqa: E402
from agent.config import load_config  # noqa: E402
from agent.protocol import encode  # noqa: E402


async def main() -> int:
    config = load_config()

    async def send(frame: dict) -> None:
        sys.stdout.buffer.write(encode(frame))
        sys.stdout.buffer.flush()

    agent = Agent(config, send)
    loop = asyncio.get_running_loop()

    while True:
        line = await loop.run_in_executor(None, sys.stdin.buffer.readline)
        if not line:
            break
        await agent.feed(line)

    await agent.close()
    return 0


if __name__ == "__main__":
    os.environ.setdefault("PYTHONIOENCODING", "utf-8")
    raise SystemExit(asyncio.run(main()))
