"""Privileged actions, addressed by name.

The phone never sends a command line here. It picks an action and supplies
*data* - an SSID, a password, a block device it saw in ``disk-list`` - and each
of those is validated before it reaches argv, so there is nowhere to smuggle a
shell fragment in. Anything not in the action table does not exist.

Why not just tell people to turn on ``shell.allow_any``? Because then one
mistyped tap is a root shell. Here the vocabulary is fixed and small: reboot,
poweroff, restart a unit, mount, umount, wifi. Mount points are generated under
``control.mount_root`` and devices come from ``lsblk``, never from the phone.

Two things this module deliberately does *not* do: format block devices and
touch partitions. Those are the operations that destroy data, and there is no
undo for them over a 30-metre radio link.
"""

from __future__ import annotations

import asyncio
import json
import logging
import os
import posixpath
import re
import shutil
import time
from typing import Any

from . import shell
from ..config import DEFAULT_CONFIG

logger = logging.getLogger("piblue.control")

#: Hard ceiling for ``control.custom``. Five fits on one screen without
#: scrolling, which is the point of the page.
MAX_CUSTOM = 5

#: Length limits for an app-written custom command. A name longer than this
#: does not fit on a chip, and a line longer than this is not a one-liner
#: anybody can recognise before tapping it.
CUSTOM_NAME_MAX = 40
CUSTOM_LINE_MAX = 500

#: Mount directory names are chosen by the user, so the rule has to be narrow:
#: one path component, no separators, no leading dot. ``..`` would otherwise
#: escape ``mount_root`` entirely.
MOUNT_NAME_MAX = 32
_MOUNT_NAME = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]*$")

#: Actions that cannot be taken back once they start, so the phone has to say
#: ``confirm: true``. A stray tap in a pocket should not power off a Pi that is
#: halfway through writing something.
DANGEROUS = frozenset({"reboot", "shutdown"})

#: Everything the page can ask for. ``status`` reports which of these the config
#: actually allows, and the app only draws those buttons.
ACTIONS = (
    "reboot",
    "shutdown",
    "restart-agent",
    "restart-bluetooth",
    "wifi-enable",
    "wifi-disable",
    "wifi-scan",
    "wifi-connect",
    "wifi-disconnect",
    "disk-list",
    "disk-mount",
    "disk-unmount",
    "custom",
    "custom-save",
)

#: Filesystems we refuse to mount: mounting is harmless, but the *lookup* of a
#: swap partition or a bare disk in a mount call is where confusion starts.
_SKIP_FSTYPES = frozenset({"swap", "crypto_LUKS", "zfs_member", "LVM2_member"})

#: Pseudo block devices that carry a filesystem but are not storage a phone
#: should ever mount: the eMMC boot partitions and loop/ram devices.
_SKIP_NAMES = re.compile(r"^(mmcblk\d+boot\d+|loop\d+|ram\d+|nbd\d+)$")

#: NTFS as ``lsblk`` spells it. ``fuseblk`` is what a *mounted* NTFS volume
#: reports when ntfs-3g owns it, so it means the same thing here.
_NTFS_FSTYPES = frozenset({"ntfs", "ntfs3", "fuseblk"})

#: The driver we pin for NTFS. Kernel 5.15+ ships ntfs3, which is noticeably
#: faster than the userspace ntfs-3g that ``mount`` picks by default, and it is
#: the one that can write to a volume ntfs-3g refuses. Every other filesystem
#: is left to mount's own probe.
_NTFS_DRIVER = "ntfs3"

#: Filesystems that store no POSIX owner, so every file inside is shown with
#: whatever uid/gid the mount was given. Only NTFS is mounted here, and only
#: NTFS gets the owner fix; ext4 keeps the owners written on disk.
_OWNER_FSTYPES = _NTFS_FSTYPES

#: Login user that should own a mounted NTFS volume when the config says
#: nothing (``control.mount_owner``). ``pi`` is the account on a stock
#: Raspberry Pi image.
_DEFAULT_MOUNT_OWNER = "pi"

#: Mount points a phone must never be able to unmount, whatever ``lsblk`` says
#: about the device. A USB stick is never mounted on these, but one wrong ``rm``
#: flag in a driver should not be enough to take the root filesystem away.
_CRITICAL_MOUNTS = frozenset(
    {
        "/",
        "/boot",
        "/boot/firmware",
        "/usr",
        "/var",
        "/etc",
        "/home",
        "/opt",
        "/root",
        "/srv",
        "/tmp",
        "/proc",
        "/sys",
        "/dev",
        "/run",
    }
)


def _can_unmount(mountpoint: str | None, removable: bool, root: str) -> bool:
    if not mountpoint:
        return False
    normalised = mountpoint.rstrip("/") or "/"
    if normalised in _CRITICAL_MOUNTS:
        return False
    base = root.rstrip("/") or "/"
    # "startswith(root)" alone would also match /mntfoo for a root of /mnt.
    if base == "/" or normalised == base or normalised.startswith(base + "/"):
        return True
    # A stick that the desktop auto-mounted under /media/pi is still a stick.
    # Being able to unmount it is the difference between ejecting it safely and
    # yanking it out mid-write - and unmounting cannot lose data the way
    # formatting would.
    return removable

_SAFE_LABEL = re.compile(r"[^A-Za-z0-9._-]+")

SSID_MAX = 32
PSK_MIN = 8
PSK_MAX = 63

#: Delayed tasks (power off) are kept referenced, otherwise the loop may
#: collect them before they fire.
_PENDING: set[asyncio.Task[Any]] = set()


class ControlRejected(Exception):
    """A request the Pi refuses. ``message`` is shown to the user as-is."""

    def __init__(self, message: str) -> None:
        super().__init__(message)
        self.message = message


# --------------------------------------------------------------- process glue


def _prefix() -> list[str]:
    """Run privileged helpers through sudo when the agent is not root.

    In service mode the unit runs as root and this is empty. In dev mode the
    agent runs as the login user, so ``-n`` is used to fail fast instead of
    hanging on a password prompt that nobody can answer over BLE.
    """
    try:
        if os.geteuid() == 0:
            return []
    except AttributeError:  # Windows, for the simulator
        return []
    return ["sudo", "-n"]


async def _run(
    argv: list[str], timeout: float = 20.0
) -> tuple[int, str, str]:
    """Run one command, never through a shell, and never with a timeout hang."""
    try:
        proc = await asyncio.create_subprocess_exec(
            *argv,
            stdin=asyncio.subprocess.DEVNULL,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
        )
    except (FileNotFoundError, PermissionError, OSError) as exc:
        raise ControlRejected(f"无法执行 {argv[0]}：{exc}") from exc

    try:
        out, err = await asyncio.wait_for(proc.communicate(), timeout)
    except asyncio.TimeoutError:
        proc.kill()
        raise ControlRejected(f"{argv[0]} 超时（{timeout:.0f} 秒）") from None

    return (
        proc.returncode if proc.returncode is not None else -1,
        out.decode("utf-8", "replace").strip(),
        err.decode("utf-8", "replace").strip(),
    )


