package com.raspi.piblue

import android.app.Activity
import android.app.AlertDialog
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
import com.raspi.piblue.data.Pairing
import com.raspi.piblue.data.SensorMeta
import com.raspi.piblue.data.SensorReading
import com.raspi.piblue.data.SysInfo
import com.raspi.piblue.ui.formatGb
import com.raspi.piblue.ui.formatNumber
import com.raspi.piblue.ui.formatPct
import com.raspi.piblue.ui.formatTemp
import com.raspi.piblue.ui.formatUptime
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
        pageTerminal = findViewById(R.id.page_terminal)
        tabs = listOf(
            findViewById(R.id.tab_status),
            findViewById(R.id.tab_sensors),
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
        infoWifi = findViewById(R.id.info_wifi)
        warnCard = findViewById(R.id.warn_card)
        warnText = findViewById(R.id.warn_text)

        sensorCount = findViewById(R.id.sensor_count)
        sensorEmpty = findViewById(R.id.sensor_empty)
        sensorContainer = findViewById(R.id.sensor_container)

        termScroll = findViewById(R.id.term_scroll)
        termOutput = findViewById(R.id.term_output)
        cmdInput = findViewById(R.id.cmd_input)
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

        buildChips()
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
                selectTab(2)
                controller.runCommand(command)
            }
            row.addView(chip)
        }
    }

    private fun sendCommand() {
        val text = cmdInput.text.toString()
        if (text.isBlank()) return
        cmdInput.setText("")
        controller.runCommand(text)
    }

    // ------------------------------------------------------------------ tabs

    private fun selectTab(index: Int) {
        tab = index
        pageStatus.visibility = if (index == 0) View.VISIBLE else View.GONE
        pageSensors.visibility = if (index == 1) View.VISIBLE else View.GONE
        pageTerminal.visibility = if (index == 2) View.VISIBLE else View.GONE

        tabs.forEachIndexed { i, view ->
            val active = i == index
            view.setTextColor(
                ContextCompat.getColor(this, if (active) R.color.accent else R.color.text_secondary)
            )
            view.setTypeface(null, if (active) Typeface.BOLD else Typeface.NORMAL)
        }
        render()
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

    private fun renderTerminal() {
        val lines = controller.terminal
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
        termScroll.post { termScroll.fullScroll(View.FOCUS_DOWN) }
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
