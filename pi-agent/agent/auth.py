"""Challenge-response authentication for the BLE link.

The GATT service is public: anyone in radio range can connect and write to the
RX characteristic. There is no way to hide it - stopping the advertisement
would hide it from the owner's phone too. So the gate is at the command layer:

    1. phone connects, sends ``hello``
    2. we answer with a fresh 16-byte challenge
    3. phone sends ``auth`` + hex(HMAC-SHA256(key, challenge))
    4. only then is anything except hello/auth/ping answered

Fresh challenge per handshake means a recorded ``(challenge, mac)`` pair is
worthless on replay. The challenge is one-shot: a failed attempt consumes it,
so every guess costs a round trip and, after ``max_attempts``, a lockout.

Sessions end in three ways: on ``hello`` (a new handshake always starts from
scratch), when BlueZ reports the device disconnected (``link_watch``), and
after ``session_idle_s`` without traffic as a backstop for stacks where the
disconnect signal never arrives.
"""

from __future__ import annotations

import hashlib
import hmac
import logging
import secrets
import time
from typing import Any

from .token import MIN_SET_LEN, derived_key, read_token

logger = logging.getLogger("piblue.auth")

CHALLENGE_BYTES = 16


class AuthRejected(Exception):
    """Raised by a command handler that refuses the peer."""

    def __init__(self, message: str) -> None:
        super().__init__(message)
        self.message = message


class AuthGate:
    def __init__(self, cfg: dict[str, Any]) -> None:
        auth = cfg.get("auth", {}) or {}
        self.enabled = bool(auth.get("enabled", True))
        self.max_attempts = max(1, int(auth.get("max_attempts", 5)))
        self.lockout_s = max(0.0, float(auth.get("lockout_s", 60)))
        self.session_idle_s = max(1.0, float(auth.get("session_idle_s", 300)))

        self._token = read_token(cfg)
        self._key = derived_key(self._token) if self._token else None
        self._challenge: bytes | None = None
        self._authorized = False
        self._attempts = 0
        self._locked_until = 0.0
        self._last_seen = 0.0

        if not self.enabled:
            logger.warning(
                "auth is DISABLED - anyone in radio range can run shell commands on this Pi"
            )
        elif self._key is None:
            logger.error(
                "auth is enabled but no pairing code is configured; every client "
                "will be refused. Create one with: "
                "sudo /opt/piblue/venv/bin/python -m agent.token new"
            )
        elif len(self._token or "") < MIN_SET_LEN:
            # Writing the file by hand skips the CLI's length check, so say
            # something: the lockout only covers guesses made over the link,
            # not a captured challenge+mac replayed against a wordlist offline.
            logger.warning(
                "pairing code is only %d characters (%d bits) - anyone who records "
                "one handshake can brute-force it offline; `agent.token set` asks "
                "for at least %d. This still works, it is just easier to guess.",
                len(self._token or ""), len(self._token or "") * 5, MIN_SET_LEN,
            )

    # ------------------------------------------------------------- lifecycle

    def new_session(self) -> str | None:
        """Start a handshake: forget any previous authorisation, new challenge."""
        if self._authorized:
            logger.info("handshake restarted - previous session dropped")
        self._authorized = False
        self._last_seen = 0.0
        self._challenge = secrets.token_bytes(CHALLENGE_BYTES)
        return self._challenge.hex() if self.enabled else None

    def reset(self, reason: str = "link closed") -> None:
        """Drop the session (called when BlueZ says the device is gone)."""
        if not self._authorized:
            return
        logger.info("deauthorised: %s", reason)
        self._authorized = False
        self._challenge = None
        self._last_seen = 0.0

    def touch(self) -> None:
        self._last_seen = time.monotonic()

    # ---------------------------------------------------------------- state

    @property
    def authorized(self) -> bool:
        if not self.enabled:
            return True
        if not self._authorized:
            return False
        if time.monotonic() - self._last_seen > self.session_idle_s:
            logger.info("auth session expired after %.0f s without traffic", self.session_idle_s)
            self._authorized = False
            return False
        return True

    @property
    def locked_for(self) -> float:
        return max(0.0, self._locked_until - time.monotonic())

    # --------------------------------------------------------- verification

    def verify(self, mac_hex: Any) -> None:
        """Raise [AuthRejected] unless ``mac_hex`` signs the outstanding challenge."""
        if not self.enabled:
            self._authorized = True
            return

        if self._key is None:
            raise AuthRejected(
                "this Pi has no pairing code yet - on the Pi run: "
                "sudo /opt/piblue/venv/bin/python -m agent.token new"
            )

        if self.locked_for > 0:
            raise AuthRejected(f"too many wrong codes, locked for {int(self.locked_for) + 1} s")

        if self._challenge is None:
            raise AuthRejected("no challenge outstanding - send {'cmd':'hello'} first")

        expected = hmac.new(self._key, self._challenge, hashlib.sha256).digest()
        try:
            got = bytes.fromhex(str(mac_hex).strip())
        except (TypeError, ValueError):
            got = b""

        if not hmac.compare_digest(got, expected):
            # One shot: a replay of this challenge cannot be retried either.
            self._challenge = None
            self._attempts += 1
            if self._attempts >= self.max_attempts:
                self._locked_until = time.monotonic() + self.lockout_s
                logger.warning("auth failed %d times - locked for %.0f s", self._attempts, self.lockout_s)
                self._attempts = 0
                raise AuthRejected(
                    f"too many wrong codes, locked for {int(self.lockout_s)} s"
                )
            left = self.max_attempts - self._attempts
            logger.warning("auth failed - %d attempt(s) left", left)
            raise AuthRejected(f"wrong pairing code, {left} attempt(s) left")

        self._challenge = None
        self._attempts = 0
        self._authorized = True
        self.touch()
        logger.info("phone authorised for %.0f s of idle time", self.session_idle_s)

    def describe(self) -> dict[str, Any]:
        """The ``auth`` block appended to the ``hello`` reply."""
        if not self.enabled:
            return {"required": False}
        return {
            "required": True,
            "algo": "hmac-sha256",
            "challenge": self._challenge.hex() if self._challenge else "",
            "session_s": self.session_idle_s,
        }
