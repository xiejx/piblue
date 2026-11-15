# 树莓派端：安装与配置

README 的「快速开始」只给了最短路径。这份文件是它的完整版：两种运行模式的全部参数、`config.json` 每个字段的含义、配对码怎么管、多台树莓派怎么区分，以及不装 App 时如何单独验证链路。

---

## 前提

* 树莓派 3B+ / 4 / 5（板载蓝牙，3B 也可以），Raspberry Pi OS Bookworm 或 Bullseye
* 能 SSH 进去，能联网装包
* **不需要** 手机和树莓派在同一个 Wi-Fi 下，纯蓝牙通信

## 先把代码传上去

在电脑上（Windows 用 Git Bash / PowerShell 都行）：

```bash
cd E:/workbuddy/rasberrypi
scp -r pi-agent pi@raspberrypi.local:~/
ssh pi@raspberrypi.local
```

`raspberrypi.local` 解析不到就换成 IP。后面的命令都在 `~/pi-agent` 里执行。

> 直接 `scp` 拷过去时，脚本可能带上 Windows 行尾（CRLF），在树莓派上会报 `set: pipefail: invalid option name`。修法和预防见 [troubleshooting.md](troubleshooting.md)。仓库已用 `.gitattributes` 固定 LF，只有"拷文件"这条路会复发。

---

## 调试模式（不装服务，推荐先这样跑）

不想往系统里写任何东西时用这个。`venv` 和配置都留在 `pi-agent/` 目录内，前台运行，Ctrl-C 停：

```bash
cd ~/pi-agent
sudo ./scripts/dev.sh          # 首次会自动建 ./venv 装依赖，然后直接跑
```

产物只有两个，都在目录里：

```
pi-agent/venv/            依赖，删掉即可
pi-agent/config.dev.json  配置（通过 PIBLUE_CONFIG 传给进程，不碰 /etc）
```

* 看日志：就是终端输出（`agent.main` 的 logging 打到 stdout）
* 改配置：编辑 `config.dev.json`，Ctrl-C 后重跑
* 重装依赖：`sudo ./scripts/dev.sh --rebuild`
* 只准备环境不运行：`sudo ./scripts/dev.sh --setup`

> 仍然需要 `sudo`：BlueZ 的 GATT 注册只对特权客户端开放，这一点在调试期躲不掉。

---

## 正式安装（跑通之后再考虑）

```bash
cd ~/pi-agent
sudo ./scripts/install.sh
```

这套会把代码放到 `/opt/piblue`、配置放 `/etc/piblue`、安装并 `enable` systemd 单元 `piblue.service`（开机自启）。

**从服务退回到调试模式**：

```bash
sudo systemctl stop piblue
sudo ./scripts/uninstall.sh          # 停用 + 删 unit，保留 /opt 和 /etc
sudo ./scripts/uninstall.sh --purge  # 连 /opt/piblue 和 /etc/piblue 一起删（会二次确认）
```

注意：`piblue.service` 在跑的时候，`dev.sh` 会直接拒绝启动——同一个适配器上只能有一个 GATT application。

### `install.sh` 做的事

1. `apt install python3-venv python3-dbus python3-gi bluez`
2. 建 `/opt/piblue/venv`（`--system-site-packages`，留给旧版 bless 的 dbus 绑定）
3. `pip install 'bless>=0.3.0'`（0.3 起走 dbus-next，纯 Python；0.2.x 的 txdbus 在 Python 3.11+ 会崩）
4. 首次安装时把配置写到 `/etc/piblue/config.json`（已存在则保留）
5. 生成配对码到 `/etc/piblue/token`（已存在则保留，不会悄悄换掉）
6. 装 `piblue.service` 并启动
7. 装 `/usr/local/bin/piblue-token`（查看/更换配对码的短命令）

### 配对码

装完最后会打印**配对码**，形如 `ABCD-EFGH-JKLM-NPQR`，手机第一次连接时填一次。日志只是顺带打印，**码本身存在文件里**，任何时候都能查：

```bash
sudo piblue-token                                    # 看当前生效的码（重跑一次 install.sh 会装上这条命令）
sudo cat /etc/piblue/token                           # 同一个东西，文件内容就是带横线的码
sudo piblue-token set MYPI-HOME-PASS-KLMN            # 自己指定一个
sudo piblue-token new                                # 随机换一个（所有手机都要重填）
```

没有 `piblue-token` 这条命令（装的是加认证之前的版本）就用全称：