async def _run_ok(argv: list[str], timeout: float = 20.0) -> str:
    code, out, err = await _run(argv, timeout)
    if code != 0:
        detail = err or out or f"退出码 {code}"
        raise ControlRejected(f"{argv[0]} 失败：{detail[:300]}")
    return out


def _spawn_later(delay: float, argv: list[str]) -> None:
    """Fire and forget, after the reply frame has had time to leave."""

    async def _later() -> None:
        await asyncio.sleep(delay)
        try:
            await _run(argv, timeout=15.0)
        except ControlRejected as exc:
            logger.warning("delayed command failed: %s", exc.message)

    task = asyncio.create_task(_later())
    _PENDING.add(task)
    task.add_done_callback(_PENDING.discard)


async def _systemd_later(delay: float, *argv: str) -> None:
    """Restart a unit *outside* our own cgroup.

    A plain child process would be killed together with piblue.service, which
    is exactly the wrong moment to stop halfway. ``systemd-run --on-active``
    hands the job to PID 1 instead.
    """
    if not shutil.which("systemd-run"):
        raise ControlRejected(
            "这台机器没有 systemd-run，无法在重启自身的同时回包；"
            "请在 SSH 里手动执行 sudo systemctl restart <unit>"
        )
    # A unique unit name per call: `systemd-run --unit=` fails with
    # "already exists" if a previous transient unit is still pending, and two
    # taps in a row is exactly how that happens. --collect reaps them.
    unit_name = f"piblue-oneshot-{int(time.monotonic() * 1000) % 1000000}"
    await _run_ok(
        [
            *_prefix(),
            "systemd-run",
            "--quiet",
            "--collect",
            f"--on-active={delay:g}s",
            f"--unit={unit_name}",
            *argv,
        ],
        timeout=10.0,
    )


def _delay(msg: dict[str, Any], default: float = 2.0) -> float:
    try:
        value = float(msg.get("delay_s", default))
    except (TypeError, ValueError):
        value = default
    return max(0.5, min(value, 60.0))


# ------------------------------------------------------------------- config


def _cfg(config: dict[str, Any]) -> dict[str, Any]:
    return config.get("control", {}) or {}


def allowed(config: dict[str, Any]) -> set[str]:
    cfg = _cfg(config)
    if not cfg.get("enabled", True):
        return set()
    listed = cfg.get("allow")
    if listed is None:
        permit = set(ACTIONS)
    else:
        permit = {str(a) for a in listed} & set(ACTIONS)
    # Editing follows running. A config.json written before `custom-save`
    # existed still lists only `custom`, and an upgrade must not turn into
    # "the add button works but the Pi says the action is not enabled".
    if cfg.get("allow_custom_edit", True):
        if "custom" in permit:
            permit.add("custom-save")
    else:
        permit.discard("custom-save")
    return permit


def mount_root(config: dict[str, Any]) -> str:
    root = str(_cfg(config).get("mount_root") or "/mnt/piblue")
    if not root.startswith("/"):
        root = "/mnt/piblue"
    return root.rstrip("/") or "/mnt/piblue"


def custom_path() -> str:
    """Where the app-owned custom commands live.

    Next to ``config.json``, not inside it: the app rewrites this file on every
    save, and rewriting the operator's whole config just to add one button would
    throw away every key the app does not model. A dev run that points
    ``PIBLUE_CONFIG`` at the repo therefore gets its own copy and never touches
    ``/etc/piblue``.
    """
    override = os.environ.get("PIBLUE_CUSTOM")
    if override:
        return override
    from ..config import CONFIG_PATH

    return os.path.join(os.path.dirname(CONFIG_PATH) or ".", "custom.json")


def _read_custom_file() -> list[Any] | None:
    """``None`` means "no app-managed file yet"; a list means "this is the list"."""
    path = custom_path()
    try:
        with open(path, "r", encoding="utf-8") as fh:
            data = json.load(fh)
    except FileNotFoundError:
        return None
    except (OSError, json.JSONDecodeError) as exc:
        logger.warning("cannot read %s (%s); falling back to control.custom", path, exc)
        return None
    if isinstance(data, dict):
        data = data.get("items")
    if not isinstance(data, list):
        logger.warning("%s must hold a list of items; ignoring it", path)
        return None
    return data


def _write_custom_file(items: list[dict[str, str]]) -> str:
    """Replace the file atomically so a power cut cannot leave it half-written."""
    path = custom_path()
    directory = os.path.dirname(path)
    if directory:
        os.makedirs(directory, exist_ok=True)
    tmp = f"{path}.tmp"
    with open(tmp, "w", encoding="utf-8") as fh:
        json.dump({"items": items}, fh, ensure_ascii=False, indent=2)
        fh.write("\n")
    os.chmod(tmp, 0o644)
    os.replace(tmp, path)
    return path


def custom_allow_meta(config: dict[str, Any]) -> bool:
    """Pipes and redirects inside a custom command, on by default.

    A custom command is written once and deliberately, then run by tapping a
    button - the same trust level as an entry hand-written in config.json. The
    deny list still applies, so ``rm`` / ``dd`` stay refused.
    """
    return bool(_cfg(config).get("custom_allow_meta", True))


def custom_commands(config: dict[str, Any]) -> list[dict[str, Any]]:
    """The effective list, normalised: drop junk, keep at most five.

    ``custom.json`` wins when it exists (the app owns it from that moment on);
    the ``control.custom`` array in config.json is the seed used until the first
    save, so hand-written entries keep working exactly as before.
    """
    raw = _read_custom_file()
    if raw is None:
        raw = _cfg(config).get("custom") or []
    if not isinstance(raw, list):
        raise ControlRejected("control.custom 必须是数组")
    out: list[dict[str, Any]] = []
    for index, item in enumerate(raw):
        if len(out) >= MAX_CUSTOM:
            logger.warning("custom commands exceed %d entries; extra ignored", MAX_CUSTOM)
            break
        if not isinstance(item, dict):
            logger.warning("custom[%d] is not an object; ignored", index)
            continue
        line = str(item.get("line") or "").strip()
        if not line:
            continue
        name = str(item.get("name") or "").strip() or line
        out.append({"index": len(out), "name": name[:CUSTOM_NAME_MAX], "line": line})
    return out


def _check_custom(items_raw: Any) -> list[dict[str, str]]:
    """Validate what the phone wants to save, before it reaches the disk."""
    if not isinstance(items_raw, list):
        raise ControlRejected("items 必须是数组")
    if len(items_raw) > MAX_CUSTOM:
        raise ControlRejected(f"最多只能保存 {MAX_CUSTOM} 条自定义命令")
    out: list[dict[str, str]] = []
    for position, item in enumerate(items_raw, start=1):
        if not isinstance(item, dict):
            raise ControlRejected(f"第 {position} 条格式不对")
        name = str(item.get("name") or "").strip()
        line = str(item.get("line") or "").strip()
        if not line:
            raise ControlRejected(f"第 {position} 条没有填要执行的命令")
        if len(line) > CUSTOM_LINE_MAX:
            raise ControlRejected(f"第 {position} 条命令太长（最多 {CUSTOM_LINE_MAX} 个字符）")
        if len(name) > CUSTOM_NAME_MAX:
            raise ControlRejected(f"第 {position} 条名称太长（最多 {CUSTOM_NAME_MAX} 个字符）")
        if any(ch in line for ch in "\r\n") or any(ch in name for ch in "\r\n"):
            raise ControlRejected(f"第 {position} 条不能换行")
        out.append({"name": name or line[:CUSTOM_NAME_MAX], "line": line})
    return out


