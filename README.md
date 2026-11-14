# PiBlue · 手机通过蓝牙查看与控制树莓派

一个自洽的最小系统：树莓派用 Python 跑一个 BLE GATT 服务，手机上用 Kotlin 写的原生 App 连上去，读系统状态、读传感器、执行命令。

```
┌──────────────┐   BLE (GATT, 无需配对)   ┌─────────────────────────┐
│  Android App │ ◀══════════════════════▶ │  树莓派                  │
│  Kotlin      │   RX: JSON 命令（分片写）│  bless → GATT server     │
│  原生 View UI│   TX: JSON 回包（notify）│  Agent → handlers        │
└──────────────┘                          │   ├ sysinfo  系统快照    │
                                          │   ├ shell    命令白名单  │
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
│  │  └─ handlers/           sysinfo / shell / sensors
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
│     ├─ PiController.kt     状态与命令，与 UI 解耦
│     └─ MainActivity.kt     原生 View 三页界面
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
5. 装 `piblue.service` 并启动

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
  "shell": {
    "enabled": true,
    "allow_any": false,
    "allow_meta": false,
    "timeout_s": 10,
    "max_output_bytes": 8192
  },
  "sensors": { "onewire": true, "dht": [], "custom": [] },
  "disk": { "path": "/" }
}
```

* `ble.adapter` 留空 = 让 bless 自己选第一个适配器（板载蓝牙就是这个）。只有插了 USB 蓝牙棒、想指定它时才填 `hci1`；填错会报 `Could not locate bluetooth adapter`。
* `ble.name` 改成别的名字，App 里就按新名字找（`BleClient.nameHint` 同步改，或直接靠服务 UUID 匹配，也能找到）。
* `shell.allow_any = true` 等于把 root shell 交给手机，只在完全私人的环境里开。
* `shell.allow_meta = false` 会拦 `; | & > < $ \``，所以 `ps aux | grep x` 默认跑不了。想放开就改 `true`。
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

### 先不写 App，验证链路

这一步别跳过：它把"蓝牙/树莓派的问题"和"App 的问题"分开。

手机装 **nRF Connect**，扫描 → 连接 `PiBlue` → 展开 `6e400001-…` 服务 → 点 TX（`…0003`）右侧三个箭头订阅通知 → 点 RX（`…0002`）上传箭头，输入：

```json
{"id":1,"cmd":"info"}
```

上面立刻会跳出 `{"id":1,"ok":true,"data":{…}}`。通了就说明树莓派侧 OK。

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

### 三个页面

| 页面 | 内容 |
| --- | --- |
| **状态** | SoC 温度、1/5/15 分钟负载、内存与磁盘占用（带进度条）、运行时长、IP、型号/内核/Wi-Fi、供电与降频告警。右上角可开关"每 2 秒实时推送" |
| **传感器** | 自动列出可用传感器（SoC 温度、DS18B20、DHT、自定义脚本）。点卡片单独读一次，右上角刷新读全部 |
| **终端** | 输入命令回车执行，输出区分 stdout / stderr / 退出码；预置了几个常用只读命令的快捷 chip |

---

## 三、命令与安全边界

`exec` 是整条链路上唯一有破坏力的口子，所以默认上了三层锁（`pi-agent/agent/handlers/shell.py`）：

1. **白名单**：只允许 `ls / cat / df / free / uptime / vcgencmd / systemctl / ps / ip …`，`sudo` 会被剥掉再看真实命令
2. **黑名单**：`rm / dd / mkfs / fdisk …` 永远拒绝，`allow_any=true` 时也拒绝
3. **元字符**：默认不允许 `; | & > < $ \``，避免 `ls; rm -rf /` 绕过白名单

建议：只在自家网络环境用；不要把 `allow_any` 打开后长期开着服务；真要远程管理，用 SSH 更合适，蓝牙这套定位是"在旁边看一眼、改点小东西"。

---

## 四、排错速查

