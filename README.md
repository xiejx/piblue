# PiBlue · 手机通过蓝牙查看与控制树莓派

一个自洽的最小系统：树莓派用 Python 跑一个 BLE GATT 服务，手机上用 Kotlin 写的原生 App 连上去，读系统状态、读传感器、执行命令。

```
┌──────────────┐   BLE (GATT, 无需配对)   ┌─────────────────────────┐
│  Android App │ ◀══════════════════════▶ │  树莓派                  │
│  Kotlin      │   RX: JSON 命令（分片写）│  bless → GATT server     │
│  原生 View UI│   TX: JSON 回包（notify）│  Agent → handlers        │
└──────────────┘                          │   ├ sysinfo  系统快照    │
                                          │   ├ shell    命令白名单  │
                                          │   ├ control  重启/磁盘/Wi-Fi│
                                          │   └ sensors  探头/自定义 │
                                          └─────────────────────────┘
```

**为什么选 BLE 而不是经典蓝牙串口（SPP）**：SPP 依赖 BlueZ 的废弃兼容模式（`bluetoothd -C`）+ `rfcomm` 绑定，近几版 Raspberry Pi OS 上越来越容易翻车；GATT 是 BlueZ 官方支持的路径，手机端也不用走"配对"流程，连上就能用。代价是两端都要自己按 MTU 分片：手机侧在 `BleClient.kt`，树莓派侧在 `BleTransport.send` —— **BlueZ 不会替你切 notify，超长的一条直接丢**，这一点踩过坑，见排错表。

---

## 目录

```
rasberrypi/
├─ pi-agent/                 树莓派端（Python，无第三方运行时依赖，bless 除外）
│  ├─ agent/
│  │  ├─ main.py             入口
│  │  ├─ ble_server.py       GATT 服务（bless + BlueZ）
│  │  ├─ agent.py            命令分发 + 订阅推送
│  │  ├─ protocol.py         行式 JSON 分帧
│  │  ├─ config.py           默认配置 + /etc/piblue/config.json 覆盖
│  │  └─ handlers/           sysinfo / shell / sensors / control
│  ├─ scripts/dev.sh         调试运行（只写目录内，不装服务）
│  ├─ scripts/install.sh     正式安装 + systemd
│  ├─ scripts/uninstall.sh   移除 systemd 那一套
│  ├─ scripts/doctor.sh      体检脚本
│  └─ tools/simulator.py     不开蓝牙，用 stdin/stdout 跑同一套逻辑
├─ android/                  手机 App（Kotlin，零 UI 框架依赖）
│  ├─ build-apk.sh           命令行构建（走本机工具链，可离线）
│  └─ app/src/main/java/com/raspi/piblue/
│     ├─ ble/BleClient.kt    扫描 / 连接 / 分片写 / 按字节重组
│     ├─ data/Models.kt      JSON → 数据类
│     ├─ data/Control.kt     控制页的数据类（动作/磁盘/Wi-Fi）
│     ├─ PiController.kt     状态与命令，与 UI 解耦
│     └─ MainActivity.kt     原生 View 四页界面
├─ dist/PiBlue-debug.apk     现成的调试包
└─ docs/protocol.md          协议细则
```

---

## 一、树莓派端

### 前提

* 树莓派 3B+ / 4 / 5（板载蓝牙，3B 也可以），Raspberry Pi OS Bookworm 或 Bullseye
* 能 SSH 进去，能联网装包
* **不需要** 手机和树莓派在同一个 Wi-Fi 下，纯蓝牙通信

### 先把代码传上去

在电脑上（Windows 用 Git Bash / PowerShell 都行）：

```bash
cd E:/workbuddy/rasberrypi
scp -r pi-agent pi@raspberrypi.local:~/
ssh pi@raspberrypi.local
```

`raspberrypi.local` 解析不到就换成 IP。后面的命令都在 `~/pi-agent` 里执行。

### 调试模式（不装服务，推荐先这样跑）

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

### 正式安装（跑通之后再考虑）

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

`install.sh` 做的事：

1. `apt install python3-venv python3-dbus python3-gi bluez`
2. 建 `/opt/piblue/venv`（`--system-site-packages`，留给旧版 bless 的 dbus 绑定）
3. `pip install 'bless>=0.3.0'`（0.3 起走 dbus-next，纯 Python；0.2.x 的 txdbus 在 Python 3.11+ 会崩）
4. 首次安装时把配置写到 `/etc/piblue/config.json`（已存在则保留）
5. 生成配对码到 `/etc/piblue/token`（已存在则保留，不会悄悄换掉）
6. 装 `piblue.service` 并启动
7. 装 `/usr/local/bin/piblue-token`（查看/更换配对码的短命令）

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

装完自检：

```bash
sudo ./scripts/doctor.sh
```

全绿的话，树莓派已经在广播名为 `PiBlue` 的 BLE 设备了。

