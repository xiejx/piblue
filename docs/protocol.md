# PiBlue 协议

手机（GATT client）与树莓派（GATT server）之间只有一种数据形态：**一行一个 JSON 对象，以 `\n` 结尾**。

为什么不用二进制协议？因为两端都要做分片重组，文本行协议把"一帧到哪结束"这件事变成扫一个字节，调试时还能直接肉眼读。

## GATT 结构

| 项 | UUID | 属性 |
| --- | --- | --- |
| Service | `6e400001-b5a3-f393-e0a9-e50e24dcca9e` | primary，广播时带设备名 |
| RX（手机 → 树莓派） | `6e400002-b5a3-f393-e0a9-e50e24dcca9e` | Write, WriteWithoutResponse |
| TX（树莓派 → 手机） | `6e400003-b5a3-f393-e0a9-e50e24dcca9e` | Notify, Read |
| CCCD | `00002902-0000-1000-8000-00805f9b34fb` | 订阅 TX |

这是 Nordic UART Service 的布局，选择它是因为手机端 GATT 调试工具（nRF Connect）默认就认识这套 UUID，手工联调很方便。

## 为什么是 GATT 而不是 SPP

经典蓝牙串口（SPP）依赖 BlueZ 的废弃兼容模式（`bluetoothd -C`）+ `rfcomm` 绑定，近几版 Raspberry Pi OS 上越来越容易翻车；GATT 是 BlueZ 官方支持的路径，手机端也不用走"配对"流程，连上就能用。代价是两端都要自己按 MTU 分片：手机侧在 `BleClient.kt`，树莓派侧在 `BleTransport.send` —— **BlueZ 不会替你切 notify，超长的一条直接丢**，这一点踩过坑，见 [troubleshooting.md](troubleshooting.md#链路上通了但命令超时)。

## 分片与重组

* **写入**：一次 GATT write 最多 `MTU - 3` 字节。手机端把整行按此切块，逐块发送，**必须等上一块的 write 回调再发下一块**（Android 的 GATT 同时只允许一个未完成操作）。树莓派端按 `\n` 重组，收到换行才解析。
* **通知**：**树莓派端自己切**，一次 notify 最多 `MTU - 3` 字节。别指望 BlueZ 帮你切：ATT 的 notify PDU 超过 MTU 时 bluetoothd 直接丢弃（D-Bus 那层还是成功返回），于是超长响应看起来和"树莓派没响应"一模一样 —— `info` 的 566 字节就这样丢过。手机端按**字节**累积、遇到 `0x0A` 才解码 UTF-8 —— 不能对每个分片单独解码，否则中文（3 字节）会被切在中间变成乱码。
* **MTU 从哪来**：BlueZ 不把协商后的 MTU 暴露到 D-Bus，bless 也不暴露，所以只有手机知道。手机连上后第一帧发 `hello` 并带上 `mtu`（`{"id":1,"cmd":"hello","mtu":517}`），树莓派据此把分片长度设为 `mtu-3`（上限 512）。没收到 `hello` 之前按 20 字节保守发送。
* 单行上限 16 KiB，超了整行丢弃并计一次 overflow。

## 报文

请求：

```json
{"id": 7, "cmd": "info"}
```

* `id`：请求标识，由手机生成，响应原样带回。**保留字段**，不能拿它当业务参数（所以读传感器用 `sensor` 而不是 `id`）。
* `cmd`：命令名。

成功响应：

```json
{"id": 7, "ok": true, "data": { }}
```

失败响应：

```json
{"id": 7, "ok": false, "error": "'rm' is on the deny list"}
```

失败响应可能带 `code` 字段，用于让手机区分处理方式。目前只有一个值：

```json
{"id": 7, "ok": false, "code": "unauthorized", "error": "wrong pairing code, 4 attempt(s) left"}
```

`code: "unauthorized"` 表示**需要（重新）认证**，而不是这条命令本身有问题 —— 手机端应重走一遍 `hello` → `auth` 再重发，而不是把错误抛给用户。会话是随连接结束的，所以断线重连后的第一条命令必然撞上这个。

主动推送（无 `id`，用 `event` 区分）：

```json
{"event": "summary", "data": {"cpu_temp_c": 48.2, "load1": 0.14, "mem_used_pct": 23.1, "uptime_s": 91234, "time": {"epoch": 1760000000.12, "local": "2026-11-15 09:57:03", "tz": "CST", "utc_offset": "+0800", "synced": true}, "ts": 1760000000.12}}
```

## 认证

GATT 服务是公开的：BLE 广播挡不住任何人连上来（能挡住的唯一办法是停止广播，那样自己的手机也扫不到）。所以关卡放在命令层：**认证之前，只有 `hello` / `auth` / `ping` 有回应，其余一律 `code: "unauthorized"`**。

配对码是 16 位 base32（约 80 bit，形如 `ABCD-EFGH-JKLM-NPQR`），存在树莓派的 `/etc/piblue/token`（0600），**从不经过 BLE**。

握手：

```jsonc
// 1. 手机
{"id": 1, "cmd": "hello", "mtu": 517}

// 2. 树莓派（challenge 每次握手都不同）
{"id": 1, "ok": true, "data": {
  "name": "PiBlue", "version": "1.3.0", "hostname": "raspberrypi", "commands": ["..."],
  "auth": {"required": true, "algo": "hmac-sha256",
           "challenge": "9f2c…32 hex…", "session_s": 300}
}}

// 3. 手机：key = SHA-256("piblue-auth-v1:" + 配对码)，answer = HMAC-SHA256(key, challenge)
{"id": 2, "cmd": "auth", "mac": "5c1e…64 hex…"}

// 4. 树莓派
{"id": 2, "ok": true, "data": {"authorized": true, "session_s": 300}}
```

规则：

* `challenge` 是**一次性**的。验证失败就作废，重试必须重新 `hello`（每次猜测都要一个来回）。
* 连错 `auth.max_attempts`（默认 5）次锁定 `auth.lockout_s`（默认 60 秒），锁定期间所有回应都是 `too many wrong codes`。
* `hello` 总是开新会话：无论之前认证过没有，发完 `hello` 就得重新 `auth`。
* 会话在三种情况下失效：收到 `hello`、BlueZ 报告设备断开、`auth.session_idle_s`（默认 300 秒）内没有任何报文。断开信号是主路径，空闲超时是 BlueZ 信号收不到时的兜底。
* `auth.enabled: false` 时 `hello` 回 `{"required": false}`，所有命令直接放行 —— 只建议在自己的实验环境里这么干。

服务端实现见 `pi-agent/agent/auth.py`，配对码的生成与派生见 `pi-agent/agent/token.py`。两端派生规则必须一字不差，手机端在 `android/.../data/Pairing.kt`。

## 命令表

| cmd | 参数 | 返回 `data` |
| --- | --- | --- |
| `hello` | `mtu`（可选，手机侧的协商结果） | `name`, `version`, `hostname`, `commands[]`, `auth`（见上） |
| `auth` | `mac`（HMAC-SHA256 十六进制） | `authorized`, `session_s` |
| `ping` | — | `pong`, `t` |
| `info` | — | 完整系统快照，见下 |
| `summary` | — | 精简快照 `cpu_temp_c` / `load1` / `mem_used_pct` / `uptime_s` / `time` |
| `sensors` | `read=true` 则同时读取全部 | `sensors[]` |
| `sensor` | `sensor=<id>` | 单个读数 |
| `exec` | `line`, 可选 `timeout`（秒） | `stdout`, `stderr`, `code`, `timed_out`, `truncated`, `duration_ms` |
| `control` | `action`，其余参数按动作而定 | 每个动作都带 `message`（可直接展示的文案），见下 |
| `sub` | `topic=summary`, `every`（秒） | `topic`, `every` |
| `unsub` | `topic` | `topic`, `stopped` |
| `help` | — | 命令说明 |

### `info` 返回结构

```json
{
  "hostname": "raspberrypi",
  "model": "Raspberry Pi 4 Model B Rev 1.4",
  "kernel": "6.6.31+rpt-rpi-v8",
  "os": "Linux-6.6.31+rpt-rpi-v8-aarch64-with-glibc2.36",
  "uptime_s": 91234,
  "cpu_temp_c": 48.2,
  "cpu_count": 4,
  "load": [0.14, 0.09, 0.05],
  "mem": {"total_mb": 7800.0, "used_mb": 1802.3, "available_mb": 5731.2, "used_pct": 23.1},
  "disk": {"path": "/", "total_gb": 29.7, "used_gb": 8.1, "free_gb": 20.2, "used_pct": 28.6},
  "net": {"iface": "wlan0", "ipv4": "192.168.1.42"},
  "wifi": {"ssid": "home", "signal": 58},
  "throttled": {"raw": "0x0", "flags": [], "ok": true},
  "time": {"epoch": 1760000000.12, "local": "2026-11-15 09:57:03",
           "tz": "CST", "utc_offset": "+0800", "zone": "Asia/Shanghai", "synced": true},
  "ts": 1760000000.12
}
```

字段都可能为 `null`（比如没插 Wi-Fi、没有 `vcgencmd`），手机端要按可空处理。

`wifi.signal` 是 `/proc/net/wireless` 的 **link quality（0..70）**，不是 dBm：状态页把它印成 `xqx688 (61/70)`，分母 70 是这一列的满刻度。驱动不填这一列时为 `null`，语义上就是"不知道"，App 于是只显示 SSID，不会补一个 0 出来。要 dBm 看 `control` 里同名的 `wifi.signal`（nmcli 的负值，如 `-49`）或 `iw dev wlan0 link`——两个字段同名但量纲不同，不能互相换算。

`time` 同时给**格式化字符串**和 `epoch`，不是冗余：`local` 是树莓派自己时区下的读数，手机要是拿 `epoch` 按本地时区重排，就正好把"树莓派差了几个时区"这件事抹掉了。`synced` 来自 `timedatectl show -p NTPSynchronized`（没有这个工具时为 `null`）；RTC 和网络都没有的树莓派会停在 1970 年，那是 TLS 和 apt 各种诡异报错的源头，状态页会把它标出来。`summary` 推送里也带同一个 `time` 对象，所以订阅开着时时间会跟着刷新。

### 传感器读数

单值：

```json
{"id": "cpu", "name": "SoC 温度", "type": "temperature", "unit": "°C", "value": 48.2, "ts": 1760000000.1}
```

多值（DHT、自定义脚本）：

```json
{"id": "dht:D4", "name": "DHT22 @ D4", "type": "climate", "unit": "", "values": {"temp_c": 21.5, "humidity": 44.0}, "ts": 1760000000.1}
```

读取失败时带 `error` 字段而不是 `value`。

## 控制（`control`）

手机端不发送命令行，只发送**动作名**加少量数据。动作表在树莓派上是固定的，不在表里的动作根本不存在：

| action | 参数 | 作用 |
| --- | --- | --- |
| `status` | — | 控制页的一次性快照：可用动作、Wi-Fi 状态、磁盘列表、自定义命令 |
| `reboot` | `confirm=true`，可选 `delay_s` | `systemctl --no-block reboot` |
| `shutdown` | `confirm=true`，可选 `delay_s` | `systemctl --no-block poweroff` |
| `restart-agent` | 可选 `delay_s` | 重启 `piblue.service`（用 `systemd-run --on-active` 排在自身 cgroup 之外，否则还没回包就被自己杀了） |
| `restart-bluetooth` | 可选 `delay_s` | 重启 `bluetooth.service` + `piblue.service`（只重启前者会留下失效的 D-Bus 注册，反而更扫不到） |
| `wifi-enable` / `wifi-disable` | — | `nmcli radio wifi on/off`，另用 `rfkill` 兜底。打开时还会执行 `nmcli device connect <iface>`：光把 radio 打开不会恢复连接，NetworkManager 会给"手动断开"过的设备打标记，autoconnect 一直被压着 |
| `wifi-scan` | — | 强制重扫，返回 `networks[]`（`ssid` / `signal` / `security` / `saved`） |
| `wifi-connect` | `ssid`，可选 `password` | 树莓派已有该 SSID 的 profile 且没给密码 → 直接激活，用存档里的密码；否则显式写一份带 `key-mgmt` 的 profile 再激活（不是 `nmcli dev wifi connect`）。没有 nmcli 时走 `wpa_cli` |
| `wifi-disconnect` | — | 断开当前网络 |
| `disk-list` | — | `lsblk -J` 的可挂载分区 |
| `disk-mount` | `device`（必须来自 `disk-list`），可选 `name`、`read_only` | 挂到 `control.mount_root/<name>`；`name` 会被校验成**单个路径段**（字母数字开头，`[A-Za-z0-9._-]{0,31}`），目录不存在则 `mkdir -p`，已存在且非空则拒绝。不带 `name` 时退回用 label 当目录名。**NTFS 显式用 `mount -t ntfs3`**（`ntfs`/`ntfs3`/`fuseblk` 都算）并加 `-o uid=<uid>,gid=<gid>`（按 `control.mount_owner`，默认 `pi`，查 passwd/group 换成数字；查不到就不加），内核没有 ntfs3 时自动退回不带 `-t` 的探测（即 ntfs-3g）；其他文件系统一律不指定 `-t`、也不改属主。回包里多一个 `owner` 字段（实际钉上的账号名，没钉则为 `null`） |
| `disk-unmount` | `device` | 只允许卸载 `mount_root` 下的挂载，以及**可移动设备**的挂载（桌面自动挂到 `/media/pi/…` 的 U 盘也能安全弹出）；`/`、`/boot`、`/usr` 等关键挂载点一律拒绝 |
| `custom` | `index`（0-4） | 触发第 index 条命令，正文在树莓派上 |
| `custom-save` | `items: [{"name","line"}]`（≤5） | **整表替换**，写入 `custom.json`（与 `config.json` 同目录，原子写、0644）。保存前用执行时的同一套规则预检，写不进去的命令当场拒绝 |

`status` 返回：

```json
{
  "enabled": true,
  "actions": ["reboot", "shutdown", "restart-agent", "wifi-scan", "disk-mount", "custom", "custom-save"],
  "wifi": {"available": true, "backend": "nmcli", "iface": "wlan0",
           "enabled": true, "ssid": "home", "signal": -48, "state": "connected",
           "saved": ["home", "office"]},
  "disks": [{"path": "/dev/sda1", "name": "sda1", "size": 31000000000,
             "fstype": "exfat", "label": "USB", "mountpoint": null,
             "can_unmount": false, "removable": true, "tran": "usb", "model": "USB DISK"}],
  "custom": [{"index": 0, "name": "重启 nginx", "line": "systemctl restart nginx"}],
  "custom_max": 5,
  "custom_path": "/etc/piblue/custom.json",
  "mount_root": "/mnt",
  "root": true,
  "errors": {"disk": null, "custom": null}
}
```

`custom` 里的 `line` 会一起下发，因为手机现在也要能编辑它 —— 命令正文不再只存在于树莓派。`custom.json` 存在时以它为准，`config.json` 的 `control.custom` 只是首次保存前的种子。`actions` 里出现 `custom-save` 才表示这台树莓派允许手机改这份列表（`control.allow_custom_edit`，默认跟 `custom` 一起开）。

`wifi.saved` 只列 SSID，永远不含密码——它回答的是"树莓派记得哪些网络"，不是"密码是什么"。App 用它在列表里标「已保存」，并在没有扫描结果时把不在范围内的已保存网络也列出来（照着这个点就能连回去）。

`actions` 已经是**过滤后**的结果：配置里没开（`control.allow`）、或这台机器没装对应工具（没有 `nmcli`/`wpa_cli`、没有 `lsblk`）的动作不会出现。App 只画这个列表里的按钮，所以不会出现“点了必然失败”的按钮。

为什么不让手机发命令行：`exec` 的路径上，一条 `sh -c '…'` 就能绕过白名单；这里文件名、挂载点、SSID 全部由树莓派侧校验后才进 `argv`，且**永远不经过 shell**（`create_subprocess_exec`）。手机能给的最坏输入是一个不存在的 SSID。

唯一的例外是 `custom-save`：它确实让手机写入要执行的命令。这是刻意的 —— 控制页的价值就是把常用操作做成按钮，而按钮背后必然得有一行命令。边界是：① 只有通过配对码鉴权的手机能调（所有 `control` 动作都在鉴权之后）；② 上限 5 条、单行、最长 500 字符，保存前用执行时的同一套规则预检；③ `shell.deny` 仍然生效，`rm`/`dd`/`mkfs` 保存不进去；④ 想彻底关掉就设 `control.allow_custom_edit = false`，那样手机只能按序号执行 `config.json` 里手写的条目。

## 手工联调

开了配对码之后，nRF Connect 也得先过认证。两条路：

**A. 临时关掉认证**（只在实验环境，改完记得改回来）：

```bash
sudo nano /etc/piblue/config.json   # "auth": {"enabled": false}
sudo systemctl restart piblue
```

**B. 自己算 mac**（树莓派上跑，不需要手机）：

```bash
sudo /opt/piblue/venv/bin/python - <<'PY'
import hashlib, hmac, json
from agent.token import derived_key, read_token

code = read_token()                    # 读 /etc/piblue/token
challenge = input("challenge (hex): ") # 从 nRF Connect 收到的 hello 里抄
print(hmac.new(derived_key(code), bytes.fromhex(challenge), hashlib.sha256).hexdigest())
PY
```

再往 RX 发 `{"cmd":"auth","mac":"<上面那串>"}` 即可。

关掉认证时：

1. 扫描，找到 `PiBlue`，Connect；
2. 展开 `6e400001-…` 服务 → 点 TX（`…0003`）右边的三个箭头 → 订阅；
3. 点 RX（`…0002`）右边的上传箭头，格式选 `UTF-8`，输入 `{"id":1,"cmd":"info"}`，Send；
4. 上面会跳出 `{"id":1,"ok":true,"data":{…}}`。

这一步能跑通，就说明树莓派侧没问题，后面只剩 App 的事。

## 兼容性备注

* `exec` 的 `line` 在默认配置下不允许 `; | & > < $ \``，也不允许白名单外的可执行文件。想跑管道就把 `/etc/piblue/config.json` 的 `shell.allow_meta` 改成 `true`。
* `sub` 目前只支持 `summary`。加新 topic 只需在 `agent.py` 的 `_sub_loop` 里分支。
* MTU 由手机请求（App 要 517）。协商失败会退回 23，功能不变，只是分片更多 —— 前提是 `hello` 如实报了 MTU；报错的话按 `mtu-3` 切出来的分片照样会被丢，而按 20 字节切永远安全。
