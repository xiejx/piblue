# 控制页（`control`）

手机端**不发送命令行**，只发送动作名。动作表固定在 `pi-agent/agent/handlers/control.py`，不在表里的动作不存在；所有参数（SSID、设备路径、自定义命令序号）都由树莓派侧校验后才进 `argv`，且永远不经过 shell。

控制页显示哪些卡片，由树莓派 `status` 的 `control.allow` + 机器能力决定，所以**树莓派上增减动作不需要重装 App**。

---

## 动作表

| 卡片 | 动作 | 背后做的事 |
| --- | --- | --- |
| 电源 | `reboot` / `shutdown` | `systemctl --no-block reboot\|poweroff`，默认延迟 2 秒，先把回包发出去 |
| 服务 | `restart-agent` / `restart-bluetooth` | 用 `systemd-run --on-active=2s` 排在自身 cgroup 之外重启，否则服务会把自己杀掉、回包发不出去 |
| Wi-Fi | `wifi-enable` `wifi-disable` `wifi-scan` `wifi-connect` `wifi-disconnect` | 优先 `nmcli`（Bookworm 默认），没有就用 `wpa_cli`（Bullseye 及更早）；开关同时用 `rfkill` 兜底 |
| 磁盘 | `disk-list` `disk-mount` `disk-unmount` | `lsblk -J` 列分区（未挂载的也列，带「挂载」按钮），每行带**文件系统类型**（`ext4`/`ntfs`/`vfat`…）。点「挂载」时自己填目录名，挂到 `control.mount_root/<名字>`，目录不存在会自动建；**NTFS 用 `mount -t ntfs3`**，其他类型交给 `mount` 自己探测。卸载允许两类：这个目录下的挂载，以及**可移动设备**的挂载（`/media/pi/…` 的 U 盘能安全弹出）；`/`、`/boot`、`/usr` 等关键挂载点一律拒绝 |
| 自定义 | `custom` + `index`，`custom-save` + `items` | 执行第 index 条命令；在 App 里增删改（最多 5 条），保存时整表替换 |

---

## 磁盘：挂载点由你自己取名字

比如挂到 `/mnt/usb`，就在对话框里填 `usb`：

* 名字只能是**单个路径段**：字母数字开头，后面可以是字母、数字、点、下划线和减号，最长 32 个字符。`/`、`..`、空格一律拒绝，所以它永远逃不出 `mount_root`。
* 目录不存在会自动 `mkdir -p`（通过 sudo，dev 模式也能建）。
* 目录**已存在且不是空的**会直接拒绝 —— 在手机上挂载会盖住原有文件，而你在手机上看不出来。
* 设备已经挂在别处（比如桌面自动挂到 `/media/pi/KINGSTON`）时，会要求先卸载再改挂。
* 挂好之后，同一行变成「卸载」，因为 `mount_root` 下的挂载本来就允许卸。
* **文件系统类型在列表里直接显示**（`ext4`、`ntfs`、`vfat`、`exfat`…），挂载对话框也会写一句用哪个驱动。
* **NTFS 显式用 `ntfs3`**：`mount` 在 NTFS 上默认走用户态 `ntfs-3g`，慢，而且精简镜像里常常没装；点名 `ntfs3` 用的是内核驱动（5.15 起内置，Bookworm 就有）。内核没有 ntfs3 时（Bullseye 的 5.10）会自动退回不带 `-t` 的探测，也就是 `ntfs-3g`，所以老系统照样能挂，只是没有加速。
* 其他文件系统**不指定** `-t`：ext4、vfat、exfat 由 `mount` 自己按 superblock 判断，写死反而多一条出错的路。
* Windows 没正常关机时 NTFS 卷会被标记为脏，读写挂载会被拒；对话框里的「只读挂载」就是给这种情况用的，先把文件读出来，再回 Windows 跑一次 `chkdsk`。

> 升级提示：`/etc/piblue/config.json` 不会被 `install.sh` 覆盖。如果你那份里还是 `"mount_root": "/mnt/piblue"`，新版会挂到 `/mnt/piblue/名字`（App 里会显示真实路径）。想用 `/mnt/名字` 就把这行改成 `"/mnt"` 再 `sudo systemctl restart piblue`。`allow` 数组不用动 —— `custom` 在就自动带上 `custom-save`。