> 为什么服务以 root 跑：BlueZ 的 `LEAdvertisingManager1` 只对特权用户开放，普通用户加 capability 也能做，但 systemd 单元里 root 最省事。这也是为什么 `exec` 的权限控制必须做得保守（见下文）。

### 配置

`/etc/piblue/config.json`，改完 `sudo systemctl restart piblue`：

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

### 控制页（`control`）

手机端**不发送命令行**，只发送动作名。动作表固定在 `pi-agent/agent/handlers/control.py`，不在表里的动作不存在；所有参数（SSID、设备路径、自定义命令序号）都由树莓派侧校验后才进 `argv`，且永远不经过 shell。

| 卡片 | 动作 | 背后做的事 |
| --- | --- | --- |
| 电源 | `reboot` / `shutdown` | `systemctl --no-block reboot|poweroff`，默认延迟 2 秒，先把回包发出去 |
| 服务 | `restart-agent` / `restart-bluetooth` | 用 `systemd-run --on-active=2s` 排在自身 cgroup 之外重启，否则服务会把自己杀掉、回包发不出去 |
| Wi-Fi | `wifi-enable` `wifi-disable` `wifi-scan` `wifi-connect` `wifi-disconnect` | 优先 `nmcli`（Bookworm 默认），没有就用 `wpa_cli`（Bullseye 及更早）；开关同时用 `rfkill` 兜底 |
| 磁盘 | `disk-list` `disk-mount` `disk-unmount` | `lsblk -J` 列分区（未挂载的也列，带「挂载」按钮），每行带**文件系统类型**（`ext4`/`ntfs`/`vfat`…）。点「挂载」时自己填目录名，挂到 `control.mount_root/<名字>`，目录不存在会自动建；**NTFS 用 `mount -t ntfs3`**，其他类型交给 `mount` 自己探测。卸载允许两类：这个目录下的挂载，以及**可移动设备**的挂载（`/media/pi/…` 的 U 盘能安全弹出）；`/`、`/boot`、`/usr` 等关键挂载点一律拒绝 |
| 自定义 | `custom` + `index`，`custom-save` + `items` | 执行第 index 条命令；在 App 里增删改（最多 5 条），保存时整表替换 |

**挂载点由你自己取名字。** 比如挂到 `/mnt/usb`，就在对话框里填 `usb`：

* 名字只能是**单个路径段**：字母数字开头，后面可以是字母、数字、点、下划线和减号，最长 32 个字符。`/`、`..`、空格一律拒绝，所以它永远逃不出 `mount_root`。
* 目录不存在会自动 `mkdir -p`（通过 sudo，dev 模式也能建）。
* 目录**已存在且不是空的**会直接拒绝 —— 在手机上挂载会盖住原有文件，而你在手机上看不出来。
* 设备已经挂在别处（比如桌面自动挂到 `/media/pi/KINGSTON`）时，会要求先卸载再改挂。
* 挂好之后，同一行变成「卸载」，因为 `mount_root` 下的挂载本来就允许卸。
* **文件系统类型在列表里直接显示**（`ext4`、`ntfs`、`vfat`、`exfat`…），挂载对话框也会写一句用哪个驱动。
* **NTFS 显式用 `ntfs3`**：`mount` 在 NTFS 上默认走用户态 `ntfs-3g`，慢，而且精简镜像里常常没装；点名 `ntfs3` 用的是内核驱动（5.15 起内置，Bookworm 就有）。内核没有 ntfs3 时（Bullseye 的 5.10）会自动退回不带 `-t` 的探测，也就是 `ntfs-3g`，所以老系统照样能挂，只是没有加速。
* 其他文件系统**不指定** `-t`：ext4、vfat、exfat 由 `mount` 自己按 superblock 判断，写死反而多一条出错的路。
* Windows 没正常关机时 NTFS 卷会被标记为脏，读写挂载会被拒；对话框里的「只读挂载」就是给这种情况用的，先把文件读出来，再回 Windows 跑一次 `chkdsk`。

自定义命令现在有两种来源，**App 写入的优先**：

* `custom.json`（和 `config.json` 同目录，服务模式即 `/etc/piblue/custom.json`）——App 增删改写的就是这个文件，原子替换、0644。它存在时以它为准。
* `config.json` 里的 `control.custom` 数组 —— 只作为**首次保存之前的种子**，这样手写的条目照旧能用；第一次在 App 里保存后，这份列表就搬到 `custom.json` 了。

```json
// /etc/piblue/custom.json —— 一般不用手改，App 会写
{ "items": [ { "name": "重启 nginx", "line": "systemctl restart nginx" } ] }
```

保存时用**执行时的同一套规则**做预检，写不进去的命令当场拒绝，不会等到你点按钮才报错。这些命令以 root 执行，**不受 `shell.allowed` 白名单限制**；`shell.deny` 里的 `rm`/`dd`/`mkfs` 等仍然拒绝。管道和重定向由 `control.custom_allow_meta`（默认 `true`）控制 —— 自定义命令是你自己写一次的固定操作，和手写在配置里等价；不想放开就改成 `false`。`control.allow_custom_edit = false` 可以让 App 只能执行、不能改。

