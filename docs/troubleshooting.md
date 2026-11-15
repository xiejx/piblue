# 排错速查

先跑一遍体检，很多问题它能直接给结论：

```bash
sudo ./scripts/doctor.sh
```

下面的条目按现象分组。表格里点不开的东西（UUID、配对码路径、MTU 计算）在 [protocol.md](protocol.md) 和 [agent-install.md](agent-install.md)。

---

## 连接与配对

| 现象 | 排查 |
| --- | --- |
| App 扫不到设备 | `sudo systemctl status bluetooth` 是否 active；`bluetoothctl show` 是否 `Powered: yes`；`journalctl -u piblue -n 40` 有没有 `advertising as 'PiBlue'`；树莓派是否被别的蓝牙设备连着（一个适配器同时只能做有限的事） |
| 扫到但连不上 / 立刻断开 | 关掉手机蓝牙再打开（清 GATT 缓存）；确认没有别的手机还连着；看 `journalctl -u piblue -f` 有无报错 |
| 连上后一直空白 | 服务 UUID 不匹配 —— 确认树莓派跑的是这个版本的 agent；nRF Connect 能否看到 `6e400001` 服务 |
| **状态栏显示"已连接（未配对）"，命令一律没反应** | 配对码没填或填错。App 会自己弹出输入框；也可以断开重连，在扫描窗口的输入框里重填。树莓派侧日志：`wrong pairing code, N attempt(s) left`→ 码不对；`too many wrong codes` → 被锁 60 秒，等一会儿；`this Pi has no pairing code yet` → `/etc/piblue/token` 不存在，跑一次 `install.sh` 或 `sudo /opt/piblue/venv/bin/python -m agent.token new` |
| 树莓派日志刷 `bluez reports the central is gone` | 正常：每次断开都会让会话作废，手机重连后会自己重新认证一次 |
| 中文乱码 | 不该出现：手机端按字节累积、遇到换行才 UTF-8 解码。若出现，检查是否有人改了 `BleClient.emitFrames` 的分片逻辑 |

---

## 链路上通了，但命令超时

这一类最难查，两条命令能定位方向。

| 现象 | 排查 |
| --- | --- |
| **显示"已连接 PiBlue, MTU 517"但每条命令都「树莓派没有响应（超时）」** | 先分清方向：`sudo btmon -w /tmp/bt.log`（或在另一窗口 `sudo btmon`），然后在 App 里点一条命令。日志里 `ATT: Write Request` 之后有 `ATT: Write Response` + `Handle Value Notification` → 链路正常，问题在手机端回调（Android 13+ 只认三参数 `onCharacteristicChanged`）；只有 `Write Request` 没有 `Write Response` → 手机侧写入就卡住了（`BleTimeout`，写入 5 秒超时）；有 Response 没有 Notification → 树莓派收到但没回，看 `journalctl -u piblue -f` 有没有 `rx N bytes` / `<- id=1 cmd=info` / `notify failed` |
| **小命令（`ls`）能出结果，`info` / 长输出一律超时** | 单向超大 notify 被 BlueZ 丢了：ATT 的 notify PDU 不能超过 `MTU-3`，超了 bluetoothd 直接丢弃，D-Bus 那层仍返回成功，所以树莓派日志里连报错都没有。判断方法：`echo '{"id":1,"cmd":"info"}' \| ./venv/bin/python tools/simulator.py` 看响应多少字节，超过 514（MTU 517）就是它。修法已在代码里：手机连上后先发 `hello` 带上 mtu，树莓派按 `mtu-3` 分片（`BleTransport.set_peer_mtu`）；没收到 hello 时按 20 字节保守发 |

---

## 终端与命令白名单

| 现象 | 排查 |
| --- | --- |
| 命令返回 `not in the allow list` | 加进 `shell.allowed`，或临时 `allow_any=true` |
| 命令返回 `shell metacharacters are blocked` | 去掉管道/重定向，或 `allow_meta=true` |
| 自定义命令里有管道/重定向却报 `metacharacters are blocked` | 把 `control.custom_allow_meta` 改回 `true`（默认就是 true）。它被关掉时，自定义命令和终端页一样不许 `;` `\|` `&` `>` `<` |
| 保存自定义命令报 `会被拒绝：'rm' is on the deny list` | 预检按执行规则跑了一遍。`rm`/`dd`/`mkfs`/`shred` 等在 `shell.deny` 里，改命令本身，或从 deny 列表里删掉那项（不建议） |