| 现象 | 排查 |
| --- | --- |
| **打开后几秒闪退，没连蓝牙也会** | 见下方「启动 5 秒后闪退」一节：`com.google.common.util.concurrent.ListenableFuture` 不在包里，`androidx.profileinstaller` 写 ART profile 时 `VerifyError` |
| App 扫不到设备 | `sudo systemctl status bluetooth` 是否 active；`bluetoothctl show` 是否 `Powered: yes`；`journalctl -u piblue -n 40` 有没有 `advertising as 'PiBlue'`；树莓派是否被别的蓝牙设备连着（一个适配器同时只能做有限的事） |
| 扫到但连不上 / 立刻断开 | 关掉手机蓝牙再打开（清 GATT 缓存）；确认没有别的手机还连着；看 `journalctl -u piblue -f` 有无报错 |
| 连上后一直空白 | 服务 UUID 不匹配 —— 确认树莓派跑的是这个版本的 agent；nRF Connect 能否看到 `6e400001` 服务 |
| **显示"已连接 PiBlue, MTU 517"但每条命令都「树莓派没有响应（超时）」** | 先分清方向：`sudo btmon -w /tmp/bt.log`（或在另一窗口 `sudo btmon`），然后在 App 里点一条命令。日志里 `ATT: Write Request` 之后有 `ATT: Write Response` + `Handle Value Notification` → 链路正常，问题在手机端回调（Android 13+ 只认三参数 `onCharacteristicChanged`，见下）；只有 `Write Request` 没有 `Write Response` → 手机侧写入就卡住了（`BleTimeout`，写入 5 秒超时）；有 Response 没有 Notification → 树莓派收到但没回，看 `journalctl -u piblue -f` 有没有 `rx N bytes` / `<- id=1 cmd=info` / `notify failed` |
| **小命令（`ls`）能出结果，`info` / 长输出一律超时** | 单向超大 notify 被 BlueZ 丢了：ATT 的 notify PDU 不能超过 `MTU-3`，超了 bluetoothd 直接丢弃，D-Bus 那层仍返回成功，所以树莓派日志里连报错都没有。判断方法：`echo '{"id":1,"cmd":"info"}' \| ./venv/bin/python tools/simulator.py` 看响应多少字节，超过 514（MTU 517）就是它。修法已在代码里：手机连上后先发 `hello` 带上 mtu，树莓派按 `mtu-3` 分片（`BleTransport.set_peer_mtu`）；没收到 hello 时按 20 字节保守发 |
| 命令返回 `not in the allow list` | 加进 `shell.allowed`，或临时 `allow_any=true` |
| 命令返回 `shell metacharacters are blocked` | 去掉管道/重定向，或 `allow_meta=true` |
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

* 树莓派端：协议、分帧、三个 handler、订阅推送已用 `tools/simulator.py` 跑通（含白名单/元字符/黑名单拒绝路径、未知命令、非法 JSON、超长行、分片重组与中文跨包边界）。
* 手机端：**已构建成功** —— `dist/PiBlue-debug.apk`（v1.0.1, versionCode 2），AGP 8.5.2 / Kotlin 1.9.24 / compileSdk 34，`aapt dump badging` 校验包名、权限、启动 Activity 均正确。真机首次运行暴露的「启动 5 秒后闪退」已定位并修复（`listenablefuture` 空 jar 导致 `ListenableFuture` 缺类 → `profileinstaller` VerifyError），详见「启动 5 秒后闪退」一节；同时加了全局崩溃落盘，BLE 实际链路仍需真机验证一次。
* 构建时的环境约束已固化在 `app/build.gradle.kts` 里：排除 `kotlin-stdlib-jdk7/jdk8`（1.8+ 已并入 stdlib），把 `collection` / `annotation` 钉到工具链缓存里已有的版本，`listenablefuture` 直接 exclude 并由源码补上接口（见「启动 5 秒后闪退」）。
* `bless` 与 BlueZ 的实际广播行为依赖具体系统版本；`scripts/doctor.sh` 会在装完后给出明确结论。