> 升级提示：`/etc/piblue/config.json` 不会被 `install.sh` 覆盖。如果你那份里还是 `"mount_root": "/mnt/piblue"`，新版会挂到 `/mnt/piblue/名字`（App 里会显示真实路径）。想用 `/mnt/名字` 就把这行改成 `"/mnt"` 再 `sudo systemctl restart piblue`。`allow` 数组不用动 —— `custom` 在就自动带上 `custom-save`。

两条刻意没做的：**格式化分区**和**分区表操作**。这两个操作没有撤销，隔着 30 米蓝牙点错一次就是数据没了，要格式化请用 SSH。

Wi-Fi 密码只经蓝牙传给树莓派，不落手机存储；但 `nmcli` 会把密码放在自己的参数里执行，树莓派上短暂的 `ps` 能看到 —— 单用户机器可接受，多用户机器请当作已知项。
* 传感器扩展（不用改代码）：

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

### 有多台树莓派？

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

### 先不写 App，验证链路

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

不想关认证也可以，用 `docs/protocol.md` 里那段 Python 从 `hello` 的 challenge 算出 `mac` 再发 `{"cmd":"auth","mac":"…"}`。

---

## 二、手机端

### 构建

已用本机工具链 `D:\git-workspace\casting-android\.toolchain`（JDK 17 + SDK 34 + Gradle 8.7）**离线构建成功**，产物在 `dist/PiBlue-debug.apk`（2.1 MB）。

命令行（Git Bash）：

```bash
cd android
./build-apk.sh            # debug
./build-apk.sh release    # release（未签名）
./build-apk.sh clean
```

脚本自己设好 `JAVA_HOME` / `ANDROID_HOME` / `GRADLE_USER_HOME`，并把 `local.properties` 指到工具链的 SDK —— 不依赖 Android Studio，也不依赖网络。工具链换位置时：

```bash
TOOLCHAIN=/d/somewhere/.toolchain ./build-apk.sh
```

装到手机（开 USB 调试）：

```bash
"/d/git-workspace/casting-android/.toolchain/sdk/platform-tools/adb" \
  install -r dist/PiBlue-debug.apk
```

也可以用 Android Studio 打开 `android/` 目录直接 Run（wrapper 已生成，Gradle JDK 选 17）。

> **为什么 UI 是原生 View 而不是 Compose**
> 这台机器到 Google Maven 的大文件下载完全不通（实测 60 秒 0 字节，`Read timed out`），Compose 那几十个 artifact 拉不下来，构建必然失败。改用框架自带的 LinearLayout / TextView / ListView 后，依赖只剩 `core-ktx` + `coroutines`，全部命中工具链缓存，可以完全离线构建。功能与交互和原设计一致。

最低支持 Android 7.0（API 24），目标 API 34。

### 权限

* Android 12+：`BLUETOOTH_SCAN`（声明 `neverForLocation`）+ `BLUETOOTH_CONNECT`
* Android 11 及以下：`ACCESS_FINE_LOCATION`（老系统的 BLE 扫描被归到定位权限里）

App 只在第一次点"扫描并连接"时申请。

### 四个页面

| 页面 | 内容 |
| --- | --- |
| **状态** | SoC 温度、1/5/15 分钟负载、内存与磁盘占用（带进度条）、运行时长、**树莓派时间**（含时区、与手机的偏差、NTP 是否同步过）、IP、型号/内核/Wi-Fi、供电与降频告警。右上角可开关"每 2 秒实时推送"，开着时时间跟着一起走 |
| **传感器** | 自动列出可用传感器（SoC 温度、DS18B20、DHT、自定义脚本）。点卡片单独读一次，右上角刷新读全部 |
| **控制** | 重启 / 关机、重启 agent 与蓝牙服务、Wi-Fi 开关与连接（扫描附近网络、已保存的网络可直接点）、磁盘列表与挂载（每行标出**文件系统类型**，挂载目录名自己填，NTFS 走 `ntfs3`，卸掉自己挂的）、最多 5 条自定义命令（可在 App 里增删改）。**按钮由树莓派下发**，不是写死在 App 里 |
| **终端** | 输入命令回车执行，输出区分 stdout / stderr / 退出码；预置了几个常用只读命令的快捷 chip |

---

## 三、命令与安全边界

### 先说清楚 BLE 挡得住什么

广播是公开的：只要树莓派在广播，**任何开了扫描的手机都能看到 `PiBlue`，也都能连上来**。能挡住"连上来"的唯一办法是停止广播，而那样你自己的手机也找不到它。所以真正的关卡在命令层：

**配对码（挑战-响应）**

1. 手机连上 → `hello` → 树莓派回一个一次性随机数 `challenge`
2. 手机用配对码算 `HMAC-SHA256(key, challenge)` 发 `auth`
3. 验证通过前，除了 `hello` / `auth` / `ping`，一律回 `{"ok":false,"code":"unauthorized"}`