---

## 控制页与 Wi-Fi

| 现象 | 排查 |
| --- | --- |
| 控制页一直停在"正在读取树莓派的可控项…" | `control` 的 `status` 没回。`journalctl -u piblue -f` 看有没有 `<- id=N cmd=control`；`exec` 能通而它不通，多半是 `control.enabled=false` 且页面在等 `actions` 列表 |
| 控制页少了整张卡片 | 正常：卡片是按 `control.allow` 和机器能力生成的。删掉了对应动作、没有 `nmcli`/`wpa_cli`、没有 `lsblk`，那一块就不会出现。`status` 的 `errors.disk` 会说明原因 |
| 控制页报 `无法执行 systemctl` | agent 不是 root（`dev.sh` 起的），`sudo -n` 又没有免密。用 `install.sh` 装成服务，或给该用户配 NOPASSWD |
| **控制页没有「添加命令」按钮** | 说明树莓派那边的 agent 还是 1.2.x（`status` 里没有 `custom-save`），或者 `control.allow_custom_edit=false`，或者已经存满 5 条。连上一眼日志里的 `配对通过 · agent x.x.x` 最快 |
| 点「重启 agent」后立刻收到超时 | 预期：服务 2 秒后重启，蓝牙会断。等它回来重连即可；如果 30 秒还没广播，说明 unit 起不来，去 SSH 看 `systemctl status piblue` |
| **连 Wi-Fi 报 `802-11-wireless-security.key-mgmt: property is missing`** | 不是密码错，是 libnm 收到了一份没写 `key-mgmt` 的 Wi-Fi 配置。根因是旧实现走 `nmcli dev wifi connect`，它在扫描缓存里读不到该 AP 的加密类型（隐藏网络、缓存过期、只扫到别人家同名 AP）时就只写 SSID 和 psk，`key-mgmt` 留空 → 整个 profile 被判非法。现在的实现改成显式 `connection add` + `connection modify wifi-sec.key-mgmt <wpa-psk/sae/none>`，不再依赖扫描猜测。如果树莓派上已经留下坏配置，`nmcli connection delete <ssid>` 删掉重连即可（新代码也会复用并修正同名 profile） |
| **关掉 Wi-Fi 再打开，不会自己连回去** | 只执行 `nmcli radio wifi on` 是**不够的**：NetworkManager 会给"被手动断开过"的设备打一个标记（控制页的「断开」按钮就会打上），标记存在时所有 autoconnect 都被压住。所以 `wifi-enable` 现在会补一条 `nmcli device connect wlan0`，清掉标记并按优先级挑一个已保存的网络重新激活。只有已经保存过密码的网络才会被自动连回去——没保存过的网络无从知道密码 |
| **连过的网络，再连还要输一次密码** | 现在不用了。`status` 的 `wifi.saved` 会带上树莓派已保存的 SSID（只有名字，永远不含密码），App 在列表里标「已保存」并加粗变色，点一下直接发不带密码的 `wifi-connect`，树莓派用自己存档里的密码激活。扫描没扫到的已保存网络也会列在最后（标「未扫描到」），所以断网重连这件事不再依赖"必须扫到它" |
| 想在终端里核对一下已保存的网络 | `nmcli -t -f NAME,TYPE connection show`（所有 profile）、`nmcli -g 802-11-wireless.ssid connection show <名字>`（这个 profile 是哪个 SSID）、`nmcli device wifi list`（带信号的当前列表）。`con up` 一个已有 profile 就等于"用保存的密码连接" |

---

## 磁盘与挂载