def _guard(config: dict[str, Any], action: str) -> None:
    if not _cfg(config).get("enabled", True):
        raise ControlRejected("控制功能已在树莓派上关闭（control.enabled=false）")
    if action not in allowed(config):
        raise ControlRejected(f"动作 '{action}' 未在 control.allow 中启用")


# --------------------------------------------------------------------- power


async def _power(action: str, msg: dict[str, Any]) -> dict[str, Any]:
    if action in DANGEROUS and not msg.get("confirm"):
        raise ControlRejected(f"{action} 需要确认（confirm=true）")
    unit = "reboot" if action == "reboot" else "poweroff"
    delay = _delay(msg)
    argv = [*_prefix(), "systemctl", "--no-block", unit]
    _spawn_later(delay, argv)
    logger.warning("scheduling %s in %.1fs", unit, delay)
    return {
        "scheduled": True,
        "action": action,
        "delay_s": delay,
        "message": f"{'重启' if action == 'reboot' else '关机'}将在 {delay:.0f} 秒后执行",
    }


async def _restart_unit(action: str, msg: dict[str, Any]) -> dict[str, Any]:
    delay = _delay(msg, default=2.0)
    if action == "restart-agent":
        units = ["piblue"]
    else:
        # Restarting bluetooth.service alone leaves the agent holding a D-Bus
        # registration that no longer exists, so it would keep running and
        # advertise nothing - the exact problem this button is meant to fix.
        # piblue is `After=bluetooth`, so passing both lets systemd bring them
        # back in the right order.
        units = ["bluetooth", "piblue"]
    await _systemd_later(delay, "systemctl", "restart", *units)
    logger.warning("units %s will restart in %.1fs", units, delay)
    what = "蓝牙服务和 agent" if len(units) > 1 else "agent 服务"
    return {
        "scheduled": True,
        "action": action,
        "units": units,
        "delay_s": delay,
        "message": f"{what} 将在 {delay:.0f} 秒后重启，蓝牙连接会断开，等它回来重连即可",
    }


# ---------------------------------------------------------------------- wifi


def _wifi_backend() -> str | None:
    if shutil.which("nmcli"):
        return "nmcli"
    if shutil.which("wpa_cli"):
        return "wpa_cli"
    return None


def _nm_split(line: str) -> list[str]:
    """Split one ``nmcli -t`` row.

    ``-t`` escapes the separator as ``\\:`` and a backslash as ``\\\\``; a naive
    ``str.split(":")`` mangles every SSID that contains a colon, and an SSID may
    contain anything.
    """
    out: list[str] = []
    cur: list[str] = []
    i = 0
    while i < len(line):
        ch = line[i]
        if ch == "\\" and i + 1 < len(line):
            cur.append(line[i + 1])
            i += 2
            continue
        if ch == ":":
            out.append("".join(cur))
            cur = []
            i += 1
            continue
        cur.append(ch)
        i += 1
    out.append("".join(cur))
    return out


def _wifi_iface() -> str | None:
    base = "/sys/class/net"
    try:
        names = sorted(os.listdir(base))
    except OSError:
        return None
    for name in names:
        if os.path.isdir(os.path.join(base, name, "wireless")):
            return name
    for name in names:
        if name.startswith("wl"):
            return name
    return None


def _wifi_enabled() -> bool:
    """Radio state from rfkill, which is the same on both backends."""
    try:
        import subprocess

        out = subprocess.run(
            ["rfkill", "-o", "TYPE,SOFT,HARD"],
            capture_output=True,
            text=True,
            timeout=2.0,
            check=False,
        ).stdout
    except (OSError, subprocess.SubprocessError):
        return True  # no rfkill: assume the radio is on
    for line in out.splitlines():
        cols = line.split()
        if len(cols) >= 3 and cols[0] == "wlan":
            return cols[1] == "unblocked" and cols[2] == "unblocked"
    return True


def _nm_saved_profiles() -> list[dict[str, Any]]:
    """Wi-Fi profiles NetworkManager already holds a secret for.

    This is the thing the phone is allowed to know and the Pi is not: that a
    network is *known*, never what its password is. One ``-g`` query per
    profile, because an SSID may contain a colon and splitting ``-t`` output
    would corrupt it.
    """
    code, out, _ = _run_sync(
        ["nmcli", "-t", "-f", "NAME,TYPE", "connection", "show"], timeout=8.0
    )
    if code != 0:
        return []
    names: list[str] = []
    for line in out.splitlines():
        cols = _nm_split(line)
        if len(cols) >= 2 and "wireless" in cols[1]:
            names.append(cols[0])
    if not names:
        return []

    profiles: list[dict[str, Any]] = []
    for name in names:
        code, ssid, _ = _run_sync(
            ["nmcli", "-g", "802-11-wireless.ssid", "connection", "show", name],
            timeout=6.0,
        )
        ssid = ssid.strip()
        if code != 0 or not ssid:
            continue
        profiles.append({"ssid": ssid, "name": name})
    return profiles


def _saved_ssids() -> list[str]:
    """SSIDs the Pi can join again without being told the password."""
    backend = _wifi_backend()
    if backend == "nmcli":
        return [p["ssid"] for p in _nm_saved_profiles()]
    if backend == "wpa_cli":
        iface = _wifi_iface()
        if not iface:
            return []
        code, out, _ = _run_sync(["wpa_cli", "-i", iface, "list_networks"], timeout=6.0)
        if code != 0:
            return []
        ssids: list[str] = []
        for line in out.splitlines()[1:]:  # first line is the header
            cols = line.split("\t")
            if len(cols) >= 2 and cols[1].strip():
                ssids.append(cols[1].strip())
        return ssids
    return []


def _wifi_status() -> dict[str, Any]:
    """Blocking snapshot; callers in async code wrap it in ``to_thread``."""
    backend = _wifi_backend()
    iface = _wifi_iface()
    status: dict[str, Any] = {
        "available": backend is not None and iface is not None,
        "backend": backend,
        "iface": iface,
        "enabled": _wifi_enabled(),
        "ssid": None,
        "signal": None,
        "state": None,
        "saved": [],
    }
    if backend is None or iface is None:
        return status

    try:
        status["saved"] = _saved_ssids()
    except ControlRejected:
        pass

    try:
        if backend == "nmcli":
            code, out, _ = _run_sync(["nmcli", "-t", "-f", "DEVICE,STATE,CONNECTION", "dev"])
            for line in out.splitlines():
                cols = _nm_split(line)
                if len(cols) >= 3 and cols[0] == iface:
                    status["state"] = cols[1]
                    status["ssid"] = cols[2] or None
            code, out, _ = _run_sync(["nmcli", "-t", "-f", "active,ssid,signal", "dev", "wifi"])
            for line in out.splitlines():
                cols = _nm_split(line)
                if len(cols) >= 3 and cols[0] == "yes":
                    status["ssid"] = cols[1] or status["ssid"]
                    try:
                        status["signal"] = int(cols[2])
                    except ValueError:
                        pass
                    break
        else:
            out = _run_sync(["wpa_cli", "-i", iface, "status"])[1]
            for line in out.splitlines():
                key, _, value = line.partition("=")
                if key == "ssid":
                    status["ssid"] = value or None
                elif key == "wpa_state":
                    status["state"] = value
                elif key == "signal_level" and value.lstrip("-").isdigit():
                    status["signal"] = int(value)
    except ControlRejected:
        pass
    return status