配对码是 16 位 base32（约 80 bit），存在 `/etc/piblue/token`（0600），**从不经过 BLE** —— 链路上只有对一次性随机数的 HMAC，录下来也没法重放。连错 5 次锁 60 秒。会话在收到 `hello`、设备断开、或 300 秒无报文时失效；断开信号来自 BlueZ 的 D-Bus 事件（`agent/link_watch.py`），收不到时退化成空闲超时，日志里会写明当前用的是哪一种。

即便如此，这层的意义是"**别人连上也没用**"，不是"别人连不上"。真要隐藏设备，得改 `ble.name` 加个随机后缀，或者干脆在不用的时候 `sudo systemctl stop piblue`。

### 树莓派能从手机拿到什么

连接建立时 BlueZ 会为对方建一个 `org.bluez.Device1` 对象，`link_watch.py` 从对象路径（`/org/bluez/hci0/dev_AA_BB_CC_DD_EE_FF`）里取出地址写进日志：

```
link up: 7C:2A:31:5E:10:04 (random) name='Galaxy S23'
link down: 7C:2A:31:5E:10:04
```

| 信息 | 能否拿到 | 说明 |
|---|---|---|
| 蓝牙地址 | 能 | 但 Android / iOS 默认用 RPA（可解析私有地址），**每次连接都可能不同**；只有配对绑定过，对方才能认出是同一台 |
| 地址类型 | 能 | `public` / `random`，手机上基本都是 `random` |
| 设备名 | 不一定 | BlueZ 读到 GAP name 才有；不少手机连上后不主动给，日志里就不会有 `name=` |
| 信号强度 | 能 | `Device1.RSSI`，随时间变化，只适合判断远近 |
| 协商 MTU | 能 | 由手机在 `hello` 里自报，BlueZ 不暴露（见 MTU 分片那节） |
| 型号 / 系统版本 / IP / 手机号 | **不能** | 不在 BLE 链路层里，App 不主动上报就没有 |

结论：**蓝牙地址不能当身份凭证**。它每次可能变、是明文、也能被伪造。要"认设备"只能让 App 主动带一个标识（比如首次启动生成并存本地），但能改 App 的人也能换一个——真正的关卡仍然是配对码。

### `exec` 本身

`exec` 是整条链路上唯一有破坏力的口子，所以默认上了三层锁（`pi-agent/agent/handlers/shell.py`）：

1. **白名单**：只允许 `ls / cat / df / free / uptime / vcgencmd / systemctl / ps / ip …`，`sudo` 会被剥掉再看真实命令
2. **黑名单**：`rm / dd / mkfs / fdisk …` 永远拒绝，`allow_any=true` 时也拒绝
3. **元字符**：默认不允许 `; | & > < $ \``，避免 `ls; rm -rf /` 绕过白名单

注意这三层是**防手滑的护栏，不是沙箱**：`sh` 和 `python3` 在白名单里，而白名单只看行首那个可执行文件，所以 `sh -c '…'`、`python3 -c '…'`、`find . -exec …` 都能绕过去。它们都以 root 身份跑。换句话说：**能过配对认证的人就等于拿到了 root**，配对码就是这台机器的实际口令，别外传。

建议：只在自家网络环境用；不要把 `allow_any` 打开后长期开着服务；真要远程管理，用 SSH 更合适，蓝牙这套定位是"在旁边看一眼、改点小东西"。

### `control` 为什么比 `exec` 安全

控制页走的是另一条路，它把"能做什么"从**字符串**换成了**枚举**：

* 动作名固定，未知动作直接拒绝；手机永远发不出命令行。
* 参数只当数据用：`ssid` 校验长度与字符集，`device` 必须是 `lsblk` 里真实存在的那一条，`index` 必须是 `0-4`。
* 一律 `create_subprocess_exec`，不经过 `sh`，所以没有 `;`、`$(…)`、`反引号` 这些事。
* 挂载点由手机发**目录名**（校验为单个路径段）而不是路径，树莓派侧用 `posixpath.join(mount_root, 名字)` 拼出来，`..` 出不去。
* 危险动作要二次确认：`reboot` / `shutdown` 必须带 `confirm: true`，而且 App 自己也会先弹一次 —— 两道，防的是口袋里误触。
* 卸载有范围限制：`control.mount_root` 下的挂载、以及可移动设备的挂载；`/`、`/boot`、`/boot/firmware`、`/usr`、`/var`、`/etc` 这些关键挂载点写在代码里的黑名单中，`lsblk` 的 `RM` 位即使标错也卸不掉。

它**不能替代 `exec`**：临时想看点什么，还得去终端页。但"每天要按几次的那几个操作"放在控制页，就不必再给终端开 `allow_any` 了。

---

## 四、排错速查