```bash
sudo /opt/piblue/venv/bin/python -m agent.token show
```

`set` 的规则：base32 字符（A–Z 加 2–7，**没有 0/1/8/9**），去掉横线空格后至少 12 位（60 bit）。少于 16 位会提示一句，但仍然可用；不想背就用 `new` 随机生成。

用 `dev.sh` 调试时是**另一套码**，不碰 `/etc`，文件在代码目录里，启动时也会打在 `[dev] pairing ...` 那行：

```bash
sudo ./scripts/dev.sh token                          # 看当前 dev 码（= sudo cat ~/pi-agent/.dev-token）
sudo ./scripts/dev.sh token new                      # 换一个
sudo ./scripts/dev.sh token set MYPI-DEVT-OKEN-KLMN   # 自己指定
sudo rm ~/pi-agent/.dev-token                        # 删掉，下次启动自动换一个
```

所以同一个 App 在两种模式下要填不同的码：对外面跑着的服务填 `/etc/piblue/token`，对 `dev.sh` 填 `.dev-token`。分不清时看 App 终端里那行 `配对通过 · agent x.y.z` 有没有出现。

### 装完自检

```bash
sudo ./scripts/doctor.sh
```

全绿的话，树莓派已经在广播名为 `PiBlue` 的 BLE 设备了。

> 为什么服务以 root 跑：BlueZ 的 `LEAdvertisingManager1` 只对特权用户开放，普通用户加 capability 也能做，但 systemd 单元里 root 最省事。这也是为什么 `exec` 的权限控制必须做得保守（见 [security.md](security.md)）。

---

## 配置

`/etc/piblue/config.json`，改完 `sudo systemctl restart piblue`（调试模式则改 `config.dev.json` 后重跑 `dev.sh`）：

```json
{
  "ble": { "name": "PiBlue", "adapter": "" },
  "agent": { "log_level": "INFO", "min_interval_s": 1.0, "max_interval_s": 60.0 },
  "auth": {
    "enabled": true,
    "token": "",
    "token_file": "/etc/piblue/token",
    "max_attempts": 5,
    "lockout_s": 60,
    "session_idle_s": 300
  },
  "shell": {
    "enabled": true,
    "allow_any": false,
    "allow_meta": false,
    "timeout_s": 10,
    "max_output_bytes": 8192
  },
  "control": {
    "enabled": true,
    "allow": [
      "reboot", "shutdown", "restart-agent", "restart-bluetooth",
      "wifi-enable", "wifi-disable", "wifi-scan", "wifi-connect", "wifi-disconnect",
      "disk-list", "disk-mount", "disk-unmount", "custom", "custom-save"
    ],
    "mount_root": "/mnt",
    "custom": [],
    "custom_timeout_s": 20,
    "allow_custom_edit": true,
    "custom_allow_meta": true
  },
  "sensors": { "onewire": true, "dht": [], "custom": [] },
  "disk": { "path": "/" }
}
```

* `ble.adapter` 留空 = 让 bless 自己选第一个适配器（板载蓝牙就是这个）。只有插了 USB 蓝牙棒、想指定它时才填 `hci1`；填错会报 `Could not locate bluetooth adapter`。
* `ble.name` 改成别的名字，App 里就按新名字找（`BleClient.nameHint` 同步改，或直接靠服务 UUID 匹配，也能找到）。
* `auth.enabled = false` 等于谁连上都能跑命令，只在完全私人的实验环境里关；`auth.token` 留空是为了让密钥待在 `/etc/piblue/token`（0600），而不是待在会被人复制走的配置文件里。
* `shell.allow_any = true` 等于把 root shell 交给手机，只在完全私人的环境里开。
* `shell.allow_meta = false` 会拦 `; | & > < $ \``，所以 `ps aux | grep x` 默认跑不了。想放开就改 `true`。
* `control.allow` 是**控制页的按钮清单**：删掉一行，App 里那个按钮就消失。比如不想让任何人远程关机，把 `"shutdown"` 去掉即可 —— 注意这是唯一的地方，没有第二个隐藏开关。
* `sensors` / `disk` 的用法见下面的传感器扩展。控制页相关字段（`mount_root`、`custom*`）见 [control.md](control.md)。

### 传感器扩展（不用改代码）

```json
"sensors": {
  "onewire": true,
  "dht": [{ "pin": "D4", "model": "DHT22" }],
  "custom": [
    { "id": "custom:cpu_freq", "name": "CPU 频率", "type": "value", "unit": "MHz",
      "cmd": "vcgencmd measure_clock arm" }
  ]
}
```

