package com.raspi.piblue

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SimpleAdapter
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.raspi.piblue.ble.BleClient
import com.raspi.piblue.data.ControlStatus
import com.raspi.piblue.data.CustomAction
import com.raspi.piblue.data.DiskEntry
import com.raspi.piblue.data.ExecResult
import com.raspi.piblue.data.Json
import com.raspi.piblue.data.Pairing
import com.raspi.piblue.data.SensorMeta
import com.raspi.piblue.data.SensorReading
import com.raspi.piblue.data.SysInfo
import com.raspi.piblue.data.WifiNetwork
import com.raspi.piblue.ui.formatGb
import com.raspi.piblue.ui.formatNumber
import com.raspi.piblue.ui.formatPct
import com.raspi.piblue.ui.formatTemp
import com.raspi.piblue.ui.formatUptime
import org.json.JSONObject
import java.util.Locale

class MainActivity : Activity(), PiController.Listener {

    private lateinit var controller: PiController
    private var tab = 0

    // header
    private lateinit var statusDot: TextView
    private lateinit var statusText: TextView
    private lateinit var btnHeader: TextView

    // pages
    private lateinit var pageStatus: ScrollView
    private lateinit var pageSensors: LinearLayout
    private lateinit var pageControl: ScrollView
    private lateinit var pageTerminal: LinearLayout
    private lateinit var tabs: List<TextView>

    // status
    private lateinit var banner: View
    private lateinit var bannerText: TextView
    private lateinit var switchAuto: Switch
    private lateinit var tempValue: TextView
    private lateinit var loadValue: TextView
    private lateinit var loadDetail: TextView
    private lateinit var memPct: TextView
    private lateinit var memBar: ProgressBar
    private lateinit var memDetail: TextView
    private lateinit var diskTitle: TextView
    private lateinit var diskPct: TextView
    private lateinit var diskBar: ProgressBar
    private lateinit var diskDetail: TextView
    private lateinit var uptimeValue: TextView
    private lateinit var ipTitle: TextView
    private lateinit var ipValue: TextView
    private lateinit var infoHostname: TextView
    private lateinit var infoModel: TextView
    private lateinit var infoKernel: TextView
    private lateinit var infoCores: TextView
    private lateinit var timeValue: TextView
    private lateinit var timeDetail: TextView
    private lateinit var timeSync: TextView
    private lateinit var infoWifi: TextView
    private lateinit var warnCard: View
    private lateinit var warnText: TextView

    // sensors
    private lateinit var sensorCount: TextView
    private lateinit var sensorEmpty: TextView
    private lateinit var sensorContainer: LinearLayout

    // terminal
    private lateinit var termScroll: ScrollView
    private lateinit var termOutput: TextView
    private lateinit var cmdInput: EditText

    /** Transcript the output view was last built from, so idle ticks cost nothing. */
    private var termDrawn: List<TermLine>? = null

    // control
    private lateinit var controlEmpty: TextView
    private lateinit var controlCards: LinearLayout
    private lateinit var powerCard: View
    private lateinit var btnReboot: TextView
    private lateinit var btnShutdown: TextView
    private lateinit var serviceCard: View
    private lateinit var btnRestartAgent: TextView
    private lateinit var btnRestartBt: TextView
    private lateinit var wifiCard: View
    private lateinit var wifiBadge: TextView
    private lateinit var wifiDetail: TextView
    private lateinit var btnWifiToggle: TextView
    private lateinit var btnWifiScan: TextView
    private lateinit var btnWifiDisconnect: TextView
    private lateinit var wifiList: LinearLayout
    private lateinit var diskCard: View
    private lateinit var diskHint: TextView
    private lateinit var diskContainer: LinearLayout
    private lateinit var customCard: View
    private lateinit var customContainer: LinearLayout
    private lateinit var customHint: TextView
    private lateinit var customAdd: TextView

    private var scanDialog: AlertDialog? = null
    private var scanListView: ListView? = null
    private var pairingDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        controller = PiController(applicationContext)
        controller.listener = this