| 现象 | 排查 |
| --- | --- |
| **打开后几秒闪退，没连蓝牙也会** | 见下方「启动 5 秒后闪退」一节：`com.google.common.util.concurrent.ListenableFuture` 不在包里，`androidx.profileinstaller` 写 ART profile 时 `VerifyError` |
| App 扫不到设备 | `sudo systemctl status bluetooth` 是否 active；`bluetoothctl show` 是否 `Powered: yes`；`journalctl -u piblue -n 40` 有没有 `advertising as 'PiBlue'`；树莓派是否被别的蓝牙设备连着（一个适配器同时只能做有限的事） |
| 扫到但连不上 / 立刻断开 | 关掉手机蓝牙再打开（清 GATT 缓存）；确认没有别的手机还连着；看 `journalctl -u piblue -f` 有无报错 |
| 连上后一直空白 | 服务 UUID 不匹配 —— 确认树莓派跑的是这个版本的 agent；nRF Connect 能否看到 `6e400001` 服务 |
| **状态栏显示"已连接（未配对）"，命令一律没反应** | 配对码没填或填错。App 会自己弹出输入框；也可以断开重连，在扫描窗口的输入框里重填。树莓派侧日志：`wrong pairing code, N attempt(s) left`→ 码不对；`too many wrong codes` → 被锁 60 秒，等一会儿；`this Pi has no pairing code yet` → `/etc/piblue/token` 不存在，跑一次 `install.sh` 或 `sudo /opt/piblue/venv/bin/python -m agent.token new` |
| 树莓派日志刷 `bluez reports the central is gone` | 正常：每次断开都会让会话作废，手机重连后会自己重新认证一次 |
| **显示"已连接 PiBlue, MTU 517"但每条命令都「树莓派没有响应（超时）」** | 先分清方向：`sudo btmon -w /tmp/bt.log`（或在另一窗口 `sudo btmon`），然后在 App 里点一条命令。日志里 `ATT: Write Request` 之后有 `ATT: Write Response` + `Handle Value Notification` → 链路正常，问题在手机端回调（Android 13+ 只认三参数 `onCharacteristicChanged`，见下）；只有 `Write Request` 没有 `Write Response` → 手机侧写入就卡住了（`BleTimeout`，写入 5 秒超时）；有 Response 没有 Notification → 树莓派收到但没回，看 `journalctl -u piblue -f` 有没有 `rx N bytes` / `<- id=1 cmd=info` / `notify failed` |
| **小命令（`ls`）能出结果，`info` / 长输出一律超时** | 单向超大 notify 被 BlueZ 丢了：ATT 的 notify PDU 不能超过 `MTU-3`，超了 bluetoothd 直接丢弃，D-Bus 那层仍返回成功，所以树莓派日志里连报错都没有。判断方法：`echo '{"id":1,"cmd":"info"}' \| ./venv/bin/python tools/simulator.py` 看响应多少字节，超过 514（MTU 517）就是它。修法已在代码里：手机连上后先发 `hello` 带上 mtu，树莓派按 `mtu-3` 分片（`BleTransport.set_peer_mtu`）；没收到 hello 时按 20 字节保守发 |
| **`./scripts/dev.sh: line 15: set: pipefail: invalid option name`**（或任何 `\r` 相关的怪错） | 脚本被存成了 Windows 行尾（CRLF），bash 把 `pipefail\r` 当成选项名，还没跑到第一行逻辑就退出了。在树莓派上一次性修掉：`grep -rlU $'\r' ~/pi-agent --include='*.sh' --include='*.py' \| xargs sed -i 's/\r$//'`。仓库已加 `.gitattributes`（`* text=auto eol=lf`），git 拉取不会再转回 CRLF；但用 scp / 共享目录 / 复制粘贴直接拷文件时仍会复发，改完确认一下：`head -1 ~/pi-agent/scripts/dev.sh \| od -c \| head -2` 里不应该出现 `\r` |
| 命令返回 `not in the allow list` | 加进 `shell.allowed`，或临时 `allow_any=true` |
| 命令返回 `shell metacharacters are blocked` | 去掉管道/重定向，或 `allow_meta=true` |
| 控制页一直停在"正在读取树莓派的可控项…" | `control` 的 `status` 没回。`journalctl -u piblue -f` 看有没有 `<- id=N cmd=control`；`exec` 能通而它不通，多半是 `control.enabled=false` 且页面在等 `actions` 列表 |
| 控制页少了整张卡片 | 正常：卡片是按 `control.allow` 和机器能力生成的。删掉了对应动作、没有 `nmcli`/`wpa_cli`、没有 `lsblk`，那一块就不会出现。`status` 的 `errors.disk` 会说明原因 |
| 控制页报 `无法执行 systemctl` | agent 不是 root（`dev.sh` 起的），`sudo -n` 又没有免密。用 `install.sh` 装成服务，或给该用户配 NOPASSWD |
| **连 Wi-Fi 报 `802-11-wireless-security.key-mgmt: property is missing`** | 不是密码错，是 libnm 收到了一份没写 `key-mgmt` 的 Wi-Fi 配置。根因是旧实现走 `nmcli dev wifi connect`，它在扫描缓存里读不到该 AP 的加密类型（隐藏网络、缓存过期、只扫到别人家同名 AP）时就只写 SSID 和 psk，`key-mgmt` 留空 → 整个 profile 被判非法。现在的实现改成显式 `connection add` + `connection modify wifi-sec.key-mgmt <wpa-psk/sae/none>`，不再依赖扫描猜测。如果树莓派上已经留下坏配置，`nmcli connection delete <ssid>` 删掉重连即可（新代码也会复用并修正同名 profile） |
| **关掉 Wi-Fi 再打开，不会自己连回去** | 只执行 `nmcli radio wifi on` 是**不够**的：NetworkManager 会给"被手动断开过"的设备打一个标记（控制页的「断开」按钮就会打上），标记存在时所有 autoconnect 都被压住。所以 `wifi-enable` 现在会补一条 `nmcli device connect wlan0`，清掉标记并按优先级挑一个已保存的网络重新激活。只有已经保存过密码的网络才会被自动连回去——没保存过的网络无从知道密码 |
| **连过的网络，再连还要输一次密码** | 现在不用了。`status` 的 `wifi.saved` 会带上树莓派已保存的 SSID（只有名字，永远不含密码），App 在列表里标「已保存」并加粗变色，点一下直接发不带密码的 `wifi-connect`，树莓派用自己存档里的密码激活。扫描没扫到的已保存网络也会列在最后（标「未扫描到」），所以断网重连这件事不再依赖"必须扫到它" |
| 想在终端里核对一下已保存的网络 | `nmcli -t -f NAME,TYPE connection show`（所有 profile）、`nmcli -g 802-11-wireless.ssid connection show <名字>`（这个 profile 是哪个 SSID）、`nmcli device wifi list`（带信号的当前列表）。`con up` 一个已有 profile 就等于"用保存的密码连接" |
| 挂载报 `unknown filesystem type` | 系统没装这种文件系统的驱动：`sudo apt install exfat-fuse ntfs-3g` |
| **插了 U 盘，磁盘卡片里看不到它** | 先点「刷新」（卡片不会自己轮询）。还是没有就看 `lsblk -o NAME,PATH,TYPE,FSTYPE,LABEL,MOUNTPOINT,RM`：整盘没分区表（superfloppy）也能挂，会显示成 `未识别到文件系统`；`swap`/`crypto_LUKS`/`LVM2_member` 以及 `mmcblk0boot0`、`loop*` 这类伪设备是刻意过滤掉的 |
| **磁盘列表里只有"已挂载"，没有「挂载」按钮** | 两种情况：① 分区已经被桌面自动挂到 `/media/pi/…` 了 —— 这时该行给的是「卸载」，卸掉之后同一行就会变成「`exfat` 未挂载」并出现「挂载」，挂到 `/mnt/<你填的名字>`；② 分区的 `FSTYPE` 是空的（没格式化），没有任何东西可挂。控制页现在会把 Pi 返回的每一条都列出来，所以不会再出现"明明有盘却一片空白" |
| **NTFS 挂载报 `unknown filesystem type 'ntfs3'`** | 内核没有 ntfs3 模块（5.15 以下，或 `CONFIG_NTFS3_FS` 没开）。新版会自动退回不带 `-t` 的挂载（`ntfs-3g`），如果连它也没装：`sudo apt install ntfs-3g` |
| **NTFS 挂载报 `Volume is dirty`** | Windows 没正常关机/休眠。勾上对话框里的「只读挂载」先把文件读出来，回 Windows 跑 `chkdsk /f` 之后再读写挂 |
| 想卸载 `/media/pi/xxx` 却被拒 | 现在的规则是：`control.mount_root`（默认 `/mnt`）下的挂载、以及**可移动设备**的挂载可以卸；`/`、`/boot`、`/boot/firmware`、`/usr`、`/var` 等关键挂载点一律拒绝，哪怕 `lsblk` 的 `RM` 位标错了 |
| 状态页时间显示"未同步 NTP" | 树莓派没连上网或 NTP 被挡住，`timedatectl show -p NTPSynchronized` 会返回 `no`。没有 RTC 的型号断电后会从旧时间继续走，表现为 TLS/apt 报证书错误。`sudo systemctl restart systemd-timesyncd` 或临时 `sudo date -s` 都行；有网络时会自己校正 |
| 卸载报 `正在被使用` | `lsof +D /mnt/xxx` 找出占用者，关掉再卸 |
| 挂载报 `… 已存在且不是空目录` | 那个目录里有东西。挂上去会把这些文件盖住，而且手机上看不出来，所以直接拒绝。换个名字，或先在 SSH 里清空/改名 |
| 挂载报 `要先卸载才能改挂到 …` | 这块盘已经被挂到别处（多半是桌面自动挂的 `/media/pi/…`）。先点「卸载」，再点「挂载」填新名字 |
| **控制页没有「添加命令」按钮** | 说明树莓派那边的 agent 还是 1.2.x（`status` 里没有 `custom-save`），或者 `control.allow_custom_edit=false`，或者已经存满 5 条。连上一眼日志里的 `配对通过 · agent x.x.x` 最快 |
| 保存自定义命令报 `会被拒绝：'rm' is on the deny list` | 预检按执行规则跑了一遍。`rm`/`dd`/`mkfs`/`shred` 等在 `shell.deny` 里，改命令本身，或从 deny 列表里删掉那项（不建议） |
| 自定义命令里有管道/重定向却报 `metacharacters are blocked` | 把 `control.custom_allow_meta` 改回 `true`（默认就是 true）。它被关掉时，自定义命令和终端页一样不许 `;` `\|` `&` `>` `<` |
| 点「重启 agent」后立刻收到超时 | 预期：服务 2 秒后重启，蓝牙会断。等它回来重连即可；如果 30 秒还没广播，说明 unit 起不来，去 SSH 看 `systemctl status piblue` |
| 中文乱码 | 不该出现：手机端按字节累积、遇到换行才 UTF-8 解码。若出现，检查是否有人改了 `BleClient.emitFrames` 的分片逻辑 |
| 构建报 `No cached version ... available for offline mode` | 工具链缓存里缺这个 artifact。去掉 `--offline` 让 Gradle 联网取一次，或在 `app/build.gradle.kts` 的 `resolutionStrategy` 里 force 到已缓存版本 |
| 温度显示 `—` | 树莓派上没有 `vcgencmd`（非官方系统）且 thermal zone 读不到，属正常降级 |
| 传感器列表只有 SoC 温度 | 没接探头，或 1-Wire 没开（`dtoverlay=w1-gpio`），或 DHT 没装 `adafruit-circuitpython-dht` |