---

## 自定义命令（最多 5 条）

命令有两种来源，**App 写入的优先**：

* `custom.json`（和 `config.json` 同目录，服务模式即 `/etc/piblue/custom.json`）——App 增删改写的就是这个文件，原子替换、0644。它存在时以它为准。
* `config.json` 里的 `control.custom` 数组 —— 只作为**首次保存之前的种子**，这样手写的条目照旧能用；第一次在 App 里保存后，这份列表就搬到 `custom.json` 了。

```json
// /etc/piblue/custom.json —— 一般不用手改，App 会写
{ "items": [ { "name": "重启 nginx", "line": "systemctl restart nginx" } ] }
```

想手写也可以，注意三点：`name` 最多 40 字符、`line` 最多 500、总共不超过 5 条。**`custom.json` 是每次执行/刷新状态时读的，改完不用重启服务**；而 `config.json` 里的 `control.custom` 只在启动时加载，改它得 `sudo systemctl restart piblue`。

保存时用**执行时的同一套规则**做预检，写不进去的命令当场拒绝，不会等到你点按钮才报错。这些命令以 root 执行，**不受 `shell.allowed` 白名单限制**；`shell.deny` 里的 `rm`/`dd`/`mkfs` 等仍然拒绝。管道和重定向由 `control.custom_allow_meta`（默认 `true`）控制 —— 自定义命令是你自己写一次的固定操作，和手写在配置里等价；不想放开就改成 `false`。`control.allow_custom_edit = false` 可以让 App 只能执行、不能改。

---

## Wi-Fi

* 密码只经蓝牙传给树莓派，不落手机存储；但 `nmcli` 会把密码放在自己的参数里执行，树莓派上短暂的 `ps` 能看到 —— 单用户机器可接受，多用户机器请当作已知项。
* `status` 的 `wifi.saved` 会带上树莓派已保存的 SSID（**只有名字，永远不含密码**），App 在列表里标「已保存」并加粗变色，点一下直接发不带密码的 `wifi-connect`，树莓派用自己存档里的密码激活。扫描没扫到的已保存网络也会列在最后（标「未扫描到」）。
* 「关掉再打开」不只是 `nmcli radio wifi on`：NetworkManager 会给"被手动断开过"的设备打标记，标记存在时 autoconnect 全被压住，所以 `wifi-enable` 会补一条 `nmcli device connect wlan0`，按优先级挑一个已保存的网络重新激活。

---

## 为什么它比 `exec` 安全

控制页走的是另一条路，它把"能做什么"从**字符串**换成了**枚举**：

* 动作名固定，未知动作直接拒绝；手机永远发不出命令行。
* 参数只当数据用：`ssid` 校验长度与字符集，`device` 必须是 `lsblk` 里真实存在的那一条，`index` 必须是 `0-4`。
* 一律 `create_subprocess_exec`，不经过 `sh`，所以没有 `;`、`$(…)`、`反引号` 这些事。
* 挂载点由手机发**目录名**（校验为单个路径段）而不是路径，树莓派侧用 `posixpath.join(mount_root, 名字)` 拼出来，`..` 出不去。
* 危险动作要二次确认：`reboot` / `shutdown` 必须带 `confirm: true`，而且 App 自己也会先弹一次 —— 两道，防的是口袋里误触。
* 卸载有范围限制：`control.mount_root` 下的挂载、以及可移动设备的挂载；`/`、`/boot`、`/boot/firmware`、`/usr`、`/var`、`/etc` 这些关键挂载点写在代码里的黑名单中，`lsblk` 的 `RM` 位即使标错也卸不掉。

它**不能替代 `exec`**：临时想看点什么，还得去终端页。但"每天要按几次的那几个操作"放在控制页，就不必再给终端开 `allow_any` 了。

---

## 两条刻意没做的

**格式化分区**和**分区表操作**。这两个操作没有撤销，隔着 30 米蓝牙点错一次就是数据没了，要格式化请用 SSH。

相关：[security.md](security.md)（整体安全边界）、[troubleshooting.md](troubleshooting.md)（挂载/Wi-Fi 报错对照）。