        bindViews()
        wireActions()
        selectTab(0)
        showLastCrashIfAny()
    }

    private fun bindViews() {
        statusDot = findViewById(R.id.status_dot)
        statusText = findViewById(R.id.status_text)
        btnHeader = findViewById(R.id.btn_header)

        pageStatus = findViewById(R.id.page_status)
        pageSensors = findViewById(R.id.page_sensors)
        pageControl = findViewById(R.id.page_control)
        pageTerminal = findViewById(R.id.page_terminal)
        tabs = listOf(
            findViewById(R.id.tab_status),
            findViewById(R.id.tab_sensors),
            findViewById(R.id.tab_control),
            findViewById(R.id.tab_terminal),
        )

        banner = findViewById(R.id.banner)
        bannerText = findViewById(R.id.banner_text)
        switchAuto = findViewById(R.id.switch_auto)
        tempValue = findViewById(R.id.temp_value)
        loadValue = findViewById(R.id.load_value)
        loadDetail = findViewById(R.id.load_detail)
        memPct = findViewById(R.id.mem_pct)
        memBar = findViewById(R.id.mem_bar)
        memDetail = findViewById(R.id.mem_detail)
        diskTitle = findViewById(R.id.disk_title)
        diskPct = findViewById(R.id.disk_pct)
        diskBar = findViewById(R.id.disk_bar)
        diskDetail = findViewById(R.id.disk_detail)
        uptimeValue = findViewById(R.id.uptime_value)
        ipTitle = findViewById(R.id.ip_title)
        ipValue = findViewById(R.id.ip_value)
        infoHostname = findViewById(R.id.info_hostname)
        infoModel = findViewById(R.id.info_model)
        infoKernel = findViewById(R.id.info_kernel)
        infoCores = findViewById(R.id.info_cores)
        timeValue = findViewById(R.id.time_value)
        timeDetail = findViewById(R.id.time_detail)
        timeSync = findViewById(R.id.time_sync)
        infoWifi = findViewById(R.id.info_wifi)
        warnCard = findViewById(R.id.warn_card)
        warnText = findViewById(R.id.warn_text)

        sensorCount = findViewById(R.id.sensor_count)
        sensorEmpty = findViewById(R.id.sensor_empty)
        sensorContainer = findViewById(R.id.sensor_container)

        termScroll = findViewById(R.id.term_scroll)
        termOutput = findViewById(R.id.term_output)
        cmdInput = findViewById(R.id.cmd_input)

        controlEmpty = findViewById(R.id.control_empty)
        controlCards = findViewById(R.id.control_cards)
        powerCard = findViewById(R.id.power_card)
        btnReboot = findViewById(R.id.btn_reboot)
        btnShutdown = findViewById(R.id.btn_shutdown)
        serviceCard = findViewById(R.id.service_card)
        btnRestartAgent = findViewById(R.id.btn_restart_agent)
        btnRestartBt = findViewById(R.id.btn_restart_bt)
        wifiCard = findViewById(R.id.wifi_card)
        wifiBadge = findViewById(R.id.wifi_badge)
        wifiDetail = findViewById(R.id.wifi_detail)
        btnWifiToggle = findViewById(R.id.btn_wifi_toggle)
        btnWifiScan = findViewById(R.id.btn_wifi_scan)
        btnWifiDisconnect = findViewById(R.id.btn_wifi_disconnect)
        wifiList = findViewById(R.id.wifi_list)
        diskCard = findViewById(R.id.disk_card)
        diskHint = findViewById(R.id.disk_hint)
        diskContainer = findViewById(R.id.disk_container)
        customCard = findViewById(R.id.custom_card)
        customContainer = findViewById(R.id.custom_container)
        customHint = findViewById(R.id.custom_hint)
        customAdd = findViewById(R.id.custom_add)
    }

    private fun wireActions() {
        btnHeader.setOnClickListener {
            if (controller.isConnected) controller.disconnect() else beginScan()
        }
        findViewById<View>(R.id.btn_banner_scan).setOnClickListener { beginScan() }
        findViewById<View>(R.id.btn_refresh).setOnClickListener { controller.refreshInfo() }
        findViewById<View>(R.id.btn_sensor_refresh).setOnClickListener { controller.refreshSensors() }

        switchAuto.setOnCheckedChangeListener { _, checked ->
            if (checked != controller.autoRefresh) controller.setAutoRefresh(checked)
        }

        tabs.forEachIndexed { index, view ->
            view.setOnClickListener { selectTab(index) }
        }

        findViewById<View>(R.id.btn_send).setOnClickListener { sendCommand() }
        cmdInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendCommand()
                true
            } else {
                false
            }
        }

        wireControl()
        buildChips()
    }

    /**
     * Every button here maps to one entry in the Pi's `control.allow` list. The
     * two irreversible ones ask first, and the Pi asks again (`confirm: true`)
     * before it acts - a stray tap in a pocket should not power off a machine.
     */
    private fun wireControl() {
        btnReboot.setOnClickListener {
            confirmAction(
                "重启树莓派",
                "重启会断开蓝牙，大约 30 秒后重新出现在扫描列表里。正在写入的数据可能还没落盘。",
                "重启",
            ) { controller.runAction("reboot") }
        }
        btnShutdown.setOnClickListener {
            confirmAction(
                "关机",
                "关机之后只能到现场断电再上电才能唤醒。确定现在关机？",
                "关机",
            ) { controller.runAction("shutdown") }
        }
        btnRestartAgent.setOnClickListener {
            confirmAction(
                "重启 agent",
                "改了 /etc/piblue/config.json 之后用这个生效。蓝牙会断开几秒。",
                "重启",
            ) { controller.runAction("restart-agent") }
        }
        btnRestartBt.setOnClickListener {
            confirmAction(
                "重启蓝牙服务",
                "所有已连接的设备都会断开，包括这台手机。只在扫不到设备时用。",
                "重启",
            ) { controller.runAction("restart-bluetooth") }
        }

        btnWifiToggle.setOnClickListener {
            val enabled = controller.control?.wifi?.enabled ?: true
            // Enabling now waits for NetworkManager to bring a saved profile
            // back up (up to ~20 s), so it needs more room than the default.
            controller.runAction(if (enabled) "wifi-disable" else "wifi-enable", 45_000L)
        }
        btnWifiScan.setOnClickListener { controller.scanWifi() }
        btnWifiDisconnect.setOnClickListener {
            confirmAction(
                "断开 Wi-Fi",
                "如果这是树莓派唯一的网络，断开后它就上不了网了（蓝牙不受影响）。",
                "断开",
            ) { controller.runAction("wifi-disconnect") }
        }

        findViewById<View>(R.id.btn_disk_refresh).setOnClickListener { controller.refreshControl() }
    }

    private fun buildChips() {
        val row = findViewById<LinearLayout>(R.id.chip_row)
        QUICK_COMMANDS.forEach { command ->
            val chip = TextView(this)
            chip.text = command
            chip.textSize = 11f
            chip.setTextColor(ContextCompat.getColor(this, R.color.accent_dark))
            chip.background = ContextCompat.getDrawable(this, R.drawable.chip_bg)
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.rightMargin = dp(8)
            chip.layoutParams = lp
            chip.setPadding(dp(12), dp(6), dp(12), dp(6))
            chip.isClickable = true
            chip.setOnClickListener {
                selectTab(3)
                controller.runCommand(command)
            }
            row.addView(chip)
        }
    }

    private fun sendCommand() {
        val text = cmdInput.text.toString()
        if (text.isBlank()) return
        cmdInput.setText("")
        // Keep the caret - and therefore the keyboard - in the command box: a
        // terminal you have to re-tap after every line is not a terminal.
        cmdInput.requestFocus()
        controller.runCommand(text)
    }

    // ------------------------------------------------------------------ tabs

    private fun selectTab(index: Int) {
        tab = index
        pageStatus.visibility = if (index == 0) View.VISIBLE else View.GONE
        pageSensors.visibility = if (index == 1) View.VISIBLE else View.GONE
        pageControl.visibility = if (index == 2) View.VISIBLE else View.GONE
        pageTerminal.visibility = if (index == 3) View.VISIBLE else View.GONE

        tabs.forEachIndexed { i, view ->
            val active = i == index
            view.setTextColor(
                ContextCompat.getColor(this, if (active) R.color.accent else R.color.text_secondary)
            )
            view.setTypeface(null, if (active) Typeface.BOLD else Typeface.NORMAL)
        }
        render()
        // Opening the page is the natural moment to ask: a failed `status` (the
        // Pi was busy, the first frame after handshake got dropped) would
        // otherwise leave it on "正在读取…" until something else refreshes.
        if (index == 2 && controller.isReady && controller.control == null) {
            controller.refreshControl()
        }
    }

    // ---------------------------------------------------------------- render

    override fun onChanged() {
        runOnUiThread { render() }
    }

    override fun onError(message: String) {
        runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    }

    override fun onAuthRequired(message: String) {
        runOnUiThread { showPairingDialog(message) }
    }

    override fun onActionDone(message: String, ok: Boolean) {
        runOnUiThread {
            // A toast truncates at roughly two lines, and the interesting part
            // of an nmcli error ("...key-mgmt: property is missing") is always
            // at the end. Long failures get a dialog the user can actually
            // read and select.
            if (message.length > 60 || (!ok && message.length > 30)) {
                AlertDialog.Builder(this)
                    .setTitle(if (ok) "完成" else "操作失败")
                    .setMessage(message)
                    .setPositiveButton("知道了", null)
                    .show()
            } else {
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * The pairing code is the only thing standing between a stranger's phone and
     * a root shell on the Pi, so it is asked for here and never travels over BLE
     * - only an HMAC of a one-time challenge does.
     */
    private fun showPairingDialog(message: String) {
        if (isFinishing || pairingDialog?.isShowing == true) return

        val input = EditText(this).apply {
            hint = "ABCD-EFGH-JKLM-NPQR"
            setText(Pairing.load(this@MainActivity))
            isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        }

        val padded = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(input)
        }

        pairingDialog = AlertDialog.Builder(this)
            .setTitle("输入配对码")
            .setMessage(
                "$message\n\n" +
                    "配对码在树莓派上，16 位，形如 ABCD-EFGH-JKLM-NPQR" +
                    "（大小写和横线都不影响）。\n\n" +
                    "服务模式：sudo /opt/piblue/venv/bin/python -m agent.token show\n" +
                    "开发模式：cat ~/pi-agent/.dev-token"
            )
            .setView(padded)
            .setPositiveButton("验证") { _, _ ->
                Pairing.save(this, input.text.toString())
                controller.retryAuth()
            }
            .setNegativeButton("稍后", null)
            .setOnDismissListener { pairingDialog = null }
            .show()
    }

    private fun render() {
        renderHeader()
        when (tab) {
            0 -> renderStatus()
            1 -> renderSensors()
            2 -> renderControl()
            else -> renderTerminal()
        }
        if (scanDialog?.isShowing == true) updateScanList()
    }

    private fun renderHeader() {
        val state = controller.state
        when (state) {
            is BleClient.State.Connected -> {
                // hostname is the only reliable "which Pi is this" hint: the
                // advertised name is the same on every unit.
                val host = controller.peerHostname?.let { "（$it）" } ?: ""
                if (controller.authorized) {
                    statusText.text = "已连接 ${state.name}$host"
                    statusDot.setTextColor(ContextCompat.getColor(this, R.color.dot_on))
                } else {
                    statusText.text = "已连接 ${state.name}$host（未配对）"
                    statusDot.setTextColor(ContextCompat.getColor(this, R.color.dot_wait))
                }
                btnHeader.text = "断开"
            }

            is BleClient.State.Connecting -> {
                statusText.text = "正在连接 ${state.name}"
                statusDot.setTextColor(ContextCompat.getColor(this, R.color.dot_wait))
                btnHeader.text = "扫描"
            }

            is BleClient.State.Scanning -> {
                statusText.text = "扫描中…"
                statusDot.setTextColor(ContextCompat.getColor(this, R.color.dot_wait))
                btnHeader.text = "扫描"
            }

            is BleClient.State.Failed -> {
                statusText.text = "连接异常"
                statusDot.setTextColor(ContextCompat.getColor(this, R.color.dot_err))
                btnHeader.text = "扫描"
            }

            else -> {
                statusText.text = "未连接"
                statusDot.setTextColor(ContextCompat.getColor(this, R.color.dot_off))
                btnHeader.text = "扫描"
            }
        }

        // A connected-but-unauthorised link answers nothing but hello/auth/ping,
        // so leaving the banner up is the honest thing to show: the Pi is not
        // usable yet.
        val needAuth = controller.isConnected && !controller.authorized
        banner.visibility = if (controller.isConnected && !needAuth) View.GONE else View.VISIBLE
        bannerText.text = when {
            needAuth -> "已连接，但还没通过配对验证。点右侧按钮重新填写配对码。"
            state is BleClient.State.Connecting -> "正在连接 ${state.name}…"
            state is BleClient.State.Failed -> state.message
            else -> "未连接。打开树莓派电源后点右侧按钮。"
        }
    }

    private fun renderStatus() {
        val info: SysInfo? = controller.info
        if (info == null) {
            tempValue.text = "—"
            loadValue.text = "—"
            loadDetail.text = ""
            memPct.text = "—"
            memBar.progress = 0
            memDetail.text = ""
            diskPct.text = "—"
            diskBar.progress = 0
            diskDetail.text = ""
            uptimeValue.text = "—"
            ipValue.text = "—"
            infoHostname.text = "—"
            infoModel.text = "—"
            infoKernel.text = "—"
            infoCores.text = "—"
            timeValue.text = "—"
            timeDetail.text = ""
            timeSync.text = ""
            infoWifi.text = "—"
            warnCard.visibility = View.GONE
            return
        }

        tempValue.text = if (info.cpuTempC == null) "—" else "${formatTemp(info.cpuTempC)} °C"
        tempValue.setTextColor(ContextCompat.getColor(this, tempColor(info.cpuTempC)))

        loadValue.text = two(info.load1)
        loadDetail.text = "5m ${two(info.load5)} · 15m ${two(info.load15)}"

        memPct.text = formatPct(info.mem?.usedPct)
        memBar.progress = ((info.mem?.usedPct ?: 0.0).coerceIn(0.0, 100.0)).toInt()
        memDetail.text = buildString {
            append("${formatGb(info.mem?.usedMb)} / ${formatGb(info.mem?.totalMb)} MB")
            append(" · 剩余 ${formatGb(info.mem?.availableMb)} MB")
        }

        diskTitle.text = "磁盘 ${info.disk?.path ?: "/"}"
        diskPct.text = formatPct(info.disk?.usedPct)
        diskBar.progress = ((info.disk?.usedPct ?: 0.0).coerceIn(0.0, 100.0)).toInt()
        diskDetail.text = "已用 ${formatGb(info.disk?.usedGb)} GB · 可用 ${formatGb(info.disk?.freeGb)} GB" +
            " · 共 ${formatGb(info.disk?.totalGb)} GB"

        uptimeValue.text = formatUptime(info.uptimeS)

        val clock = info.clock
        if (clock == null || clock.local == null) {
            timeValue.text = "—"
            timeDetail.text = ""
            timeSync.text = ""
        } else {
            timeValue.text = clock.local
            val skew = clock.skewSeconds()
            timeDetail.text = buildString {
                clock.zone?.let { append(it) }
                if (clock.zone == null && clock.tz != null) append(clock.tz)
                if (clock.utcOffset.orEmpty().isNotEmpty()) {
                    if (isNotEmpty()) append("    ")
                    val raw = clock.utcOffset!!
                    append("UTC").append(if (raw.length == 5) "${raw.take(3)}:${raw.takeLast(2)}" else raw)
                }
                if (skew != null) {
                    if (isNotEmpty()) append("    ")
                    append(
                        when {
                            kotlin.math.abs(skew) < 3 -> "与手机一致"
                            skew > 0 -> "比手机快 ${kotlin.math.abs(skew)} 秒"
                            else -> "比手机慢 ${kotlin.math.abs(skew)} 秒"
                        }
                    )
                }
            }
            // A clock that never reached NTP is the reason TLS and apt fail in
            // ways that look like network problems, so say it plainly here.
            when {
                clock.synced == false -> {
                    timeSync.text = "未同步 NTP"
                    timeSync.setTextColor(ContextCompat.getColor(this, R.color.warn_now))
                }
                skew != null && kotlin.math.abs(skew) > 60 -> {
                    timeSync.text = "时间偏差大"
                    timeSync.setTextColor(ContextCompat.getColor(this, R.color.danger))
                }
                else -> {
                    timeSync.text = "已同步"
                    timeSync.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
                }
            }
        }

        ipTitle.text = "IP (${info.net?.iface ?: "—"})"
        ipValue.text = info.net?.ipv4 ?: "—"

        infoHostname.text = info.hostname ?: "—"
        infoModel.text = (info.model ?: "—").replace("Raspberry Pi ", "Pi ")
        infoKernel.text = info.kernel ?: "—"
        infoCores.text = info.cpuCount.toString()

        val wifi = info.wifi
        infoWifi.text = if (wifi == null || wifi.ssid == null) {
            "—"
        } else {
            "${wifi.ssid} (${wifi.signal ?: 0}/70)"
        }

        val throttle = info.throttled
        if (throttle != null && !throttle.ok) {
            warnCard.visibility = View.VISIBLE
            warnText.text = throttle.flags.joinToString("、")
        } else {
            warnCard.visibility = View.GONE
        }
    }

    private fun renderSensors() {
        val sensors = controller.sensors
        sensorContainer.removeAllViews()

        when {
            !controller.isConnected -> {
                sensorCount.text = ""
                showSensorEmpty("未连接。连接树莓派后会自动列出可用传感器。")
            }

            sensors.isEmpty() -> {
                sensorCount.text = ""
                showSensorEmpty("没有检测到传感器。SoC 温度应该总是可用，若为空请检查 agent 日志。")
            }

            else -> {
                sensorEmpty.visibility = View.GONE
                sensorCount.text = "${sensors.size} 个传感器 · 点一行单独读取"
                sensors.forEach { addSensorRow(it) }
            }
        }
    }

    private fun showSensorEmpty(message: String) {
        sensorEmpty.text = message
        sensorEmpty.visibility = View.VISIBLE
    }

    private fun addSensorRow(meta: SensorMeta) {
        val row = layoutInflater.inflate(R.layout.view_sensor_row, sensorContainer, false)
        row.findViewById<TextView>(R.id.row_name).text = meta.name
        row.findViewById<TextView>(R.id.row_id).text = meta.id
        row.findViewById<TextView>(R.id.row_value).text =
            readingLabel(controller.readings[meta.id], meta)
        row.setOnClickListener { controller.readSensor(meta.id) }
        sensorContainer.addView(row)
    }

    private fun readingLabel(reading: SensorReading?, meta: SensorMeta): String {
        if (reading == null) return "未读取"
        reading.error?.let { return it }
        if (reading.values.isNotEmpty()) {
            return reading.values.entries.joinToString("\n") { (key, value) ->
                "${label(key)} ${one(value)}${unitFor(key, meta.unit)}"
            }
        }
        val unit = reading.unit ?: meta.unit
        val value = reading.value
        return if (value == null) "—" else "${formatNumber(value)}$unit"
    }

    // ---------------------------------------------------------------- control

    private fun renderControl() {
        val ready = controller.isReady
        controlEmpty.visibility = if (ready) View.GONE else View.VISIBLE
        controlCards.visibility = if (ready) View.VISIBLE else View.GONE
        if (!ready) {
            controlEmpty.text = when {
                !controller.isConnected -> "未连接。连接并配对后才能使用控制功能。"
                else -> "已连接，但还没通过配对验证。先在状态页填好配对码。"
            }
            return
        }

        val status = controller.control
        if (status == null) {
            controlCards.visibility = View.GONE
            controlEmpty.visibility = View.VISIBLE
            controlEmpty.text = "正在读取树莓派的可控项…"
            return
        }

        if (status.actions.isEmpty()) {
            controlCards.visibility = View.GONE
            controlEmpty.visibility = View.VISIBLE
            controlEmpty.text = if (status.enabled) {
                "这台树莓派没有开放任何控制动作。检查 config.json 的 control.allow，以及有没有 nmcli / lsblk。"
            } else {
                "控制功能已在树莓派上关闭（control.enabled = false）。"
            }
            return
        }

        renderPower(status)
        renderServices(status)
        renderWifi(status)
        renderDisks(status)
        renderCustom(status)
    }

    private fun renderPower(status: ControlStatus) {
        val canReboot = status.allows("reboot")
        val canShutdown = status.allows("shutdown")
        powerCard.visibility = if (canReboot || canShutdown) View.VISIBLE else View.GONE
        btnReboot.visibility = if (canReboot) View.VISIBLE else View.GONE
        btnShutdown.visibility = if (canShutdown) View.VISIBLE else View.GONE
    }

    private fun renderServices(status: ControlStatus) {
        val agent = status.allows("restart-agent")
        val bt = status.allows("restart-bluetooth")
        serviceCard.visibility = if (agent || bt) View.VISIBLE else View.GONE
        btnRestartAgent.visibility = if (agent) View.VISIBLE else View.GONE
        btnRestartBt.visibility = if (bt) View.VISIBLE else View.GONE
    }

    private fun renderWifi(status: ControlStatus) {
        val wifi = status.wifi
        val anyAction = status.actions.any { it.startsWith("wifi") }
        if (!anyAction || wifi == null || !wifi.available) {
            wifiCard.visibility = View.GONE
            return
        }
        wifiCard.visibility = View.VISIBLE

        val toggle = if (wifi.enabled) "wifi-disable" else "wifi-enable"
        btnWifiToggle.visibility = if (status.allows(toggle)) View.VISIBLE else View.GONE
        btnWifiToggle.text = if (wifi.enabled) "关闭 Wi-Fi" else "启用 Wi-Fi"
        btnWifiScan.visibility = if (status.allows("wifi-scan")) View.VISIBLE else View.GONE
        btnWifiDisconnect.visibility =
            if (status.allows("wifi-disconnect")) View.VISIBLE else View.GONE
        btnWifiScan.text = if (controller.wifiScanning) "扫描中…" else "扫描"

        wifiBadge.text = buildString {
            append(if (wifi.enabled) "已启用" else "已禁用")
            wifi.iface?.let { append(" · ").append(it) }
            wifi.backend?.let { append(" · ").append(it) }
        }

        wifiDetail.text = when {
            !wifi.enabled -> "无线已关闭，扫描和连接都不可用"
            wifi.ssid != null -> buildString {
                append(wifi.ssid)
                wifi.signal?.let { append("    ").append(it).append(" dBm") }
                wifi.state?.let { append("    ").append(it) }
                if (wifi.saved.isNotEmpty()) append("    已保存 ").append(wifi.saved.size).append(" 个")
            }
            else -> buildString {
                append("未连接任何网络")
                if (wifi.saved.isNotEmpty()) {
                    append("    已保存 ").append(wifi.saved.size).append(" 个，点下面的名字即可连")
                }
            }
        }

        renderWifiList(status)
    }

    private fun renderWifiList(status: ControlStatus) {
        wifiList.removeAllViews()
        if (controller.wifiScanning) {
            wifiList.addView(noteView("正在扫描，约 5 秒…"))
            return
        }
        val scanned = controller.wifiNetworks
        val savedNames = (status.wifi?.saved ?: emptyList()).toSet()
        // Saved networks that are not in range right now still deserve a row:
        // that is how the terminal behaves, and it is the one case where the
        // user knows the name but the scan cannot offer it.
        val missing = savedNames
            .filter { name -> scanned.none { it.ssid == name } }
            .map { WifiNetwork(ssid = it, signal = null, security = null, saved = true) }
        // Cap the scan section first so a crowded spectrum cannot push the
        // saved-but-out-of-range rows off the page; those are the ones the user
        // is usually looking for.
        val networks = scanned.take(20) + missing

        if (networks.isEmpty()) {
            wifiList.addView(
                noteView(
                    if (status.allows("wifi-scan")) "点「扫描」列出附近的网络"
                    else "没有扫描结果"
                )
            )
            return
        }
        networks.forEach { net ->
            val known = net.saved || net.ssid in savedNames
            val row = TextView(this).apply {
                text = buildString {
                    append(net.ssid)
                    if (net.signal != null) append("    ").append(net.signal).append(" dBm")
                    else append("    未扫描到")
                    if (known) append("    已保存") else if (net.secured) append("    加密")
                }
                textSize = 13f
                setTextColor(
                    ContextCompat.getColor(
                        this@MainActivity,
                        if (known) R.color.accent else R.color.text_primary
                    )
                )
                setPadding(dp(2), dp(9), dp(2), dp(9))
                isClickable = status.allows("wifi-connect")
                if (status.allows("wifi-connect")) {
                    setOnClickListener { askWifiPassword(net.copy(saved = known)) }
                }
            }
            wifiList.addView(row)
        }
    }

    private fun askWifiPassword(net: WifiNetwork) {
        if (net.saved) {
            // No password prompt: the Pi already holds the secret, it only needs
            // to be told to use that profile.
            confirmAction(
                "连接 ${net.ssid}",
                "树莓派已保存这个网络，直接用保存的密码连接，不需要再输入。",
                "连接",
            ) { controller.connectWifi(net.ssid, "") }
            return
        }
        if (!net.secured) {
            // "Open" here only means the scan did not report a security type.
            // A stale cache or a hidden AP looks exactly the same, so leave a
            // door open for a password instead of insisting there is none.
            AlertDialog.Builder(this)
                .setTitle("连接 ${net.ssid}")
                .setMessage("扫描结果里这个网络没有加密标记。如果连不上，多半是扫描缓存过期了，可以手动填密码。")
                .setPositiveButton("直接连接") { _, _ -> controller.connectWifi(net.ssid, "") }
                .setNeutralButton("输入密码") { _, _ -> askWifiPsk(net) }
                .setNegativeButton("取消", null)
                .show()
            return
        }
        askWifiPsk(net)
    }

    private fun askWifiPsk(net: WifiNetwork) {
        val input = EditText(this).apply {
            hint = "Wi-Fi 密码"
            isSingleLine = true
            textSize = 14f
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this)
            .setTitle("连接 ${net.ssid}")
            .setMessage("密码只通过蓝牙发给树莓派，不保存在手机上。")
            .setView(padded(input))
            .setPositiveButton("连接") { _, _ ->
                val psk = input.text.toString()
                if (psk.isBlank()) {
                    // Sending an empty key to a WPA network is what produces
                    // libnm's "key-mgmt: property is missing" in the first
                    // place - refuse here instead of round-tripping to the Pi.
                    Toast.makeText(this, "密码不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                controller.connectWifi(net.ssid, psk)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun renderDisks(status: ControlStatus) {
        if (!status.actions.any { it.startsWith("disk") }) {
            diskCard.visibility = View.GONE
            return
        }
        diskCard.visibility = View.VISIBLE
        diskContainer.removeAllViews()

        if (status.diskError != null) {
            diskHint.text = status.diskError
            return
        }
        diskHint.text = "挂载时自己取个目录名，挂到 ${status.mountRoot ?: "/mnt"} 下；" +
            "自己挂的可以在这里卸载。"

        // Every entry the Pi returned is shown. Filtering here used to hide
        // unmounted partitions whose filesystem lsblk could not name, which
        // looked exactly like "no disk to mount".
        val disks = status.disks.sortedBy { if (it.mounted) 1 else 0 }
        if (disks.isEmpty()) {
            diskContainer.addView(noteView("没有检测到分区。插上 U 盘后点「刷新」。"))
            return
        }
        disks.forEach { diskContainer.addView(diskRow(it, status)) }
    }

    /** Small pill for a filesystem name, e.g. `ext4` / `ntfs`. */
    private fun fsChip(fstype: String): TextView = TextView(this).apply {
        // `fuseblk` is what lsblk reports for an NTFS volume owned by ntfs-3g.
        // It is accurate and completely meaningless to a user looking for
        // "ntfs", so the chip says what the volume actually is.
        text = if (fstype.equals("fuseblk", ignoreCase = true)) "ntfs" else fstype.lowercase(Locale.ROOT)
        textSize = 10f
        setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
        setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent_dark))
        background = ContextCompat.getDrawable(this@MainActivity, R.drawable.chip_bg)
        setPadding(dp(7), dp(2), dp(7), dp(2))
    }

    private fun diskRow(disk: DiskEntry, status: ControlStatus): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dp(7), 0, dp(7))
        }

        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(this).apply {
            text = "${disk.label ?: disk.name}    ${formatSize(disk.sizeBytes)}"
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
        })
        // The filesystem type is what decides how the Pi mounts the thing, and
        // an NTFS volume behaves visibly differently from ext4 once mounted, so
        // it gets its own chip instead of sitting mid-sentence.
        val fs = disk.fstype?.trim().orEmpty()
        val sub = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, 0)
        }
        if (fs.isNotEmpty()) sub.addView(fsChip(fs))
        sub.addView(TextView(this).apply {
            text = when {
                disk.mounted -> "已挂载 ${disk.mountpoint}"
                fs.isEmpty() && disk.removable -> "未识别到文件系统（可移动设备）"
                fs.isEmpty() -> "未识别到文件系统"
                disk.ntfs -> "未挂载 · 挂载时用 ntfs3 驱动"
                else -> "未挂载"
            }
            textSize = 11f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
            if (fs.isNotEmpty()) setPadding(dp(6), 0, 0, 0)
        })
        texts.addView(sub)
        row.addView(
            texts,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )

        val canMount = !disk.mounted && disk.fstype != null && status.allows("disk-mount")
        val canUnmount = disk.mounted && disk.canUnmount && status.allows("disk-unmount")
        val action = TextView(this).apply {
            text = when {
                canMount -> "挂载"
                canUnmount -> "卸载"
                disk.mounted -> "系统挂载"
                else -> ""
            }
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(
                ContextCompat.getColor(
                    this@MainActivity,
                    if (canMount || canUnmount) R.color.accent_dark else R.color.text_secondary
                )
            )
            if (canMount || canUnmount) {
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.chip_bg)
                setPadding(dp(14), dp(7), dp(14), dp(7))
                isClickable = true
                setOnClickListener {
                    if (canMount) {
                        askMountName(disk, status)
                    } else {
                        confirmAction(
                            "卸载 ${disk.mountpoint}",
                            "如果有程序正在使用它，卸载会失败并告诉你哪个路径被占用。",
                            "卸载",
                        ) { controller.runAction("disk-unmount", 35_000) { put("device", disk.path) } }
                    }
                }
            }
        }
        if (action.text.isNotEmpty()) row.addView(action)
        return row
    }

    /**
     * Ask for the directory name, then mount under it.
     *
     * Only the name travels to the Pi, never a path: it is joined to
     * `control.mount_root` there and validated as a single component.
     */
    private fun askMountName(disk: DiskEntry, status: ControlStatus) {
        val root = (status.mountRoot ?: "/mnt").trimEnd('/')
        val suggestion = suggestMountName(disk)
        val input = EditText(this).apply {
            setText(suggestion)
            setSelection(suggestion.length)
            hint = "目录名"
            isSingleLine = true
            textSize = 14f
        }
        val preview = TextView(this).apply {
            text = "$root/$suggestion"
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
        }
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                val name = s?.toString().orEmpty()
                preview.text = "$root/${name.trim()}"
            }
        })

        val readOnly = CheckBox(this).apply {
            text = "只读挂载（卷被标记为脏时用）"
            textSize = 13f
            setPadding(0, dp(8), 0, 0)
        }

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(input)
            addView(preview)
            addView(readOnly)
        }

        val fs = disk.fstype?.trim()?.lowercase(Locale.ROOT)
        val driver = if (disk.ntfs) "$fs 分区，挂载时指定 ntfs3 驱动。" else "${fs ?: "未知"} 分区。"
        val dialog = AlertDialog.Builder(this)
            .setTitle("挂载 ${disk.path}")
            .setMessage(
                driver + "目录不存在会自动创建；已经存在且里面有文件时会拒绝，" +
                    "免得把原来的东西盖住。"
            )
            .setView(body)
            .setPositiveButton("挂载", null)
            .setNegativeButton("取消", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text.toString().trim()
                val problem = checkMountName(name)
                if (problem != null) {
                    Toast.makeText(this, problem, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                dialog.dismiss()
                controller.mountDisk(disk.path, name, readOnly.isChecked)
            }
        }
        dialog.show()
    }

    /** Mirrors the Pi's rule, so a typo does not cost a BLE round trip. */
    private fun checkMountName(name: String): String? {
        if (name.isEmpty()) return "目录名不能为空"
        if (name.length > 32) return "目录名最多 32 个字符"
        if (name == "." || name == "..") return "这个目录名不能用"
        if (!name.matches(Regex("^[A-Za-z0-9][A-Za-z0-9._-]*$"))) {
            return "只能用字母、数字、点、下划线和减号，首字符要是字母或数字"
        }
        return null
    }

    /** The label if it survives the name rule, otherwise the device name. */
    private fun suggestMountName(disk: DiskEntry): String {
        val candidate = (disk.label ?: disk.name)
            .replace(Regex("[^A-Za-z0-9._-]+"), "_")
            .trim('.', '_', '-')
            .take(32)
        return if (candidate.isNotEmpty() && candidate.first().isLetterOrDigit()) {
            candidate
        } else {
            disk.name
        }
    }

    private fun renderCustom(status: ControlStatus) {
        if (!status.showsCustom) {
            customCard.visibility = View.GONE
            return
        }
        customCard.visibility = View.VISIBLE

        customHint.text = if (status.canEditCustom) {
            "最多 ${status.customMax} 条，点名字执行，点「添加命令」自己写"
        } else {
            "来自树莓派 config.json 的 control.custom，最多 ${status.customMax} 条"
        }

        customContainer.removeAllViews()
        if (status.custom.isEmpty()) {
            customContainer.addView(noteView("还没有命令。"))
        }
        status.custom.forEach { item -> customContainer.addView(customRow(item, status)) }

        customAdd.visibility = if (status.canAddCustom) View.VISIBLE else View.GONE
        customAdd.setOnClickListener {
            if (status.canAddCustom) editCustomCommand(status, null)
        }
    }

    private fun customRow(item: CustomAction, status: ControlStatus): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }

        val name = TextView(this).apply {
            text = item.name
            textSize = 13f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent_dark))
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.chip_bg)
            gravity = android.view.Gravity.CENTER
            setPadding(dp(14), dp(11), dp(14), dp(11))
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
            isClickable = true
            setOnClickListener { confirmRunCustom(item) }
        }
        row.addView(name)

        if (status.canEditCustom) {
            row.addView(chip("编辑") { editCustomCommand(status, item) })
            row.addView(chip("删除") { deleteCustomCommand(status, item) })
        }
        return row
    }

    private fun chip(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 12f
        setTypeface(null, Typeface.BOLD)
        setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent_dark))
        background = ContextCompat.getDrawable(this@MainActivity, R.drawable.chip_bg)
        setPadding(dp(10), dp(8), dp(10), dp(8))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = dp(6) }
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun confirmRunCustom(item: CustomAction) {
        val detail = if (item.line.isBlank()) "" else "\n\n\$ ${item.line}"
        confirmAction("执行「${item.name}」", "会在树莓派上执行：$detail", "执行") {
            // announce = false: the output dialog below says more than a toast
            // ever could, and two "done" notices for one tap is noise.
            controller.runAction(
                "custom",
                45_000,
                announce = false,
                onData = { data -> showCustomResult(item, data) },
            ) { put("index", item.index) }
        }
    }

    /**
     * Show what a custom command printed.
     *
     * The Pi answers a `custom` action with stdout, stderr, the exit code and
     * the duration - the same shape as `exec`. A one-line toast ("nginx：完成")
     * throws all of that away, which is the opposite of what someone tapping a
     * command wants to see. Everything is monospace and selectable, because the
     * usual next step is pasting the error into a search box.
     */
    private fun showCustomResult(item: CustomAction, data: JSONObject) {
        if (isFinishing) return
        val result = Json.exec(data)

        val title = when {
            result.timedOut -> "${item.name} · 超时"
            result.code == 0 -> "${item.name} · 完成"
            else -> "${item.name} · 退出码 ${result.code}"
        }

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), 0)
        }
        box.addView(monoBlock("\$ ${item.line}", R.color.text_secondary))

        if (result.stdout.isNotBlank()) {
            box.addView(monoBlock(tidy(result.stdout), R.color.text_primary))
        }
        if (result.stderr.isNotBlank()) {
            box.addView(monoBlock(tidy(result.stderr), R.color.danger))
        }
        if (result.stdout.isBlank() && result.stderr.isBlank()) {
            box.addView(monoBlock("(无输出)", R.color.text_secondary))
        }

        val footer = buildString {
            if (result.timedOut) {
                append("命令超时，已在树莓派端终止")
            } else {
                append("退出码 ").append(result.code)
            }
            if (result.durationMs > 0) append(" · ").append(result.durationMs).append(" ms")
            if (result.truncated) append(" · 输出过长已截断")
        }
        box.addView(monoBlock(footer, R.color.text_secondary).apply {
            textSize = 11f
            setPadding(0, dp(8), 0, dp(4))
        })

        val scroll = ScrollView(this).apply { addView(box) }

        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(scroll)
            .setPositiveButton("关闭", null)
            .setNeutralButton("复制", null)
            .create()

        // The neutral button is re-wired so that copying does not dismiss the
        // dialog - the default click handler closes it, and the output that was
        // just copied is what the user is still looking at.
        dialog.setOnShowListener {
            // Cap the height so an 8 KB output does not become a full-screen
            // sheet; past that the ScrollView scrolls instead.
            val max = (resources.displayMetrics.heightPixels * 0.55f).toInt()
            if (scroll.height > max) {
                scroll.layoutParams = scroll.layoutParams.apply { height = max }
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                copyCustomResult(item, result)
            }
        }
        dialog.show()
    }

    private fun copyCustomResult(item: CustomAction, result: ExecResult) {
        val text = buildString {
            append("\$ ").append(item.line)
            if (result.stdout.isNotBlank()) append("\n\n").append(tidy(result.stdout))
            if (result.stderr.isNotBlank()) append("\n\n[stderr]\n").append(tidy(result.stderr))
            if (result.stdout.isBlank() && result.stderr.isBlank()) append("\n\n(无输出)")
            append("\n\n退出码 ").append(result.code)
            if (result.durationMs > 0) append(" · ").append(result.durationMs).append(" ms")
        }
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("PiBlue 命令输出", text))
        Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show()
    }

    /**
     * Strip the line ending a command leaves behind.
     *
     * The Pi returns `\n`, but a command that prints a file authored on Windows
     * brings `\r\n` along, and a stray `\r` renders as a box in the dialog.
     */
    private fun tidy(text: String): String = text.replace("\r\n", "\n").trimEnd('\n')

    /** Monospace, selectable text block for command output. */
    private fun monoBlock(text: String, colorRes: Int): TextView = TextView(this).apply {
        this.text = text
        textSize = 12f
        typeface = Typeface.MONOSPACE
        setTextColor(ContextCompat.getColor(this@MainActivity, colorRes))
        setTextIsSelectable(true)
        setLineSpacing(dp(3).toFloat(), 1f)
        setPadding(0, dp(6), 0, 0)
    }

    /**
     * One dialog for add and edit. Saving always sends the whole list, because
     * that is what the Pi replaces - there is no per-row endpoint to half-apply.
     */
    private fun editCustomCommand(status: ControlStatus, existing: CustomAction?) {
        val nameInput = EditText(this).apply {
            hint = "名称（显示在按钮上）"
            isSingleLine = true
            textSize = 14f
            existing?.let {
                setText(it.name)
                setSelection(it.name.length)
            }
        }
        val lineInput = EditText(this).apply {
            hint = "要执行的命令，例如 vcgencmd measure_temp"
            isSingleLine = true
            textSize = 14f
            typeface = android.graphics.Typeface.MONOSPACE
            existing?.let {
                setText(it.line)
                setSelection(it.line.length)
            }
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(nameInput)
            addView(lineInput)
            addView(
                noteView(
                    "命令以 root 执行，点按钮时还会再确认一次。" +
                        "rm / dd / mkfs 这类在树莓派的黑名单里，保存时就会被拒绝。"
                )
            )
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "添加命令" else "编辑「${existing.name}」")
            .setView(body)
            .setPositiveButton("保存", null)
            .setNegativeButton("取消", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = nameInput.text.toString().trim()
                val line = lineInput.text.toString().trim()
                when {
                    line.isEmpty() -> {
                        Toast.makeText(this, "要执行的命令不能为空", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    line.length > 500 -> {
                        Toast.makeText(this, "命令太长（最多 500 个字符）", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    name.length > 40 -> {
                        Toast.makeText(this, "名称太长（最多 40 个字符）", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                }
                val items = status.custom.map { it.name to it.line }.toMutableList()
                val entry = name.ifBlank { line.take(40) } to line
                val index = existing?.index ?: -1
                if (index in items.indices) items[index] = entry else items.add(entry)
                dialog.dismiss()
                controller.saveCustomCommands(items)
            }
        }
        dialog.show()
    }

    private fun deleteCustomCommand(status: ControlStatus, item: CustomAction) {
        confirmAction(
            "删除「${item.name}」",
            "只从树莓派上删掉这条，不会执行它。",
            "删除",
        ) {
            controller.saveCustomCommands(
                status.custom.filter { it.index != item.index }.map { it.name to it.line }
            )
        }
    }

    private fun noteView(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 11f
        setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
        setPadding(dp(2), dp(8), dp(2), dp(8))
    }

    private fun padded(view: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), dp(8), dp(24), 0)
        addView(view)
    }

    private fun confirmAction(
        title: String,
        message: String,
        positive: String,
        onOk: () -> Unit,
    ) {
        if (isFinishing) return
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(positive) { _, _ -> onOk() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1_000_000_000L -> String.format(Locale.US, "%.1f GB", bytes / 1e9)
        bytes >= 1_000_000L -> String.format(Locale.US, "%.0f MB", bytes / 1e6)
        bytes > 0 -> String.format(Locale.US, "%d KB", bytes / 1000)
        else -> "—"
    }

    private fun renderTerminal() {
        val lines = controller.terminal
        // The Pi pushes a `summary` every 2 s, and every push re-renders the
        // open page. Rebuilding this spannable (and scrolling) on each tick is
        // what used to yank focus out of the command box mid-word, so the
        // transcript is only redrawn when it actually grew.
        if (lines === termDrawn) return
        termDrawn = lines

        val stayAtBottom = isTermAtBottom()
        val builder = SpannableStringBuilder()
        lines.forEach { line ->
            val start = builder.length
            builder.append(line.text)
            val bodyEnd = builder.length
            builder.append('\n')
            builder.setSpan(
                ForegroundColorSpan(ContextCompat.getColor(this, colorFor(line.kind))),
                start,
                bodyEnd,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            if (line.kind == LineKind.Input) {
                builder.setSpan(
                    StyleSpan(Typeface.BOLD),
                    start,
                    bodyEnd,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
        if (builder.isEmpty()) {
            builder.append(
                if (controller.isConnected) {
                    "在下面输入命令，回车执行。"
                } else {
                    "未连接。连接树莓派后可以在这里执行命令。"
                }
            )
            builder.setSpan(
                ForegroundColorSpan(ContextCompat.getColor(this, R.color.text_secondary)),
                0,
                builder.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        termOutput.text = builder
        // Only follow new output when the user was already at the end: someone
        // scrolled up reading `dmesg` should not be dragged back down by a
        // command that finishes while they read.
        if (stayAtBottom) scrollTerminalToBottom()
    }

    /** True while the transcript is parked at its end (or has no height yet). */
    private fun isTermAtBottom(): Boolean {
        val content = termOutput.height
        if (content == 0) return true
        return termScroll.scrollY + termScroll.height >= content - dp(24)
    }

    /**
     * Scroll to the end of the transcript without touching focus.
     *
     * `fullScroll(FOCUS_DOWN)` looks like the obvious call and is the trap: as
     * its documentation says, it also hands focus to the bottom-most focusable
     * view in the newly visible area - here the selectable output TextView, or
     * the ScrollView itself. Called on a 2 s timer that steals the keyboard
     * from the input field after a letter or two. [ScrollView.scrollTo] only
     * moves pixels.
     */
    private fun scrollTerminalToBottom() {
        termScroll.post {
            val bottom = (termOutput.height - termScroll.height).coerceAtLeast(0)
            termScroll.scrollTo(0, bottom)
        }
    }

    private fun colorFor(kind: LineKind): Int = when (kind) {
        LineKind.Input -> R.color.accent
        LineKind.Output -> R.color.text_primary
        LineKind.Error -> R.color.danger
        LineKind.Note -> R.color.text_secondary
    }

    // ------------------------------------------------------------------ scan

    /** Surfaces the stack trace the previous run died with, then discards it. */
    private fun showLastCrashIfAny() {
        val dir = getExternalFilesDir(null) ?: filesDir
        val file = java.io.File(dir, PiBlueApp.CRASH_FILE)
        if (!file.exists()) return

        val text = runCatching { file.readText() }.getOrNull().orEmpty()
        runCatching { file.delete() }
        if (text.isBlank()) return

        AlertDialog.Builder(this)
            .setTitle("上次运行的崩溃日志")
            .setMessage(text)
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun beginScan() {
        if (controller.hasPermissions()) {
            openScanDialog()
        } else {
            requestPermissions(controller.requiredPermissions(), REQ_PERMISSIONS)
        }
    }

    private fun openScanDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }

        // Entered here as well as in the dedicated dialog, because the first
        // connection is when it is needed and hunting for a settings screen
        // afterwards is a worse experience than one extra field.
        val codeInput = EditText(this).apply {
            hint = "配对码 ABCD-EFGH-JKLM-NPQR"
            setText(Pairing.load(this@MainActivity))
            isSingleLine = true
            textSize = 14f
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        }
        container.addView(
            codeInput,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val note = TextView(this).apply {
            text = "install.sh 最后会打印一次；留空则连接后会被要求补填。"
            textSize = 11f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
            setPadding(0, dp(2), 0, dp(4))
        }
        container.addView(note)

        val listView = ListView(this)
        container.addView(
            listView,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(260))
        )

        listView.setOnItemClickListener { _, _, position, _ ->
            val hit = controller.hits.getOrNull(position) ?: return@setOnItemClickListener
            Pairing.save(this, codeInput.text.toString())
            scanDialog?.dismiss()
            controller.connect(hit)
        }
        scanListView = listView

        val dialog = AlertDialog.Builder(this)
            .setTitle("附近的树莓派")
            .setView(container)
            .setPositiveButton("重新扫描", null)
            .setNegativeButton("取消", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                Pairing.save(this, codeInput.text.toString())
                controller.startScan()
            }
        }
        dialog.setOnDismissListener {
            controller.stopScan()
            scanListView = null
        }

        scanDialog = dialog
        dialog.show()
        updateScanList()
        controller.startScan()
    }

    private fun updateScanList() {
        val listView = scanListView ?: return
        val hits = controller.hits
        // Two lines per row: the advertised name is not unique (every Pi ships
        // as "PiBlue"), so the MAC is what actually tells two of them apart.
        val rows: List<Map<String, String>> = if (hits.isEmpty()) {
            val message = when (val state = controller.state) {
                is BleClient.State.Failed -> state.message
                else -> "搜索中… 确认树莓派已开机、名字是 PiBlue"
            }
            listOf(mapOf("line1" to message, "line2" to ""))
        } else {
            hits.map {
                mapOf(
                    "line1" to it.name,
                    "line2" to "${it.address}    ${it.rssi} dBm",
                )
            }
        }
        listView.adapter = SimpleAdapter(
            this,
            rows,
            android.R.layout.simple_list_item_2,
            arrayOf("line1", "line2"),
            intArrayOf(android.R.id.text1, android.R.id.text2),
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMISSIONS) return
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            openScanDialog()
        } else {
            Toast.makeText(this, "没有蓝牙权限，无法扫描设备", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        scanDialog?.dismiss()
        pairingDialog?.dismiss()
        controller.release()
        super.onDestroy()
    }

    // ----------------------------------------------------------------- utils

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun two(value: Double): String = String.format(Locale.US, "%.2f", value)

    private fun one(value: Double): String = String.format(Locale.US, "%.1f", value)

    private fun tempColor(celsius: Double?): Int = when {
        celsius == null -> R.color.text_primary
        celsius >= 75.0 -> R.color.danger
        celsius >= 65.0 -> R.color.warn_now
        else -> R.color.accent
    }

    private fun label(key: String): String = when (key) {
        "temp_c", "temperature" -> "温度"
        "humidity" -> "湿度"
        "pressure" -> "气压"
        "value" -> "值"
        else -> key
    }

    private fun unitFor(key: String, fallback: String): String = when (key) {
        "temp_c", "temperature" -> " °C"
        "humidity" -> " %"
        else -> if (fallback.isBlank()) "" else " $fallback"
    }

    private companion object {
        const val REQ_PERMISSIONS = 1001

        val QUICK_COMMANDS = listOf(
            "uname -a",
            "vcgencmd measure_temp",
            "df -h",
            "free -m",
            "uptime",
            "ps -eo pid,pcpu,pmem,comm",
        )
    }
}