树莓派侧单独验证协议（不需要手机、不需要蓝牙）：

```bash
cd /opt/piblue
echo '{"id":1,"cmd":"info"}' | ./venv/bin/python tools/simulator.py
# 或在电脑上： python tools/simulator.py 然后手动输入 JSON
```

### 启动 5 秒后闪退（真机踩到的第一个坑，已修）

症状：装上 APK，打开，界面正常，**不碰任何东西，5–6 秒后闪退**，与蓝牙无关。

链路：

1. `androidx.core` → `androidx.concurrent:concurrent-futures` 声明依赖 `com.google.guava:listenablefuture:1.0`（这个 artifact 里**只有一个 `ListenableFuture` 接口**，没有实现）。
2. 离线缓存里只有它的 `.pom` 没有 `.jar`，于是当时用 `force("...:9999.0-empty-to-avoid-conflict-with-guava")` 顶掉 —— 那是个**空 jar**，只适用于"项目里已经有完整 Guava"的场景。
3. 本工程没有 Guava，结果 APK 里 `androidx.concurrent.futures.AbstractResolvableFuture implements ListenableFuture` 这句引用的接口根本不存在。
4. 启动时 `androidx.startup` 注册的 `ProfileInstallerInitializer` 会在首帧后 `postDelayed(5000 + rand(1000))`，到点在**后台线程**调 `ProfileInstaller.writeProfile()` → `ProfileVerifier.writeProfileVerification()`。
5. `ProfileVerifier` 有 `ResolvableFuture` 静态字段和返回 `ListenableFuture` 的方法，ART 校验器解析不到类型 → `VerifyError`，后台线程无人捕获 → 进程死。