def _run_sync(argv: list[str], timeout: float = 6.0) -> tuple[int, str, str]:
    """Small blocking call for the status snapshot.

    Everything here is a few milliseconds; the async wrapper exists for the
    actions that can take seconds (associating, mounting).
    """
    import subprocess

    try:
        proc = subprocess.run(
            argv, capture_output=True, text=True, timeout=timeout, check=False
        )
    except (OSError, subprocess.SubprocessError) as exc:
        raise ControlRejected(f"无法执行 {argv[0]}：{exc}") from exc
    return proc.returncode, proc.stdout.strip(), proc.stderr.strip()


def _check_ssid(raw: Any) -> str:
    ssid = str(raw or "")
    if not ssid:
        raise ControlRejected("缺少 ssid")
    if len(ssid.encode("utf-8")) > SSID_MAX:
        raise ControlRejected(f"ssid 太长（最多 {SSID_MAX} 字节）")
    if any(ord(ch) < 0x20 for ch in ssid):
        raise ControlRejected("ssid 含控制字符")
    if '"' in ssid or "\\" in ssid:
        # wpa_cli feeds the value back through its own parser; a quote would
        # change the argument boundaries.
        raise ControlRejected("ssid 不能包含引号或反斜杠")
    return ssid


def _check_psk(raw: Any) -> str:
    psk = str(raw or "")
    if not psk:
        return ""
    if not (PSK_MIN <= len(psk) <= PSK_MAX):
        raise ControlRejected(f"密码长度需在 {PSK_MIN}-{PSK_MAX} 之间")
    if any(ord(ch) < 0x20 for ch in psk):
        raise ControlRejected("密码含控制字符")
    if '"' in psk or "\\" in psk:
        raise ControlRejected("密码不能包含引号或反斜杠")
    return psk


async def _resume_after_enable(backend: str, iface: str | None) -> None:
    """Get back onto a saved network once the radio is up again.

    Turning the radio back on does **not** restore the connection by itself.
    NetworkManager keeps a per-device "manually disconnected" flag - set by our
    own ``wifi-disconnect`` action, and also written to its state file - and
    while that flag is set every autoconnect is suppressed, which is exactly
    the "I turned Wi-Fi back on and nothing happened" symptom. ``device
    connect`` clears it and runs autoconnect.
    """
    if backend == "nmcli":
        if not iface:
            return
        try:
            # --wait bounds this; the status read afterwards is what produces
            # the message, so a failure here is only advisory.
            await _run(
                [*_prefix(), "nmcli", "--wait", "20", "device", "connect", iface],
                timeout=30.0,
            )
        except ControlRejected as exc:
            logger.info("device connect after wifi-enable: %s", exc.message)
        return
    if backend == "wpa_cli" and iface:
        await _run(["wpa_cli", "-i", iface, "reconfigure"], timeout=10.0)
        await _run(["wpa_cli", "-i", iface, "reconnect"], timeout=10.0)


async def _wifi_on_off(on: bool) -> dict[str, Any]:
    backend = _wifi_backend()
    iface = _wifi_iface()
    if backend == "nmcli":
        await _run_ok(
            [*_prefix(), "nmcli", "radio", "wifi", "on" if on else "off"], timeout=15.0
        )
    elif backend == "wpa_cli":
        # rfkill is not guaranteed on a diet image; a missing one is not fatal
        # because `ip link` still does the useful half of the job.
        try:
            await _run_ok(
                [*_prefix(), "rfkill", "unblock" if on else "block", "wifi"], timeout=10.0
            )
        except ControlRejected:
            logger.info("rfkill unavailable, falling back to ip link")
        if iface and on:
            await _run(["ip", "link", "set", iface, "up"], timeout=10.0)
    else:
        raise ControlRejected("这台机器既没有 nmcli 也没有 wpa_cli，无法控制 Wi-Fi")

    if not on:
        return {
            "enabled": False,
            "message": "Wi-Fi 已禁用",
            "wifi": await asyncio.to_thread(_wifi_status),
        }

    # Belt and braces: on some images NetworkManager is installed but the radio
    # is hard-blocked, and nmcli reports success while nothing happens.
    try:
        await _run([*_prefix(), "rfkill", "unblock", "wifi"], timeout=8.0)
    except ControlRejected:
        pass

    await _resume_after_enable(backend, iface)
    status = await asyncio.to_thread(_wifi_status)
    if status.get("ssid"):
        message = f"Wi-Fi 已启用，已连接到 {status['ssid']}"
    elif not status.get("saved"):
        message = "Wi-Fi 已启用，但树莓派没有保存过任何网络，请扫描后连一个"
    else:
        message = "Wi-Fi 已启用，正在尝试连接已保存的网络（找不到就在范围外，点「扫描」看看）"
    return {"enabled": True, "message": message, "wifi": status}


async def _wifi_scan() -> dict[str, Any]:
    backend = _wifi_backend()
    if backend is None:
        raise ControlRejected("这台机器既没有 nmcli 也没有 wpa_cli，无法扫描")
    iface = _wifi_iface()
    networks: list[dict[str, Any]] = []
    known = set(await asyncio.to_thread(_saved_ssids))

    if backend == "nmcli":
        # --rescan yes kicks a fresh scan instead of showing a cached list from
        # five minutes ago, which is what the phone is trying to avoid.
        _, out, err = await _run(
            ["nmcli", "-t", "-f", "SSID,SIGNAL,SECURITY", "dev", "wifi", "list", "--rescan", "yes"],
            timeout=25.0,
        )
        if not out and err:
            raise ControlRejected(f"扫描失败：{err[:200]}")
        best: dict[str, dict[str, Any]] = {}
        for line in out.splitlines():
            cols = _nm_split(line)
            if len(cols) < 2:
                continue
            ssid = cols[0]
            if not ssid:
                continue  # hidden AP
            try:
                signal = int(cols[1])
            except ValueError:
                continue
            security = cols[2] if len(cols) > 2 else ""
            if security in ("", "--"):
                security = ""
            if ssid not in best or signal > int(best[ssid]["signal"]):
                best[ssid] = {
                    "ssid": ssid,
                    "signal": signal,
                    "security": security,
                    "saved": ssid in known,
                }
        networks = sorted(best.values(), key=lambda n: -int(n["signal"]))
    else:
        if iface is None:
            raise ControlRejected("找不到无线网卡")
        await _run(["wpa_cli", "-i", iface, "scan"], timeout=10.0)
        # The scan result is not ready immediately; a fixed short wait is the
        # only signal wpa_cli gives us.
        await asyncio.sleep(3.0)
        _, out, err = await _run(["wpa_cli", "-i", iface, "scan_results"], timeout=10.0)
        if not out and err:
            raise ControlRejected(f"扫描失败：{err[:200]}")
        for line in out.splitlines():
            if line.startswith("bssid") or not line.strip():
                continue
            cols = line.split("\t")
            if len(cols) < 5:
                continue
            flags, ssid = cols[3], cols[4]
            if not ssid or "ESS" not in flags:
                continue
            try:
                signal = int(cols[2])
            except ValueError:
                continue
            security = "WPA" if ("WPA" in flags or "RSN" in flags) else ""
            networks.append(
                {"ssid": ssid, "signal": signal, "security": security, "saved": ssid in known}
            )

    saved_here = sum(1 for n in networks if n["saved"])
    message = f"扫描到 {len(networks)} 个网络"
    if saved_here:
        message += f"，其中 {saved_here} 个已保存过密码"
    return {
        "networks": networks,
        "count": len(networks),
        "saved": sorted(known),
        "message": message,
    }


