# 安全边界

这套东西的性质先说清楚：**它是"在旁边看一眼、改点小东西"的工具，不是远程管理方案**。真要长期远程管理，用 SSH + VPN。

---

## 先说清楚 BLE 挡得住什么

广播是公开的：只要树莓派在广播，**任何开了扫描的手机都能看到 `PiBlue`，也都能连上来**。能挡住"连上来"的唯一办法是停止广播，而那样你自己的手机也找不到它。所以真正的关卡在命令层。

## 配对码（挑战-响应）

1. 手机连上 → `hello` → 树莓派回一个一次性随机数 `challenge`
2. 手机用配对码算 `HMAC-SHA256(key, challenge)` 发 `auth`
3. 验证通过前，除了 `hello` / `auth` / `ping`，一律回 `{"ok":false,"code":"unauthorized"}`

配对码是 16 位 base32（约 80 bit），存在 `/etc/piblue/token`（0600），**从不经过 BLE** —— 链路上只有对一次性随机数的 HMAC，录下来也没法重放。连错 5 次锁 60 秒。会话在收到 `hello`、设备断开、或 300 秒无报文时失效；断开信号来自 BlueZ 的 D-Bus 事件（`agent/link_watch.py`），收不到时退化成空闲超时，日志里会写明当前用的是哪一种。

即便如此，这层的意义是"**别人连上也没用**"，不是"别人连不上"。真要隐藏设备，得改 `ble.name` 加个随机后缀，或者干脆在不用的时候 `sudo systemctl stop piblue`。

## 树莓派能从手机拿到什么

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
| 协商 MTU | 能 | 由手机在 `hello` 里自报，BlueZ 不暴露（见 [protocol.md](protocol.md) 的 MTU 分片一节） |
| 型号 / 系统版本 / IP / 手机号 | **不能** | 不在 BLE 链路层里，App 不主动上报就没有 |

结论：**蓝牙地址不能当身份凭证**。它每次可能变、是明文、也能被伪造。要"认设备"只能让 App 主动带一个标识（比如首次启动生成并存本地），但能改 App 的人也能换一个——真正的关卡仍然是配对码。

## `exec` 本身

`exec` 是整条链路上唯一有破坏力的口子，所以默认上了三层锁（`pi-agent/agent/handlers/shell.py`）：

1. **白名单**：只允许 `ls / cat / df / free / uptime / vcgencmd / systemctl / ps / ip …`，`sudo` 会被剥掉再看真实命令
2. **黑名单**：`rm / dd / mkfs / fdisk …` 永远拒绝，`allow_any=true` 时也拒绝
3. **元字符**：默认不允许 `; | & > < $ \``，避免 `ls; rm -rf /` 绕过白名单

注意这三层是**防手滑的护栏，不是沙箱**：`sh` 和 `python3` 在白名单里，而白名单只看行首那个可执行文件，所以 `sh -c '…'`、`python3 -c '…'`、`find . -exec …` 都能绕过去。它们都以 root 身份跑。换句话说：**能过配对认证的人就等于拿到了 root**，配对码就是这台机器的实际口令，别外传。

建议：

* 只在自家网络环境用；不要把 `allow_any` 打开后长期开着服务。
* 手机丢了或配对码泄露：`sudo piblue-token new` 换一个，**再 `sudo systemctl restart piblue`**（码在进程启动时读进内存，不重启新码不生效），然后所有手机都要重填。
* 不想暴露设备时：`sudo systemctl stop piblue`，比改名字可靠。

## 控制页为什么更安全

控制页只发动作名、不发命令行，参数全部在树莓派侧校验，详见 [control.md](control.md#为什么它比-exec-安全)。这也是"能不开 `allow_any` 就别开"的原因：日常操作走控制页，终端页留着应急，白名单不用放宽。