| 现象 | 排查 |
| --- | --- |
| 挂载报 `unknown filesystem type` | 系统没装这种文件系统的驱动：`sudo apt install exfat-fuse ntfs-3g` |
| **NTFS 挂载报 `unknown filesystem type 'ntfs3'`** | 内核没有 ntfs3 模块（5.15 以下，或 `CONFIG_NTFS3_FS` 没开）。新版会自动退回不带 `-t` 的挂载（`ntfs-3g`），如果连它也没装：`sudo apt install ntfs-3g` |
| **NTFS 挂载报 `Volume is dirty`** | Windows 没正常关机/休眠。勾上对话框里的「只读挂载」先把文件读出来，回 Windows 跑 `chkdsk /f` 之后再读写挂 |
| **插了 U 盘，磁盘卡片里看不到它** | 先点「刷新」（卡片不会自己轮询）。还是没有就看 `lsblk -o NAME,PATH,TYPE,FSTYPE,LABEL,MOUNTPOINT,RM`：整盘没分区表（superfloppy）也能挂，会显示成 `未识别到文件系统`；`swap`/`crypto_LUKS`/`LVM2_member` 以及 `mmcblk0boot0`、`loop*` 这类伪设备是刻意过滤掉的 |
| **磁盘列表里只有"已挂载"，没有「挂载」按钮** | 两种情况：① 分区已经被桌面自动挂到 `/media/pi/…` 了 —— 这时该行给的是「卸载」，卸掉之后同一行就会变成「`exfat` 未挂载」并出现「挂载」，挂到 `/mnt/<你填的名字>`；② 分区的 `FSTYPE` 是空的（没格式化），没有任何东西可挂。控制页现在会把 Pi 返回的每一条都列出来，所以不会再出现"明明有盘却一片空白" |
| 想卸载 `/media/pi/xxx` 却被拒 | 现在的规则是：`control.mount_root`（默认 `/mnt`）下的挂载、以及**可移动设备**的挂载可以卸；`/`、`/boot`、`/boot/firmware`、`/usr`、`/var` 等关键挂载点一律拒绝，哪怕 `lsblk` 的 `RM` 位标错了 |
| 卸载报 `正在被使用` | `lsof +D /mnt/xxx` 找出占用者，关掉再卸 |
| 挂载报 `… 已存在且不是空目录` | 那个目录里有东西。挂上去会把这些文件盖住，而且手机上看不出来，所以直接拒绝。换个名字，或先在 SSH 里清空/改名 |
| 挂载报 `要先卸载才能改挂到 …` | 这块盘已经被挂到别处（多半是桌面自动挂的 `/media/pi/…`）。先点「卸载」，再点「挂载」填新名字 |

---

## 状态页与传感器

| 现象 | 排查 |
| --- | --- |
| 状态页时间显示"未同步 NTP" | 树莓派没连上网或 NTP 被挡住，`timedatectl show -p NTPSynchronized` 会返回 `no`。没有 RTC 的型号断电后会从旧时间继续走，表现为 TLS/apt 报证书错误。`sudo systemctl restart systemd-timesyncd` 或临时 `sudo date -s` 都行；有网络时会自己校正 |
| 温度显示 `—` | 树莓派上没有 `vcgencmd`（非官方系统）且 thermal zone 读不到，属正常降级 |
| 传感器列表只有 SoC 温度 | 没接探头，或 1-Wire 没开（`dtoverlay=w1-gpio`），或 DHT 没装 `adafruit-circuitpython-dht` |

---

## 安装与构建

| 现象 | 排查 |
| --- | --- |
| **打开后几秒闪退，没连蓝牙也会** | 见下方「启动 5 秒后闪退」一节：`com.google.common.util.concurrent.ListenableFuture` 不在包里，`androidx.profileinstaller` 写 ART profile 时 `VerifyError` |
| **`./scripts/dev.sh: line 15: set: pipefail: invalid option name`**（或任何 `\r` 相关的怪错） | 脚本被存成了 Windows 行尾（CRLF），bash 把 `pipefail\r` 当成选项名，还没跑到第一行逻辑就退出了。在树莓派上一次性修掉：`grep -rlU $'\r' ~/pi-agent --include='*.sh' --include='*.py' \| xargs sed -i 's/\r$//'`。仓库已加 `.gitattributes`（`* text=auto eol=lf`），git 拉取不会再转回 CRLF；但用 scp / 共享目录 / 复制粘贴直接拷文件时仍会复发，改完确认一下：`head -1 ~/pi-agent/scripts/dev.sh \| od -c \| head -2` 里不应该出现 `\r` |
| 构建报 `No cached version ... available for offline mode` | 工具链缓存里缺这个 artifact。去掉 `--offline` 让 Gradle 联网取一次，或在 `app/build.gradle.kts` 的 `resolutionStrategy` 里 force 到已缓存版本 |

---

## 启动 5 秒后闪退（真机踩到的第一个坑，已修）

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