async def _nm_scan_cache() -> list[tuple[str, str]]:
    """(ssid, security) pairs from the scan list nmcli already has."""
    _, out, _ = await _run(
        ["nmcli", "-t", "-f", "SSID,SECURITY", "dev", "wifi", "list"], timeout=20.0
    )
    rows: list[tuple[str, str]] = []
    for line in out.splitlines():
        cols = _nm_split(line)
        if len(cols) >= 2 and cols[0]:
            rows.append((cols[0], cols[1]))
    return rows


async def _nm_security(ssid: str) -> tuple[str, bool]:
    """What nmcli thinks this SSID's security is, and whether it saw it at all.

    A hidden AP never shows up in the scan list, and a stale list may still
    describe an AP that has since changed. Both cases end up here as
    ``("", False)``, which is why the caller must not treat "unknown" as "open".
    """
    for name, security in await _nm_scan_cache():
        if name == ssid:
            return security, True
    try:
        await _run([*_prefix(), "nmcli", "dev", "wifi", "rescan"], timeout=20.0)
    except ControlRejected:
        pass  # "Scanning not allowed immediately following previous scan"
    await asyncio.sleep(4.0)
    for name, security in await _nm_scan_cache():
        if name == ssid:
            return security, True
    return "", False


def _key_mgmt_for(security: str, psk: str) -> str:
    """Map an ``nmcli`` SECURITY string to the key-mgmt libnm needs.

    libnm has no default for this: a wifi-security setting without ``key-mgmt``
    is invalid, and the only diagnostic it gives is
    ``802-11-wireless-security.key-mgmt: property is missing``.
    """
    sec = (security or "").upper().strip()
    if not sec or sec == "--":
        # Unscanned or hidden: a password means the user knows better than the
        # cache does. No password means we have to assume open.
        return "wpa-psk" if psk else "none"
    if "802.1X" in sec or "EAP" in sec:
        return "eap"
    if "WEP" in sec:
        return "wep"
    if "WPA3" in sec and "WPA2" not in sec:
        return "sae"
    # "WPA1 WPA2" and "WPA2 WPA3" transition modes both accept a PSK.
    return "wpa-psk"


def _connect_hint(detail: str, ssid: str) -> str:
    low = detail.lower()
    if "secrets were required" in low or "no secrets" in low:
        return f"「{ssid}」需要密码，当前按开放网络尝试，请返回重新输入密码"
    if "not authorized" in low or "insufficient privileges" in low:
        return "没有修改网络配置的权限；dev 模式下请用 sudo 启动 agent"
    if "no network with ssid" in low or "could not be found" in low or "no suitable device" in low:
        return f"没找到「{ssid}」：可能超出范围或是 5GHz-only 热点；靠近一点或点「扫描」再试"
    return f"连接失败：{detail[:300]}"


async def _nm_activate(name: str, ssid: str) -> None:
    code, out, err = await _run(
        [*_prefix(), "nmcli", "--wait", "45", "connection", "up", "id", name],
        timeout=60.0,
    )
    detail = (err or out or "").strip()
    if code != 0 and "already active" not in detail.lower():
        raise ControlRejected(_connect_hint(detail, ssid))


async def _nm_connected(ssid: str, suffix: str = "") -> dict[str, Any]:
    status = await asyncio.to_thread(_wifi_status)
    if status.get("ssid") != ssid:
        # `con up` returning 0 means NM activated the profile, so trust that
        # over a status snapshot that may still predate the DHCP lease.
        return {
            "ssid": ssid,
            "wifi": status,
            "message": f"{ssid} 已激活，正在获取 IP（几秒后点右上角刷新）",
        }
    return {"ssid": ssid, "wifi": status, "message": f"已连接到 {ssid}{suffix}"}


async def _nm_connect(ssid: str, psk: str) -> dict[str, Any]:
    iface = _wifi_iface()
    if iface is None:
        raise ControlRejected("找不到无线网卡")

    saved = await asyncio.to_thread(_nm_saved_profiles)
    existing = next((p["name"] for p in saved if p["ssid"] == ssid), None)

    if existing and not psk:
        # The secret is already on the Pi. Asking for it again would be asking
        # the user to retype something the Pi does not show them either; this is
        # the "just tap the saved network" path.
        await _nm_activate(existing, ssid)
        return await _nm_connected(ssid, "（用已保存的密码）")

    security, seen = await _nm_security(ssid)
    key_mgmt = _key_mgmt_for(security, psk)
    if key_mgmt == "eap":
        raise ControlRejected(
            "这是企业级认证（802.1X）网络，这个页面只支持个人密码；"
            "请在树莓派上执行 nmtui 配置一次，之后就能自动连"
        )
    if key_mgmt == "wep":
        raise ControlRejected(
            "这是 WEP 加密的老网络，nmcli 不支持自动配置；请在树莓派上执行 nmtui 配置一次"
        )
    if key_mgmt in ("wpa-psk", "sae") and not psk:
        raise ControlRejected(f"「{ssid}」是加密网络（{security}），需要密码")

    name = existing
    created = False
    if name is None:
        name = ssid
        argv = [
            *_prefix(),
            "nmcli",
            "connection",
            "add",
            "type",
            "wifi",
            "con-name",
            name,
            "ifname",
            iface,
            "ssid",
            ssid,
        ]
        if not seen:
            # Not in the scan list: tell NM to look for it actively instead of
            # waiting for a beacon it may never broadcast.
            argv += ["802-11-wireless.hidden", "yes"]
        await _run_ok(argv, timeout=25.0)
        created = True

    # Spell the security out instead of letting nmcli infer it, and turn
    # autoconnect on: the whole point of saving a profile is that the radio
    # finds it again after a reboot or a Wi-Fi toggle without being asked.
    settings = ["wifi-sec.key-mgmt", key_mgmt, "connection.autoconnect", "yes"]
    if psk:
        settings += ["wifi-sec.psk", psk]
    try:
        await _run_ok(
            [*_prefix(), "nmcli", "connection", "modify", name, *settings], timeout=25.0
        )
    except ControlRejected:
        if key_mgmt != "sae":
            if created:
                await _run([*_prefix(), "nmcli", "connection", "delete", name], timeout=15.0)
            raise
        # NetworkManager older than 1.32 has no SAE; WPA2 half of a transition
        # mode AP still works with a plain PSK.
        await _run_ok(
            [*_prefix(), "nmcli", "connection", "modify", name,
             "wifi-sec.key-mgmt", "wpa-psk", "wifi-sec.psk", psk,
             "connection.autoconnect", "yes"],
            timeout=25.0,
        )

    try:
        await _nm_activate(name, ssid)
    except ControlRejected:
        if created:
            # Do not leave a half-written profile behind for the next attempt to
            # trip over.
            await _run([*_prefix(), "nmcli", "connection", "delete", name], timeout=15.0)
        raise
    return await _nm_connected(ssid)