修法：删掉那条 `force`，改成 `exclude("com.google.guava:listenablefuture")`，接口由源码提供（`app/src/main/java/com/google/common/util/concurrent/ListenableFuture.java`，内容与官方 artifact 等价）。**如果以后真加了 Guava 依赖，要把这个文件删掉**，否则类重复。

自查方式（不解包、不装工具）：

```bash
unzip -l dist/PiBlue-debug.apk | head
# 或用 dexdump / 任意 dex 分析脚本确认 ListenableFuture 在包内
```

同时 `PiBlueApp` 装了一个全局未捕获异常处理器：崩溃会写到
`Android/data/com.raspi.piblue/files/crash-last.txt`（**不需要任何权限**，文件管理器或
`adb pull` 都能取），下次启动时以对话框弹出一次。后台线程的崩溃从此有据可查。

```bash
adb logcat -b crash -d | grep -i piblue
adb shell cat /sdcard/Android/data/com.raspi.piblue/files/crash-last.txt
```

---

## 五、往下长

* **加 GPIO 控制**：在 `handlers/` 加个 `gpio.py`，写读写的两个函数，然后在 `agent.py` 的 `self._handlers` 里注册 `"gpio_set"` / `"gpio_get"`，App 端加一页即可 —— 协议不用动。
* **加推送 topic**：`_sub_loop` 里加分支，App 端 `handleEvent` 加一个 `case`。
* **多设备**：`BleClient` 现在只保持一个 GATT 连接；要同时连多台就把 `gatt` 改成按地址索引的 map，命令里带设备 id。
* **做成一键开机自启**：跑一次 `install.sh` 即可（`systemctl enable`），断电重启后自动上线。调试期不用装，用 `dev.sh` 前台跑就行。

---

## 状态说明