`custom.cmd` 打印一个数字、或一个 JSON 对象（如 `{"temp_c": 21.5, "humidity": 44}`）即可。DS18B20 插上 1-Wire 就自动出现，只要在 `/boot/firmware/config.txt` 里开了 `dtoverlay=w1-gpio`。

---

## 有多台树莓派？

出厂名字都是 `PiBlue`，所以同一次扫描里会出现好几条同名记录。三层区分手段，从最省事到最可靠：

**1. 改广播名**（推荐，扫一眼就认出来）

```bash
sudo nano /etc/piblue/config.json    # "ble": { "name": "PiBlue-kitchen", ... }
sudo systemctl restart piblue
```

名字受广播包大小限制：传统广播包只有 31 字节，flags 占 3、128-bit 服务 UUID 占 18，留给名字的空间很紧。**总长控制在 14 个字符以内**（`PiBlue-kitchen` 正好 14）比较稳，再长可能注册失败或名字被截断——以实机 `journalctl -u piblue -n 20` 的输出为准。App 找设备优先看服务 UUID，所以改名字不会导致找不到。

**2. 看 MAC**（扫描列表第二行）

每一行现在有两行文本：

```
PiBlue-kitchen
C4:7F:51:AB:CD:EF    -45 dBm
```

同名时凭 MAC 就能选中不同的机器。RSSI 只是信号强度，**别用它判断"哪台"**——几台放在一起时差别很小，而且人一动就变。

**3. 连上后看主机名**

`hello` 会返回树莓派的 hostname，连上后标题栏显示 `已连接 PiBlue-kitchen（pi-kitchen）`，终端里也有 `配对通过 · agent 1.1.0 · pi-kitchen`。hostname 没改过的话都叫 `raspberrypi`，那就顺手改掉：

```bash
sudo hostnamectl set-hostname pi-kitchen
```

---

## 不装 App 也能验证链路

这一步别跳过：它把"蓝牙/树莓派的问题"和"App 的问题"分开。

**默认开了配对码，nRF Connect 也要先过认证**，最省事的办法是临时关掉：

```bash
sudo nano /etc/piblue/config.json   # "auth": {"enabled": false}
sudo systemctl restart piblue
```

手机装 **nRF Connect**，扫描 → 连接 `PiBlue` → 展开 `6e400001-…` 服务 → 点 TX（`…0003`）右侧三个箭头订阅通知 → 点 RX（`…0002`）上传箭头，输入：

```json
{"id":1,"cmd":"info"}
```

上面立刻会跳出 `{"id":1,"ok":true,"data":{…}}`。通了就说明树莓派侧 OK，**记得把 `auth.enabled` 改回 `true`**。

不想关认证也可以，用 [protocol.md](protocol.md) 里那段 Python 从 `hello` 的 challenge 算出 `mac` 再发 `{"cmd":"auth","mac":"…"}`。

完全不开蓝牙也能验证协议逻辑（模拟器走 stdin/stdout，同一套 handler）：

```bash
cd /opt/piblue
echo '{"id":1,"cmd":"info"}' | ./venv/bin/python tools/simulator.py
# 或在电脑上： python tools/simulator.py 然后手动输入 JSON
```

---

## 代码在哪

```
pi-agent/                   树莓派端（Python，除 bless 外无第三方运行时依赖）
├─ agent/
│  ├─ main.py               入口
│  ├─ ble_server.py         GATT 服务（bless + BlueZ）
│  ├─ agent.py              命令分发 + 订阅推送
│  ├─ protocol.py           行式 JSON 分帧
│  ├─ config.py             默认配置 + /etc/piblue/config.json 覆盖
│  └─ handlers/             sysinfo / shell / sensors / control
├─ scripts/dev.sh           调试运行（只写目录内，不装服务）
├─ scripts/install.sh       正式安装 + systemd
├─ scripts/uninstall.sh     移除 systemd 那一套
├─ scripts/doctor.sh        体检
└─ tools/simulator.py       不开蓝牙，用 stdin/stdout 跑同一套逻辑
```

---

## 接下来

* 控制页能做什么、磁盘挂载规则：[control.md](control.md)
* 为什么 `exec` 默认这么保守、配对码挡得住什么：[security.md](security.md)
* 连不上 / 超时 / 乱码：[troubleshooting.md](troubleshooting.md)