async def _wifi_connect(msg: dict[str, Any]) -> dict[str, Any]:
    ssid = _check_ssid(msg.get("ssid"))
    psk = _check_psk(msg.get("password"))
    backend = _wifi_backend()
    if backend is None:
        raise ControlRejected("这台机器既没有 nmcli 也没有 wpa_cli，无法连接")

    if backend == "nmcli":
        return await _nm_connect(ssid, psk)

    # ---- wpa_supplicant backend (Bullseye and older) ----
    iface = _wifi_iface()
    if iface is None:
        raise ControlRejected("找不到无线网卡")
    code, out, _ = await _run(["wpa_cli", "-i", iface, "add_network"], timeout=10.0)
    if code != 0 or not out.strip().isdigit():
        raise ControlRejected("wpa_cli 没有返回网络编号，无法写入配置")
    net_id = out.strip()
    steps = [
        ["set_network", net_id, "ssid", f'"{ssid}"'],
        ["set_network", net_id, "psk", f'"{psk}"']
        if psk
        else ["set_network", net_id, "key_mgmt", "NONE"],
        ["enable_network", net_id],
        ["select_network", net_id],
        ["save_config"],
    ]
    for step in steps:
        code, out, err = await _run(["wpa_cli", "-i", iface, *step], timeout=15.0)
        if code != 0 or "FAIL" in out.upper():
            raise ControlRejected(f"wpa_cli {step[0]} 失败：{(err or out)[:200]}")
    await asyncio.sleep(4.0)

    status = await asyncio.to_thread(_wifi_status)
    if status.get("ssid") != ssid:
        return {
            "ssid": ssid,
            "wifi": status,
            "message": f"{ssid} 的配置已写入，正在连接（几秒后刷新看看）",
        }
    return {"ssid": ssid, "wifi": status, "message": f"已连接到 {ssid}"}


async def _wifi_disconnect() -> dict[str, Any]:
    backend = _wifi_backend()
    iface = _wifi_iface()
    if backend is None or iface is None:
        raise ControlRejected("找不到可用的 Wi-Fi 后端或网卡")
    if backend == "nmcli":
        await _run_ok([*_prefix(), "nmcli", "dev", "disconnect", iface], timeout=20.0)
    else:
        await _run_ok(["wpa_cli", "-i", iface, "disconnect"], timeout=15.0)
    return {"message": f"{iface} 已断开", "wifi": await asyncio.to_thread(_wifi_status)}


# ---------------------------------------------------------------------- disks


def _lsblk_tree(config: dict[str, Any]) -> list[dict[str, Any]]:
    """Flat list of block devices that carry a filesystem (or are bare disks)."""
    if not shutil.which("lsblk"):
        raise ControlRejected("这台机器没有 lsblk（util-linux），无法列出磁盘")
    code, out, err = _run_sync(
        [
            "lsblk",
            "-J",
            "-b",
            "-o",
            "NAME,PATH,TYPE,SIZE,FSTYPE,LABEL,MOUNTPOINT,RM,TRAN,MODEL",
        ],
        timeout=8.0,
    )
    if code != 0:
        raise ControlRejected(f"lsblk 失败：{(err or out)[:200]}")
    try:
        tree = json.loads(out or "{}")
    except json.JSONDecodeError as exc:
        raise ControlRejected(f"lsblk 输出无法解析：{exc}") from exc

    root = mount_root(config)
    entries: list[dict[str, Any]] = []

    def walk(node: dict[str, Any]) -> None:
        name = str(node.get("name") or "")
        kind = str(node.get("type") or "")
        fstype = str(node.get("fstype") or "")
        mountpoint = node.get("mountpoint")
        path = str(node.get("path") or f"/dev/{name}")
        removable = bool(node.get("rm"))
        has_children = bool(node.get("children"))
        # A partition always counts: it is the thing you can mount. A whole disk
        # only counts when it carries a filesystem itself (superfloppy layout,
        # no partition table) - and then only if it has no children, otherwise
        # the same stick would be listed twice.
        if kind in ("part", "disk") and fstype not in _SKIP_FSTYPES and not _SKIP_NAMES.match(name):
            if fstype or kind == "part" or (removable and not has_children):
                entries.append(
                    {
                        "name": name,
                        "path": path,
                        "type": kind,
                        "size": int(node.get("size") or 0),
                        "fstype": fstype or None,
                        "label": (str(node.get("label")) if node.get("label") else None),
                        "mountpoint": mountpoint or None,
                        # Only what we mounted - or what is plainly removable -
                        # may be unmounted from the phone: the system's own
                        # /boot or / must never disappear because of a tap.
                        "can_unmount": _can_unmount(
                            mountpoint or None, removable, root
                        ),
                        "removable": removable,
                        "tran": node.get("tran") or None,
                        "model": (str(node.get("model")).strip() if node.get("model") else None),
                    }
                )
        for child in node.get("children") or []:
            walk(child)

    for device in tree.get("blockdevices") or []:
        walk(device)
    return entries


def _find_device(config: dict[str, Any], raw: Any) -> dict[str, Any]:
    device = str(raw or "")
    if not device.startswith("/dev/"):
        raise ControlRejected("device 必须是 /dev/ 下的路径（先看 disk-list）")
    for entry in _lsblk_tree(config):
        if entry["path"] == device:
            return entry
    raise ControlRejected(f"{device} 不在 lsblk 列表里；刷新一下磁盘列表")


def _mountpoint_for(config: dict[str, Any], entry: dict[str, Any]) -> str:
    """Default directory when the phone does not name one (old app, curl)."""
    base = mount_root(config)
    label = _SAFE_LABEL.sub("_", entry.get("label") or entry["name"]).strip("._-")
    # posixpath on purpose: the target is always a Linux path, and this module
    # is also imported by the simulator, which runs on Windows.
    return posixpath.join(base, label or entry["name"])


def _check_mount_name(raw: Any) -> str:
    """One path component, chosen by the user, that stays inside mount_root."""
    name = str(raw or "").strip()
    if not name:
        raise ControlRejected("缺少挂载目录名")
    if len(name) > MOUNT_NAME_MAX:
        raise ControlRejected(f"目录名太长（最多 {MOUNT_NAME_MAX} 个字符）")
    if name in (".", "..") or not _MOUNT_NAME.match(name):
        raise ControlRejected(
            "目录名只能用字母、数字、点、下划线和减号，且必须以字母或数字开头"
        )
    return name


