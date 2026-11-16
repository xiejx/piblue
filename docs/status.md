# 状态、验证与后续

这个项目现在是**能用但没在真机上跑完整遍**的状态。哪些验证过、哪些没有，下面写清楚，别把"模拟器通过"当成"真机可用"。

---

## 树莓派端

* 协议、分帧、四个 handler、订阅推送已用 `tools/simulator.py` 跑通（含白名单/元字符/黑名单拒绝路径、未知命令、非法 JSON、超长行、分片重组与中文跨包边界）。
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
* NTFS 属主（`control.mount_owner`，默认 `pi`，工作区改动、未发版）在开发机上验证过：`mount_options` 的四种组合（ntfs 读写 → `['uid=1000','gid=1000']`、ntfs 只读 → `['ro','uid=..','gid=..']`、ext4 → 空、`mount_owner:""` → 空）；`_resolve_owner` 的退化路径（没有 `pwd` 的 Windows 上非数字返回 `None`、纯数字 `1000` 直接当 uid/gid 用）；用假 runner + 假 `lsblk` 干跑 `_disk_mount`，argv 为 `mount -t ntfs3 -o uid=1000,gid=1000 /dev/sda1 /mnt/usb`、只读为 `-o ro,uid=1000,gid=1000`，ext4 仍是不带 `-t` 的老 argv，回包带 `owner`。**没验证的是**真机上的 `pwd.getpwnam("pi")` 结果，以及挂上去之后 `pi` 是否真的能写（要在 `findmnt -no OPTIONS` 里看到 uid/gid 才算数）。
* agent 1.3.2 修的是状态页 Wi-Fi 后面永远显示 `0/70`：`iwgetid` **没有** `--signal` 选项，那条命令一直退 1，`signal` 恒为 `null`，被 App 的 `?: 0` 印成 `0`。现在读 `/proc/net/wireless` 的 link quality 列，用一台真机（`xqx688`，`wlan0: 0000   61.  -49.  ...`）的原始输出核对过解析结果为 `{'ssid': 'xqx688', 'signal': 61}`，空表头/驱动填 0 两种退化输入也跑过。**没验证的是**：把新代码装到 `/opt/piblue` 之后服务进程的实际返回值。排查过程见 [troubleshooting.md](troubleshooting.md) 的「状态页与传感器」。
* `bless` 与 BlueZ 的实际广播行为依赖具体系统版本；`scripts/doctor.sh` 会在装完后给出明确结论。

---

## 手机端

* **能出包** —— `./gradlew assembleDebug` 产出 `android/app/build/outputs/apk/debug/app-debug.apk`；推 `v*` tag 后由 GitHub Actions 自动构建并挂到 [Releases](https://github.com/xiejx/piblue/releases)。构建栈：JDK 17 / Gradle 8.7 / AGP 8.5.2 / Kotlin 1.9.24 / compileSdk 34。1.3.2 的包做过 `aapt dump badging` 校验（包名、权限、启动 Activity 均正确）与 `aapt2 dump resources`（确认 `custom_add`/`custom_hint` 在包里）。
* **CI 已实测跑通**（`v1.3.3`，run #2，1 分 51 秒全绿）：`PiBlue-1.3.3-debug.apk` 2,177,687 B 挂在 Releases 上，`.sha256` 为 `935eef88…`，与 GitHub 对附件自身算出的 digest 一致。首次尝试（run #1）挂在 `android-actions/setup-android@v3`，改成本地镜像自带的 SDK 后通过 —— 详见 [android.md](android.md#打-tag-自动出包)。
* 真机首次运行暴露的「启动 5 秒后闪退」已定位并修复（`listenablefuture` 空 jar 导致 `ListenableFuture` 缺类 → `profileinstaller` VerifyError），详见 [troubleshooting.md](troubleshooting.md#启动-5-秒后闪退真机踩到的第一个坑已修)；同时加了全局崩溃落盘。
* 1.3.2 修的是终端页输入框「打一两个字母就失焦、软键盘收起」：根因是 `renderTerminal()` 每收到一次 `summary` 推送（2 s 一次）就调 `termScroll.fullScroll(FOCUS_DOWN)`，而 `fullScroll` 的语义不只是滚到底，还会**把焦点交给新可视区域里的可聚焦视图**（`textIsSelectable` 的输出 TextView，或 ScrollView 自己），于是输入框被反复夺焦。现在：输出区仅在终端内容真的变化时才重绘（按列表实例比对），滚动改用 `scrollTo`（只移像素不碰焦点），并且只在用户本来就停在底部时跟随新输出（往上翻着看 `dmesg` 不会被拽回底部）；`term_scroll` 在 XML 里显式 `focusable=false`，`sendCommand()` 发送后把焦点留在输入框。
* 1.3.3 补的是控制页自定义命令「点了执行却看不到结果」：树莓派端 `_custom` 一直在回包里带 `stdout` / `stderr` / `code` / `timed_out` / `duration_ms`（与 `exec` 同构），但 App 的 `runAction` 只取了 `message` 就丢掉其余字段，用户只看到一句「nginx：完成」。现在 `runAction` 多了 `onData` 回调与 `announce` 开关，执行完弹结果对话框（等宽、可选中、可复制，超长时限高滚动）。字段用 `tools/simulator.py` 核对过：成功（`echo` → `code 0` + stdout）与失败（不存在的命令 → `code 1` + stderr）两种回包都与 `Json.exec` 对得上。
* 控制页的按钮由 `control` 的 `status` 驱动，所以**树莓派上增减动作不需要重装 App**，最坏情况只是新动作没有中文标签（会按动作名显示）。
* 构建时的依赖约束已固化在 `app/build.gradle.kts` 里，见 [android.md](android.md#依赖约束别随手删)。

**仍需真机验证的**：BLE 实际链路（`btmon` 那一套）、NTFS 真机挂载、1.3.2 之后的终端连续输入，以及 1.3.3 的命令结果对话框（模拟器只验证了回包，没在真机上点过按钮）。

---

## 后续可以往下长的地方

* **加 GPIO 控制**：在 `handlers/` 加个 `gpio.py`，写读写的两个函数，然后在 `agent.py` 的 `self._handlers` 里注册 `"gpio_set"` / `"gpio_get"`，App 端加一页即可 —— 协议不用动。
* **加推送 topic**：`_sub_loop` 里加分支，App 端 `handleEvent` 加一个 `case`。
* **多设备**：`BleClient` 现在只保持一个 GATT 连接；要同时连多台就把 `gatt` 改成按地址索引的 map，命令里带设备 id。注意先在扫描列表上解决"同名设备"的辨认（见 [agent-install.md](agent-install.md#有多台树莓派)）。
* **做成一键开机自启**：跑一次 `install.sh` 即可（`systemctl enable`），断电重启后自动上线。调试期不用装，用 `dev.sh` 前台跑就行。
