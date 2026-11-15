"""The pairing code: one shared secret between the Pi and the phone.

The code is 16 base32 characters (80 bits), shown grouped in fours::

    ABCD-EFGH-JKLM-NPQR

It lives in ``/etc/piblue/token`` with mode 0600 rather than in
``config.json``: the config is the file people paste into issues and copy
between machines, the token should not travel with it.

Base32 because it is typed on a phone keyboard: no 0/1/8/9, case-insensitive,
and dashes/spaces are stripped before use. 80 bits is far beyond what anyone
can brute-force over a BLE link, so the key is a plain SHA-256 of the code -
no KDF stretching needed.

Manage it from the Pi::

    sudo /opt/piblue/venv/bin/python -m agent.token show
    sudo /opt/piblue/venv/bin/python -m agent.token new
    sudo /opt/piblue/venv/bin/python -m agent.token set PI-PI-PI-PI-PI-PI-PI

``show`` reads whatever is in effect, so it is the one command worth
remembering.  ``set`` exists because a code you chose yourself is easier to
trust than one you have to go look up.
"""

from __future__ import annotations

import base64
import hashlib
import os
import re
import secrets
import sys
from pathlib import Path
from typing import Any

#: Domain separation, so the HMAC key is never the raw code in any other
#: protocol that might also hash something called "token".
KEY_LABEL = b"piblue-auth-v1:"

ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
TOKEN_BYTES = 10  # -> 16 base32 characters, 80 bits
DEFAULT_PATH = "/etc/piblue/token"

#: Floor for a hand-picked code. 12 characters is 60 bits, which is still far
#: out of reach over BLE given the 5-guess lockout - short enough that a code
#: you can remember is worth more than the extra entropy.
MIN_SET_LEN = 12
RECOMMENDED_LEN = 16


def generate() -> str:
    """Return a fresh 16-character code."""
    return base64.b32encode(secrets.token_bytes(TOKEN_BYTES)).decode().rstrip("=")


def normalize(raw: str) -> str:
    """Strip dashes/spaces and upper-case, so typing style cannot matter."""
    return re.sub(r"[^A-Z2-7]", "", (raw or "").upper())


def pretty(token: str) -> str:
    """``ABCDEFGHIJKLMNPQ`` -> ``ABCD-EFGH-JKLM-NPQ`` for reading aloud."""
    clean = normalize(token)
    return "-".join(clean[i: i + 4] for i in range(0, len(clean), 4))


def derived_key(token: str) -> bytes:
    """The 32-byte HMAC key. Must match ``Pairing.mac()`` on the phone."""
    return hashlib.sha256(KEY_LABEL + normalize(token).encode("utf-8")).digest()


def read_token(cfg: dict[str, Any] | None = None, path: str | None = None) -> str | None:
    """config.auth.token > $PIBLUE_TOKEN > the token file.

    The environment variable exists for ``dev.sh`` and tests; the file is the
    only thing used in production.  ``path`` overrides *where* the file is
    read from (dev mode has its own), never the priority order.
    """
    auth = (cfg or {}).get("auth", {}) if cfg else {}
    inline = normalize(str(auth.get("token") or ""))
    if inline:
        return inline

    from_env = normalize(os.environ.get("PIBLUE_TOKEN", ""))
    if from_env:
        return from_env

    target = Path(path or str(auth.get("token_file") or DEFAULT_PATH))
    try:
        stored = normalize(target.read_text(encoding="utf-8").strip())
    except OSError:
        return None
    return stored or None


def write_token(token: str, path: str = DEFAULT_PATH) -> Path:
    target = Path(path)
    target.parent.mkdir(parents=True, exist_ok=True)
    # Write then chmod, so the secret never exists with a looser mode.
    fd = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as fh:
        fh.write(pretty(token) + "\n")
    os.chmod(target, 0o600)
    return target


def ensure_token(path: str = DEFAULT_PATH) -> tuple[str, bool]:
    """Return ``(token, created)`` - generating one only if none exists."""
    existing = normalize(Path(path).read_text(encoding="utf-8")) if Path(path).exists() else ""
    if existing:
        return existing, False
    token = generate()
    write_token(token, path)
    return token, True


def main(argv: list[str]) -> int:
    from .config import load_config

    cfg = load_config()
    path = str(cfg.get("auth", {}).get("token_file") or DEFAULT_PATH)

    args = list(argv[1:])
    # Pull --file out first: dev mode keeps its code next to the source, and
    # must never write into /etc (a stray experiment would rotate the code the
    # installed service is using).
    for i, arg in enumerate(args):
        if arg == "--file" and i + 1 < len(args):
            path = args[i + 1]
            del args[i: i + 2]
            break
        if arg.startswith("--file="):
            path = arg.split("=", 1)[1]
            del args[i]
            break

    action = (args[0] if args else "show").lower()

    if action == "show":
        token = read_token(cfg, path)
        if not token:
            print(
                f"no pairing code configured ({path} does not exist)\n"
                "  create one:  python -m agent.token new\n"
                "  or choose:   python -m agent.token set MYPI-AB2C-DEF3-GH4J",
                file=sys.stderr,
            )
            return 1
        print(pretty(token))
        return 0

    if action == "new":
        token = generate()
        write_token(token, path)
        print(pretty(token))
        print("rotate done - every phone must enter the new code", file=sys.stderr)
        return 0

    if action == "set":
        raw = " ".join(args[1:])
        if not raw.strip():
            print("usage: python -m agent.token set ABCD-EFGH-JKLM-NPQR [--file PATH]",
                  file=sys.stderr)
            return 2
        # normalize() silently drops everything outside the alphabet, which
        # would turn "1234567890" into an empty code, or let "A_B" pass as two
        # characters while the stored code is really "AB". Reject instead of
        # quietly storing something other than what was typed.
        stray = sorted({c for c in raw.upper() if c not in ALPHABET and c not in "- "})
        if stray:
            print(
                f"cannot use '{' '.join(stray)}': a pairing code is base32 - "
                "A-Z plus 2-7, so no 0/1/8/9 and no punctuation other than dashes.",
                file=sys.stderr,
            )
            return 1
        token = normalize(raw)
        if len(token) < MIN_SET_LEN:
            print(
                f"too short: {len(token)} usable characters ({len(token) * 5} bits), "
                f"need at least {MIN_SET_LEN}. Try {pretty(generate())} if you just "
                "want something random.",
                file=sys.stderr,
            )
            return 1
        if len(token) < RECOMMENDED_LEN:
            print(
                f"note: {len(token)} characters ({len(token) * 5} bits); the generated "
                f"code uses {RECOMMENDED_LEN} ({RECOMMENDED_LEN * 5} bits).",
                file=sys.stderr,
            )
        write_token(token, path)
        print(pretty(token))
        print(f"saved to {path} - every phone must enter this code", file=sys.stderr)
        return 0

    print(
        "usage: python -m agent.token [show|new|set CODE] [--file PATH]\n"
        f"  file: {path}",
        file=sys.stderr,
    )
    return 2


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