def mount_driver(fstype: str | None) -> str | None:
    """Which ``mount -t`` to use, or ``None`` to let mount(8) decide.

    Only NTFS is pinned. ``mount`` on NTFS goes through ntfs-3g on most images
    (a userspace FUSE helper that is slow, and absent from a minimal install);
    naming ``ntfs3`` asks the kernel driver instead. Everything else - ext4,
    vfat, exfat - is resolved correctly by mount's own probe, so guessing a
    type there would only add a way to be wrong.
    """
    if (fstype or "").strip().lower() in _NTFS_FSTYPES:
        return _NTFS_DRIVER
    return None


def mount_owner(config: dict[str, Any]) -> str:
    """Login user meant to own a mounted NTFS volume (``control.mount_owner``).

    ``""`` turns the whole thing off, for an image where the mount really is
    supposed to stay root-owned.
    """
    return str(_cfg(config).get("mount_owner", _DEFAULT_MOUNT_OWNER) or "").strip()


def _resolve_owner(name: str) -> tuple[str, str] | None:
    """``(uid, gid)`` for ``name``, or ``None`` when it is not a real account.

    ntfs3 wants numbers - unlike ntfs-3g it has no ``getpwnam`` - so the name
    is looked up here and passed to mount as ``uid=`` / ``gid=``. The import
    stays inside the function: ``pwd`` is POSIX-only and this module is also
    imported by the simulator on Windows.
    """
    if not name:
        return None
    numeric = name.isdigit()
    try:
        import grp
        import pwd
    except ImportError:
        # No account database to read: a bare id is already what mount wants.
        return (name, name) if numeric else None
    try:
        entry = pwd.getpwuid(int(name)) if numeric else pwd.getpwnam(name)
    except (KeyError, ValueError):
        if numeric:
            return (name, name)
        logger.warning(
            "control.mount_owner=%r is not a local account; mounting as root", name
        )
        return None
    return (str(entry.pw_uid), str(entry.pw_gid))


def mount_options(
    msg: dict[str, Any], config: dict[str, Any], fstype: str | None
) -> tuple[list[str], str | None]:
    """``-o`` options for one mount, plus the owner name they pinned (or None).

    ``ro`` is simply the user's choice. The owner part is not cosmetic: ntfs3
    (and ntfs-3g) hand every file the uid/gid of the process that mounted, which
    here is root because the mount goes through sudo - so a stick mounted from
    the phone is root-owned and the pi user can only read it, even though the
    mount is read-write. Pinning ``uid=pi,gid=pi`` is what makes it usable.
    """
    opts = ["ro"] if msg.get("read_only") else []
    owner = None
    if (fstype or "").strip().lower() in _OWNER_FSTYPES:
        candidate = mount_owner(config)
        resolved = _resolve_owner(candidate)
        if resolved:
            opts += [f"uid={resolved[0]}", f"gid={resolved[1]}"]
            owner = candidate
    return opts, owner


async def _disk_mount(msg: dict[str, Any], config: dict[str, Any]) -> dict[str, Any]:
    entry = _find_device(config, msg.get("device"))
    root = mount_root(config)

    # The user names the directory; without a name we fall back to the label so
    # an older app (or a hand-written curl) keeps working.
    requested = msg.get("name")
    if requested is None or not str(requested).strip():
        target = _mountpoint_for(config, entry)
        name = posixpath.basename(target)
    else:
        name = _check_mount_name(requested)
        target = posixpath.join(root, name)

    if entry["mountpoint"]:
        if posixpath.normpath(entry["mountpoint"]) == posixpath.normpath(target):
            return {
                "device": entry["path"],
                "name": name,
                "mountpoint": entry["mountpoint"],
                "message": f"{entry['path']} 已经挂在 {entry['mountpoint']}",
            }
        raise ControlRejected(
            f"{entry['path']} 已经挂在 {entry['mountpoint']}，"
            f"要先卸载才能改挂到 {target}"
        )
    if not entry["fstype"]:
        raise ControlRejected(f"{entry['path']} 没有可识别的文件系统，无法挂载")

    # Mounting over an occupied directory hides whatever was there, and from the
    # phone there is no way to notice it happened. Only empty directories are
    # reused; anything else has to be a name the user picks on purpose.
    if os.path.isdir(target):
        try:
            if os.listdir(target):
                raise ControlRejected(f"{target} 已存在且不是空目录，换一个目录名")
        except OSError as exc:
            raise ControlRejected(f"无法检查 {target}：{exc}") from exc
    elif os.path.exists(target):
        raise ControlRejected(f"{target} 已存在且不是目录，换一个目录名")

    try:
        # Through sudo rather than os.makedirs: in dev mode the agent is not
        # root, and /mnt is not writable for the login user.
        await _run_ok([*_prefix(), "mkdir", "-p", target], timeout=10.0)
    except ControlRejected as exc:
        raise ControlRejected(f"无法创建挂载点 {target}：{exc.message}") from None

    driver = mount_driver(entry.get("fstype"))
    opts, owner = mount_options(msg, config, entry.get("fstype"))
    o_argv = ["-o", ",".join(opts)] if opts else []

    def argv_for(kind: str | None) -> list[str]:
        argv = [*_prefix(), "mount"]
        if kind:
            argv += ["-t", kind]
        return [*argv, *o_argv, entry["path"], target]

    code, out, err = await _run(argv_for(driver), timeout=25.0)
    detail = (err or out or "").strip()

    # A kernel without ntfs3 (anything below 5.15, or a build with
    # CONFIG_NTFS3_FS off) fails on the type name alone. Dropping -t hands the
    # volume back to ntfs-3g, which is what mount would have chosen anyway, so
    # an old Raspberry Pi OS still mounts rather than just erroring out.
    used_driver = driver
    if code != 0 and driver and "unknown filesystem type" in detail.lower():
        logger.info("%s: %s not available, retrying with mount's own probe", entry["path"], driver)
        code, out, err = await _run(argv_for(None), timeout=25.0)
        detail = (err or out or "").strip()
        used_driver = None

    if code != 0:
        low = detail.lower()
        hint = ""
        if "unknown filesystem type" in low or "wrong fs type" in low:
            hint = "（可能需要装 ntfs-3g / exfat-fuse）"
        elif "dirty" in low or "unclean" in low:
            hint = "（卷被标记为脏，多半是 Windows 没正常关机；可勾「只读挂载」先读出来）"
        raise ControlRejected(f"挂载失败：{detail[:300]}{hint}")

    return {
        "device": entry["path"],
        "name": name,
        "mountpoint": target,
        "fstype": entry["fstype"],
        "driver": used_driver,
        "owner": owner,
        "read_only": bool(msg.get("read_only")),
        "message": f"{entry['path']} 已挂载到 {target}"
        + (f"（{used_driver}）" if used_driver else "")
        + (f"，属主 {owner}" if owner else "")
        + ("，只读" if msg.get("read_only") else ""),
    }