* 树莓派端：协议、分帧、四个 handler、订阅推送已用 `tools/simulator.py` 跑通（含白名单/元字符/黑名单拒绝路径、未知命令、非法 JSON、超长行、分片重组与中文跨包边界）。
* `info` 的 `time` 对象（`local`/`tz`/`utc_offset`/`synced`）与 `summary` 里的同一份时间；`_can_unmount` 的判定矩阵（`/`、`/boot/firmware` 拒，`/media/pi/*` 的可移动挂载放行，`mount_root` 下的挂载放行）；`_lsblk_tree` 对 superfloppy（整盘带 fs、无子节点）与无 fs 可移动盘的收录，以及 `mmcblk0boot0`/`loop*` 的排除。
* `control` 在开发机上验证过的部分：`status` 的降级（没有 `lsblk`/`nmcli` 时不给对应动作）、14 个动作的参数校验与拒绝路径、`control.allow` 白名单、`enabled=false`、自定义命令的上限 5 条与「`rm` 仍被拒」、`wifi-connect` 的 `key-mgmt` 推导（`WPA2`/`WPA1 WPA2`→`wpa-psk`，`WPA3`→`sae`，`--`+无密码→`none`，`802.1X`/`WEP`→明确拒绝）与 nmcli 参数序列（可见网络 / 隐藏网络 / 已有 profile 三条路径，用假 runner 跑出来核对 argv）。**没在真机上验证过**的是真正的系统调用：`systemctl`、`nmcli`/`wpa_cli`、`mount`、`mkdir`。这几块第一次在树莓派上跑时请开着 `journalctl -u piblue -f`。
* 1.3.0 / 1.3.1 新增部分在开发机上验证过：
  * `_check_mount_name` 的 14 组输入（`usb`/`backup_2`/`a.b-c` 通过；空串、`..`、`.`、`a/b`、`/etc`、`_x`、`-x`、33 字符、含空格全部拒绝）；
  * `disk-mount` 的 argv：`mkdir -p /mnt/<name>` → `mount /dev/sda1 /mnt/<name>`；不带 `name` 时退回 label；已挂在别处时提示先卸载、挂在同一目标时幂等；挂载点已存在且非空时拒绝（真建了个目录试的）；
  * `mount_driver` 的判定：`ntfs`/`NTFS`/`ntfs3`/`fuseblk` → `ntfs3`，`ext4`/`vfat`/`exfat`/`f2fs`/空 → 不指定；argv 形如 `mount -t ntfs3 /dev/sda1 /mnt/usb`、`mount -t ntfs3 -o ro /dev/sda1 /mnt/usb`、`mount /dev/sda1 /mnt/usb`；ntfs3 缺失时（假 runner 回 `unknown filesystem type 'ntfs3'`）第二次不带 `-t` 重试且回包不再声称用了 ntfs3；`dirty` / `unknown filesystem type` 各自的提示文案；
  * `custom-save` 往返：写 `custom.json` → `status` 读回一致（含 `line`）；6 条、空命令、含换行、名称超 40、`rm` 全部拒绝，且报错带第几条；
  * `allowed()` 对**旧配置**（`allow` 里只有 `custom`）也发放 `custom-save`，`allow_custom_edit=false` 时收回；
  * `_can_unmount` 换成路径段前缀匹配后重跑矩阵：`/`、`/boot/firmware`、`/mntfoo` 拒；`/media/pi/USB`、`/mnt/usb` 放行；
  * 模拟器端到端：`status`（空列表时只给 `custom-save`）→ `custom-save` → `status`（出现 `custom`）→ `custom` 执行 → 拒绝路径。
* 手机端：**已构建成功** —— `dist/PiBlue-debug.apk`（App 1.3.1 / versionCode 6，agent 协议 1.3.1），AGP 8.5.2 / Kotlin 1.9.24 / compileSdk 34，`aapt dump badging` 校验包名、权限、启动 Activity 均正确，`aapt2 dump resources` 确认 `custom_add`/`custom_hint` 在包里。真机首次运行暴露的「启动 5 秒后闪退」已定位并修复（`listenablefuture` 空 jar 导致 `ListenableFuture` 缺类 → `profileinstaller` VerifyError），详见「启动 5 秒后闪退」一节；同时加了全局崩溃落盘，BLE 实际链路仍需真机验证一次。
* 控制页的按钮由 `control` 的 `status` 驱动，所以**树莓派上增减动作不需要重装 App**，最坏情况只是新动作没有中文标签（会按动作名显示）。
* 构建时的环境约束已固化在 `app/build.gradle.kts` 里：排除 `kotlin-stdlib-jdk7/jdk8`（1.8+ 已并入 stdlib），把 `collection` / `annotation` 钉到工具链缓存里已有的版本，`listenablefuture` 直接 exclude 并由源码补上接口（见「启动 5 秒后闪退」）。
* `bless` 与 BlueZ 的实际广播行为依赖具体系统版本；`scripts/doctor.sh` 会在装完后给出明确结论。
