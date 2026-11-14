"""Constrained shell execution.

The phone runs as root on the Pi, which is exactly as dangerous as it sounds.
Three layers keep it sane:

1. an allow list of binaries (``shell.allowed``) unless ``allow_any`` is on,
2. a deny list that always wins,
3. shell metacharacters are refused unless ``allow_meta`` is on, so
   ``ls; rm -rf /`` cannot get around layer 1.
"""

from __future__ import annotations

import asyncio
import logging
import os
import shlex
import signal
import time
from typing import Any

META_CHARS = set(";|&<>$`\n\r")

logger = logging.getLogger("piblue.shell")


def _kill_tree(proc: asyncio.subprocess.Process) -> None:
    """Kill the whole pipeline, not just the shell in front of it.

    ``kill()`` on ``sh -c "a | b"`` only reaps `sh`; the children keep the
    stdout pipe open, so ``communicate()`` would wait for them anyway.  A new
    session per command lets us signal the entire group at once.
    """
    try:
        if os.name == "posix":
            os.killpg(os.getpgid(proc.pid), signal.SIGKILL)
        else:
            proc.kill()
    except (ProcessLookupError, PermissionError, OSError):
        try:
            proc.kill()
        except ProcessLookupError:
            pass


class ShellRejected(Exception):
    def __init__(self, message: str) -> None:
        super().__init__(message)
        self.message = message


def _effective_binaries(argv: list[str]) -> list[str]:
    """Basenames that will actually run, so `sudo rm -rf /` is still caught."""
    out: list[str] = []
    i = 0
    while i < len(argv):
        base = os.path.basename(argv[i]).lower()
        out.append(base)
        if base not in ("sudo", "env", "nice"):
            break
        i += 1
        while i < len(argv) and (argv[i].startswith("-") or "=" in argv[i]):
            i += 1
    return out


def _check(line: str, cfg: dict[str, Any]) -> tuple[list[str], str | None]:
    """Return (argv, shell_line). Exactly one of them is None."""
    if not line.strip():
        raise ShellRejected("command is empty")

    allow_meta = bool(cfg.get("allow_meta", False))
    if not allow_meta:
        bad = META_CHARS.intersection(line)
        if bad:
            raise ShellRejected(
                "shell metacharacters are blocked ({}); enable shell.allow_meta "
                "in the config if you need pipes or redirects".format(
                    " ".join(sorted(bad))
                )
            )

    try:
        argv = shlex.split(line)
    except ValueError as exc:
        raise ShellRejected(f"cannot parse command: {exc}") from exc
    if not argv:
        raise ShellRejected("command is empty")

    deny = {b.lower() for b in cfg.get("deny", [])}
    allowed = {b.lower() for b in cfg.get("allowed", [])}

    for base in _effective_binaries(argv):
        if base in deny:
            raise ShellRejected(f"'{base}' is on the deny list")
        if not cfg.get("allow_any", False) and base not in allowed:
            raise ShellRejected(
                f"'{base}' is not in the allow list "
                "(add it to shell.allowed or set shell.allow_any=true)"
            )

    if allow_meta:
        return None, line
    return argv, None


async def exec_line(args: dict[str, Any], config: dict[str, Any]) -> dict[str, Any]:
    cfg = config.get("shell", {})
    if not cfg.get("enabled", True):
        raise ShellRejected("shell execution is disabled in the config")

    line = str(args.get("line") or args.get("cmdline") or "")
    timeout = float(args.get("timeout") or cfg.get("timeout_s", 10))
    timeout = max(0.5, min(timeout, 120.0))
    max_out = int(cfg.get("max_output_bytes", 8192))

    argv, shell_line = _check(line, cfg)

    started = time.monotonic()
    spawn: dict[str, Any] = {}
    if os.name == "posix":
        spawn["start_new_session"] = True

    if shell_line is not None:
        proc = await asyncio.create_subprocess_shell(
            shell_line,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
            **spawn,
        )
    else:
        proc = await asyncio.create_subprocess_exec(
            *argv,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
            **spawn,
        )

    timed_out = False
    # communicate() is started once and waited on, never restarted: cancelling
    # it would detach the pipe readers and we would lose partial output.
    reader = asyncio.ensure_future(proc.communicate())
    done, _ = await asyncio.wait({reader}, timeout=timeout)

    if done:
        raw_out, raw_err = reader.result()
    else:
        timed_out = True
        _kill_tree(proc)
        done, _ = await asyncio.wait({reader}, timeout=3.0)
        if done:
            raw_out, raw_err = reader.result()
        else:
            # A grandchild is still holding the pipe; give up on its output
            # rather than blocking the whole BLE link.
            reader.cancel()
            raw_out, raw_err = b"", b""
            logger.warning("command %r did not release its pipes after kill", line[:80])

    stdout = raw_out.decode("utf-8", "replace")
    stderr = raw_err.decode("utf-8", "replace")
    truncated = len(stdout) > max_out or len(stderr) > max_out

    return {
        "stdout": stdout[:max_out],
        "stderr": stderr[:max_out],
        "code": proc.returncode if proc.returncode is not None else -1,
        "timed_out": timed_out,
        "truncated": truncated,
        "duration_ms": int((time.monotonic() - started) * 1000),
    }