async def _disk_unmount(msg: dict[str, Any], config: dict[str, Any]) -> dict[str, Any]:
    entry = _find_device(config, msg.get("device"))
    mountpoint = entry["mountpoint"]
    if not mountpoint:
        raise ControlRejected(f"{entry['path']} 当前没有挂载")
    root = mount_root(config)
    if not _can_unmount(mountpoint, bool(entry.get("removable")), root):
        raise ControlRejected(
            f"{mountpoint} 是系统挂载点，为安全起见不允许从手机卸载"
            f"（只有 {root} 下的挂载、以及可移动设备的挂载可以卸）"
        )
    code, out, err = await _run([*_prefix(), "umount", mountpoint], timeout=25.0)
    if code != 0:
        detail = (err or out or "").strip()
        if "busy" in detail or "target is busy" in detail:
            raise ControlRejected(
                f"{mountpoint} 正在被使用，先在树莓派上关掉占用它的程序（lsof +D {mountpoint}）"
            )
        raise ControlRejected(f"卸载失败：{detail[:300]}")

    return {"device": entry["path"], "mountpoint": mountpoint, "message": f"{mountpoint} 已卸载"}


# --------------------------------------------------------------------- custom


async def _custom(msg: dict[str, Any], config: dict[str, Any]) -> dict[str, Any]:
    items = custom_commands(config)
    try:
        index = int(msg.get("index", -1))
    except (TypeError, ValueError):
        raise ControlRejected("缺少 index") from None
    if not 0 <= index < len(items):
        raise ControlRejected(f"index 超出范围（当前有 {len(items)} 条自定义命令）")

    item = items[index]
    timeout = float(_cfg(config).get("custom_timeout_s", 20))
    # Reuse the shell runner with allow_any: the line was written by the admin
    # in a root-owned config file, not typed on the phone. The deny list and the
    # metacharacter check still apply, so a fat-fingered "rm -rf" in there is
    # still refused. The defaults are merged in explicitly because a caller with
    # a hand-built config (the simulator, a test) may not carry a `shell`
    # section at all - and "no deny list" would mean "nothing is denied".
    shell_cfg = {**DEFAULT_CONFIG["shell"], **(config.get("shell") or {})}
    shell_cfg["allow_any"] = True
    shell_cfg["allow_meta"] = custom_allow_meta(config)
    result = await shell.exec_line(
        {"line": item["line"], "timeout": timeout},
        {**config, "shell": shell_cfg},
    )
    ok = result["code"] == 0 and not result["timed_out"]
    logger.info("custom[%d] %r -> code %s", index, item["line"][:60], result["code"])
    return {
        "index": index,
        "name": item["name"],
        **result,
        "message": f"{item['name']}：{'完成' if ok else ('超时' if result['timed_out'] else '退出码 %s' % result['code'])}",
    }


async def _custom_save(msg: dict[str, Any], config: dict[str, Any]) -> dict[str, Any]:
    """Replace the whole list with what the phone just edited.

    Whole-list replacement, not add/remove calls: the app already holds the full
    list it drew on screen, so one write either lands completely or not at all -
    there is no half-applied edit to reconcile afterwards.
    """
    items = _check_custom(msg.get("items"))

    # Pre-flight with the exact rules the runner will apply. Saving a line that
    # can only ever fail is worse than refusing it now, while the user still has
    # the text in front of them.
    shell_cfg = {**DEFAULT_CONFIG["shell"], **(config.get("shell") or {})}
    shell_cfg["allow_any"] = True
    shell_cfg["allow_meta"] = custom_allow_meta(config)
    for position, item in enumerate(items, start=1):
        try:
            shell._check(item["line"], shell_cfg)
        except shell.ShellRejected as exc:
            label = item["name"] or item["line"]
            raise ControlRejected(f"第 {position} 条「{label}」会被拒绝：{exc.message}") from None

    try:
        path = await asyncio.to_thread(_write_custom_file, items)
    except OSError as exc:
        raise ControlRejected(f"无法写入 {custom_path()}：{exc}") from exc

    logger.info("saved %d custom command(s) to %s", len(items), path)
    return {
        "items": [
            {"index": index, "name": item["name"], "line": item["line"]}
            for index, item in enumerate(items)
        ],
        "path": path,
        "message": f"已保存 {len(items)} 条自定义命令" if items else "自定义命令已清空",
    }


# --------------------------------------------------------------------- status


def status(config: dict[str, Any]) -> dict[str, Any]:
    """One round trip for the whole control page."""
    enabled = _cfg(config).get("enabled", True)
    permit = allowed(config)
    wifi_ok = _wifi_backend() is not None and _wifi_iface() is not None

    try:
        disks = _lsblk_tree(config)
        disk_error = None
    except ControlRejected as exc:
        disks = []
        disk_error = exc.message

    try:
        customs = [
            {"index": item["index"], "name": item["name"], "line": item["line"]}
            for item in custom_commands(config)
        ]
        custom_error = None
    except ControlRejected as exc:
        customs = []
        custom_error = exc.message

    # The app draws exactly what is listed here, so a missing tool or an empty
    # custom list hides the section instead of offering a button that can only
    # fail.
    usable: list[str] = []
    for action in ACTIONS:
        if action not in permit:
            continue
        if action.startswith("wifi") and not wifi_ok:
            continue
        if action.startswith("disk") and disk_error:
            continue
        if action == "custom" and not customs:
            continue
        usable.append(action)

    return {
        "enabled": bool(enabled),
        "actions": usable,
        "wifi": _wifi_status(),
        "disks": disks,
        "custom": customs,
        "custom_max": MAX_CUSTOM,
        "custom_path": custom_path(),
        "mount_root": mount_root(config),
        "root": _prefix() == [],
        "errors": {"disk": disk_error, "custom": custom_error},
        "ts": round(time.time(), 3),
    }


# ------------------------------------------------------------------- dispatch


async def run(msg: dict[str, Any], config: dict[str, Any]) -> dict[str, Any]:
    """Entry point used by the agent's dispatcher."""
    action = str(msg.get("action") or "").strip()
    if not action:
        raise ControlRejected("缺少 action；先发 {\"cmd\":\"control\",\"action\":\"status\"}")
    if action != "status":
        _guard(config, action)

    if action == "status":
        return await asyncio.to_thread(status, config)
    if action in ("reboot", "shutdown"):
        return await _power(action, msg)
    if action in ("restart-agent", "restart-bluetooth"):
        return await _restart_unit(action, msg)
    if action == "wifi-enable":
        return await _wifi_on_off(True)
    if action == "wifi-disable":
        return await _wifi_on_off(False)
    if action == "wifi-scan":
        return await _wifi_scan()
    if action == "wifi-connect":
        return await _wifi_connect(msg)
    if action == "wifi-disconnect":
        return await _wifi_disconnect()
    if action == "disk-list":
        return await asyncio.to_thread(
            lambda: {"disks": _lsblk_tree(config), "mount_root": mount_root(config)}
        )
    if action == "disk-mount":
        return await _disk_mount(msg, config)
    if action == "disk-unmount":
        return await _disk_unmount(msg, config)
    if action == "custom":
        return await _custom(msg, config)
    if action == "custom-save":
        return await _custom_save(msg, config)

    raise ControlRejected(f"未知动作 '{action}'")
