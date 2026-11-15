# 手机端：安装与构建

## 现成的包

仓库里已经有一个构建好的调试包：`dist/PiBlue-debug.apk`（App 1.3.2 / versionCode 7）。开 USB 调试后：

```bash
"/d/git-workspace/casting-android/.toolchain/sdk/platform-tools/adb" \
  install -r dist/PiBlue-debug.apk
```

也可以把 APK 传到手机上直接安装（需要允许"安装未知来源应用"）。

---

## 自己构建

已用本机工具链 `D:\git-workspace\casting-android\.toolchain`（JDK 17 + SDK 34 + Gradle 8.7）**离线构建成功**。

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

也可以用 Android Studio 打开 `android/` 目录直接 Run（wrapper 已生成，Gradle JDK 选 17）。

最低支持 Android 7.0（API 24），目标 API 34。

### 为什么 UI 是原生 View 而不是 Compose

这台机器到 Google Maven 的大文件下载完全不通（实测 60 秒 0 字节，`Read timed out`），Compose 那几十个 artifact 拉不下来，构建必然失败。改用框架自带的 LinearLayout / TextView / ListView 后，依赖只剩 `core-ktx` + `coroutines`，全部命中工具链缓存，可以完全离线构建。功能与交互和原设计一致。

### 构建环境约束

这些已经固化在 `app/build.gradle.kts` 里，别随手删：

* 排除 `kotlin-stdlib-jdk7` / `kotlin-stdlib-jdk8`（1.8+ 已并入 stdlib）；
* 把 `collection` / `annotation` 钉到工具链缓存里已有的版本；
* `com.google.guava:listenablefuture` 直接 `exclude`，接口由源码补上（`app/src/main/java/com/google/common/util/concurrent/ListenableFuture.java`）—— 原因和踩坑过程见 [troubleshooting.md](troubleshooting.md) 的「启动 5 秒后闪退」。

---

## 权限

* Android 12+：`BLUETOOTH_SCAN`（声明 `neverForLocation`）+ `BLUETOOTH_CONNECT`
* Android 11 及以下：`ACCESS_FINE_LOCATION`（老系统的 BLE 扫描被归到定位权限里）

App 只在第一次点"扫描并连接"时申请。

---

## 四个页面

| 页面 | 内容 |
| --- | --- |
| **状态** | SoC 温度、1/5/15 分钟负载、内存与磁盘占用（带进度条）、运行时长、**树莓派时间**（含时区、与手机的偏差、NTP 是否同步过）、IP、型号/内核/Wi-Fi、供电与降频告警。右上角可开关"每 2 秒实时推送"，开着时时间跟着一起走 |
| **传感器** | 自动列出可用传感器（SoC 温度、DS18B20、DHT、自定义脚本）。点卡片单独读一次，右上角刷新读全部 |
| **控制** | 重启 / 关机、重启 agent 与蓝牙服务、Wi-Fi 开关与连接（扫描附近网络、已保存的网络可直接点）、磁盘列表与挂载（每行标出**文件系统类型**，挂载目录名自己填，NTFS 走 `ntfs3`，卸掉自己挂的）、最多 5 条自定义命令（可在 App 里增删改）。**按钮由树莓派下发**，不是写死在 App 里 |
| **终端** | 输入命令回车执行，输出区分 stdout / stderr / 退出码；预置了几个常用只读命令的快捷 chip |

控制页的按钮由 `control` 的 `status` 驱动，所以**树莓派上增减动作不需要重装 App**，最坏情况只是新动作没有中文标签（会按动作名显示）。控制页能做什么见 [control.md](control.md)。

---

## 代码在哪

```
android/
├─ build-apk.sh                       命令行构建（走本机工具链，可离线）
└─ app/src/main/java/com/raspi/piblue/
   ├─ ble/BleClient.kt                扫描 / 连接 / 分片写 / 按字节重组
   ├─ data/Models.kt                  JSON → 数据类
   ├─ data/Control.kt                 控制页的数据类（动作/磁盘/Wi-Fi）
   ├─ PiController.kt                 状态与命令，与 UI 解耦
   └─ MainActivity.kt                 原生 View 四页界面
```
