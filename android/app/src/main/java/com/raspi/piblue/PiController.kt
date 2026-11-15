package com.raspi.piblue

import android.content.Context
import com.raspi.piblue.ble.BleClient
import com.raspi.piblue.ble.BleTimeout
import com.raspi.piblue.data.ControlJson
import com.raspi.piblue.data.ControlStatus
import com.raspi.piblue.data.Json
import com.raspi.piblue.data.Pairing
import com.raspi.piblue.data.SensorMeta
import com.raspi.piblue.data.SensorReading
import com.raspi.piblue.data.SysInfo
import com.raspi.piblue.data.WifiNetwork
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** A terminal row, tagged so the view can colour it. */
enum class LineKind { Input, Output, Error, Note }

data class TermLine(val text: String, val kind: LineKind)

class PiError(message: String) : Exception(message)

/**
 * All protocol/state logic, independent of the UI toolkit.
 *
 * The Activity implements [Listener] and re-renders on [Listener.onChanged].
 * Everything runs on the main dispatcher: BLE callbacks only suspend, they never
 * block, so there is no background-thread-to-UI plumbing to get wrong.
 */
class PiController(context: Context) {

    interface Listener {
        fun onChanged()
        fun onError(message: String)

        /** The Pi wants a pairing code before it will answer anything. */
        fun onAuthRequired(message: String) {}

        /** A control action finished; `ok` says whether it worked. */
        fun onActionDone(message: String, ok: Boolean) {}
    }

    var listener: Listener? = null

    private val appContext: Context = context.applicationContext
    val ble = BleClient(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    var state: BleClient.State = BleClient.State.Idle
        private set
    var info: SysInfo? = null
        private set
    var sensors: List<SensorMeta> = emptyList()
        private set
    var readings: Map<String, SensorReading> = emptyMap()
        private set
    var terminal: List<TermLine> = emptyList()
        private set
    var hits: List<BleClient.Hit> = emptyList()
        private set

    /** What the control page may offer, straight from the Pi's config. */
    var control: ControlStatus? = null
        private set
    var wifiNetworks: List<WifiNetwork> = emptyList()
        private set
    var wifiScanning: Boolean = false
        private set

    /** False while connected but not yet past the pairing gate. */
    var authorized: Boolean = false
        private set

    /** Version reported by the Pi, so a wrong deploy is visible at a glance. */
    var agentVersion: String? = null
        private set

    /**
     * The Pi's hostname from `hello`. Every Pi advertises itself as "PiBlue",
     * so this is the only way to know which one you are actually talking to.
     */
    var peerHostname: String? = null
        private set

    var autoRefresh: Boolean = true
        private set

    val isConnected: Boolean
        get() = state is BleClient.State.Connected

    /** True when the app may actually send commands. */
    val isReady: Boolean
        get() = isConnected && authorized

    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JSONObject>>()
    private val nextId = AtomicLong(1L)
    private var subJob: Job? = null
    private var lastMtu: Int = 23
    private val authMutex = Mutex()

    /** Actions the Pi refuses unless the phone confirms it meant them. */
    private val CONFIRM_ACTIONS = setOf("reboot", "shutdown")

    init {
        scope.launch {
            ble.state.collect { incoming ->
                state = incoming
                when (incoming) {
                    is BleClient.State.Connected -> {
                        lastMtu = incoming.mtu
                        authorized = false
                        addLine("已连接 ${incoming.name}，MTU ${incoming.mtu}", LineKind.Note)
                        // Handshake first, and hand over the MTU while we are at
                        // it: the Pi cannot read the negotiated MTU out of BlueZ,
                        // and it must not send a notification bigger than MTU-3.
                        // Without this the first `info` reply (566 bytes) is
                        // silently dropped by bluetoothd and shows up here as a
                        // plain timeout.
                        scope.launch {
                            if (handshake(incoming.mtu)) {
                                refreshInfo()
                                refreshSensors()
                                refreshControl()
                                if (autoRefresh) startSubscription()
                            }
                        }
                    }

                    is BleClient.State.Failed -> {
                        authorized = false
                        addLine(incoming.message, LineKind.Error)
                        listener?.onError(incoming.message)
                    }

                    else -> {
                        if (incoming !is BleClient.State.Connecting) authorized = false
                    }
                }
                notifyChanged()
            }
        }

        scope.launch {
            ble.hits.collect { list ->
                hits = list
                notifyChanged()
            }
        }

        scope.launch {
            ble.frames.collect { frame ->
                val obj = runCatching { JSONObject(frame) }.getOrNull() ?: return@collect
                val event = obj.optString("event", "")
                if (event.isNotEmpty()) handleEvent(event, obj) else routeResponse(obj)
            }
        }
    }

    // ------------------------------------------------------------- connection

    fun hasPermissions(): Boolean = ble.hasPermissions()

    fun requiredPermissions(): Array<String> = ble.requiredPermissions()

    fun startScan() = ble.startScan()

    fun stopScan() = ble.stopScan()

    fun connect(hit: BleClient.Hit) {
        terminal = emptyList()
        info = null
        readings = emptyMap()
        sensors = emptyList()
        control = null
        wifiNetworks = emptyList()
        wifiScanning = false
        authorized = false
        peerHostname = null
        lastMtu = 23
        ble.connect(hit)
        notifyChanged()
    }

    fun disconnect() {
        stopSubscription()
        ble.disconnect()
        authorized = false
        control = null
        wifiNetworks = emptyList()
        addLine("已断开连接", LineKind.Note)
        notifyChanged()
    }

    // ------------------------------------------------------------------ auth

    /**
     * hello -> (challenge) -> auth.
     *
     * Returns true when commands may be sent. The Pi answers nothing but
     * hello/auth/ping until this succeeds, so every other call is pointless
     * while it returns false.
     */
    private suspend fun handshake(mtu: Int): Boolean = authMutex.withLock {
        authorized = false
        notifyChanged()

        val hello = try {
            callOnce("hello", 5_000, { put("mtu", mtu) }, retry = false)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            fail(friendly(t))
            return@withLock false
        }

        val data = hello.optJSONObject("data") ?: JSONObject()
        agentVersion = if (data.has("version") && !data.isNull("version")) {
            data.optString("version")
        } else {
            null
        }
        // The advertised name cannot tell two Pis apart, the hostname can.
        peerHostname = if (data.has("hostname") && !data.isNull("hostname")) {
            data.optString("hostname").takeIf { it.isNotBlank() }
        } else {
            null
        }

        val auth = data.optJSONObject("auth")
        if (auth == null || !auth.optBoolean("required", false)) {
            authorized = true
            // Which agent is actually deployed has been the sticking point more
            // than once, so it is stated every time instead of being guessed.
            agentVersion?.let { addLine("agent $it（此设备未启用配对码）", LineKind.Note) }
            peerHostname?.let { addLine("已连接 $it", LineKind.Note) }
            notifyChanged()
            return@withLock true
        }

        val challenge = auth.optString("challenge", "")
        if (challenge.isEmpty()) {
            fail("树莓派没有下发配对挑战，请检查它是否配置了配对码")
            return@withLock false
        }
        if (Pairing.load(appContext).isEmpty()) {
            addLine("这台树莓派启用了配对码，需要先填写", LineKind.Error)
            notifyChanged()
            listener?.onAuthRequired("这台树莓派启用了配对码，请输入 install.sh 最后打印的 16 位。")
            return@withLock false
        }

        val answer = try {
            Pairing.answer(appContext, challenge)
        } catch (t: Throwable) {
            fail("配对码格式不对，应为 16 位字母数字（A-Z、2-7）")
            return@withLock false
        }

        try {
            callOnce("auth", 5_000, { put("mac", answer) }, retry = false)
            authorized = true
            val host = peerHostname?.let { " · $it" } ?: ""
            addLine("配对通过 · agent ${agentVersion ?: "?"}$host", LineKind.Note)
            notifyChanged()
            true
        } catch (c: CancellationException) {
            throw c
        } catch (e: PiError) {
            val message = e.message ?: "配对失败"
            addLine(message, LineKind.Error)
            notifyChanged()
            if (message.contains("wrong pairing code") || message.contains("no challenge")) {
                listener?.onAuthRequired(message)
            } else {
                listener?.onError(message)
            }
            false
        } catch (t: Throwable) {
            fail(friendly(t))
            false
        }
    }

    /** Called by the UI after the user has entered or corrected the code. */
    fun retryAuth() {
        if (!isConnected) return
        scope.launch {
            if (handshake(lastMtu)) {
                refreshInfo()
                refreshSensors()
                refreshControl()
                if (autoRefresh) startSubscription()
            }
        }
    }

    // ------------------------------------------------------------------ data

    fun refreshInfo() {
        if (!isConnected) return
        launchSafe {
            val data = call("info").optJSONObject("data") ?: return@launchSafe
            info = Json.sysInfo(data)
            notifyChanged()
        }
    }

    fun refreshSensors() {
        if (!isConnected) return
        launchSafe {
            val meta = Json.sensors(call("sensors").optJSONObject("data") ?: JSONObject())
            sensors = meta
            if (meta.isNotEmpty()) {
                val all = Json.readings(
                    call("sensors", 20_000) { put("read", true) }.optJSONObject("data") ?: JSONObject()
                )
                readings = all.associateBy { it.id }
            }
            notifyChanged()
        }
    }

    fun readSensor(id: String) {
        if (!isConnected) return
        launchSafe {
            val data = call("sensor") { put("sensor", id) }.optJSONObject("data") ?: return@launchSafe
            val reading = Json.reading(data)
            readings = readings + (reading.id to reading)
            notifyChanged()
        }
    }

    // ---------------------------------------------------------------- control

    /** Snapshot for the control page: allowed actions, Wi-Fi state, disks. */
    fun refreshControl() {
        if (!isConnected || !authorized) return
        launchSafe {
            val data = call("control", 25_000) { put("action", "status") }.optJSONObject("data")
                ?: return@launchSafe
            control = ControlJson.status(data)
            notifyChanged()
        }
    }

    /**
     * Fire one control action.
     *
     * The action name *is* the vocabulary: the Pi validates every field
     * (`ssid`, `device`, `index`) against its own lists, so nothing sent from
     * here can turn into a shell line.
     */
    fun runAction(
        action: String,
        timeoutMs: Long = 25_000,
        fields: JSONObject.() -> Unit = {},
    ) {
        if (!isConnected) {
            listener?.onActionDone("未连接", false)
            return
        }
        launchSafe {
            val data = call("control", timeoutMs) {
                put("action", action)
                if (action in CONFIRM_ACTIONS) put("confirm", true)
                fields()
            }.optJSONObject("data") ?: JSONObject()
            val message = data.optString("message").ifBlank { "已执行 $action" }
            addLine(message, LineKind.Note)
            listener?.onActionDone(message, true)
            // Asking for a snapshot right after "reboot" would only produce a
            // timeout toast: the link is about to go away on purpose.
            if (action !in CONFIRM_ACTIONS) refreshControl()
        }
    }

    fun connectWifi(ssid: String, password: String) {
        runAction("wifi-connect", 70_000) {
            put("ssid", ssid)
            if (password.isNotEmpty()) put("password", password)
        }
    }

    /**
     * Mount [device] under the user's own directory name.
     *
     * Only the directory name travels, never a path: the Pi joins it to its own
     * `control.mount_root` and validates it as a single component, so nothing
     * here can point at /etc.
     */
    fun mountDisk(device: String, name: String, readOnly: Boolean = false) {
        runAction("disk-mount", 40_000) {
            put("device", device)
            put("name", name)
            if (readOnly) put("read_only", true)
        }
    }

    /** Replace the Pi's list with exactly [items], in the order drawn on screen. */
    fun saveCustomCommands(items: List<Pair<String, String>>) {
        runAction("custom-save", 40_000) {
            put(
                "items",
                JSONArray().apply {
                    items.forEach { (name, line) ->
                        put(
                            JSONObject().apply {
                                put("name", name)
                                put("line", line)
                            }
                        )
                    }
                }
            )
        }
    }

    /** Slow enough (5-20 s) to need its own progress state in the UI. */
    fun scanWifi() {
        if (!isConnected || wifiScanning) return
        wifiScanning = true
        notifyChanged()
        scope.launch {
            try {
                val data = call("control", 60_000) { put("action", "wifi-scan") }
                    .optJSONObject("data")
                wifiNetworks = if (data == null) emptyList() else ControlJson.networks(data)
                listener?.onActionDone("扫描到 ${wifiNetworks.size} 个网络", true)
            } catch (t: TimeoutCancellationException) {
                fail("扫描超时：树莓派可能正在重启网卡，稍后再试")
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                fail(friendly(t))
            } finally {
                wifiScanning = false
                notifyChanged()
            }
        }
    }

    fun setAutoRefresh(enabled: Boolean) {        autoRefresh = enabled
        if (!isConnected) return
        if (enabled) startSubscription() else stopSubscription()
    }

    private fun startSubscription() {
        subJob?.cancel()
        subJob = scope.launch {
            try {
                call("sub") {
                    put("topic", "summary")
                    put("every", 2.0)
                }
            } catch (c: CancellationException) {
                throw c
            } catch (_: Throwable) {
                // Pushing is a nicety; manual refresh still works.
            }
        }
    }

    private fun stopSubscription() {
        subJob?.cancel()
        subJob = null
        if (!isConnected) return
        scope.launch { runCatching { call("unsub") { put("topic", "summary") } } }
    }

    // -------------------------------------------------------------- terminal

    fun runCommand(raw: String) {
        val line = raw.trim()
        if (line.isEmpty()) return
        addLine("$ $line", LineKind.Input)
        if (!isConnected) {
            addLine("未连接，命令没有发送", LineKind.Error)
            return
        }
        launchSafe {
            val result = Json.exec(
                call("exec", 30_000) { put("line", line) }.optJSONObject("data") ?: JSONObject()
            )
            val out = result.stdout.trimEnd()
            val err = result.stderr.trimEnd()
            if (out.isNotEmpty()) addLine(out, LineKind.Output)
            if (err.isNotEmpty()) addLine(err, LineKind.Error)
            if (out.isEmpty() && err.isEmpty()) addLine("(无输出，退出码 ${result.code})", LineKind.Note)
            if (result.timedOut) addLine("命令超时，已在树莓派端终止", LineKind.Error)
            if (result.truncated) addLine("输出过长，已截断", LineKind.Note)
        }
    }

    fun clearTerminal() {
        terminal = emptyList()
        notifyChanged()
    }

    private fun addLine(text: String, kind: LineKind) {
        terminal = (terminal + TermLine(text, kind)).takeLast(400)
        notifyChanged()
    }

    // -------------------------------------------------------------- plumbing

    private fun handleEvent(event: String, obj: JSONObject) {
        when (event) {
            "summary" -> {
                val data = obj.optJSONObject("data") ?: return
                // Do not bail out when the full snapshot has not landed yet:
                // the 2 s push carries enough to fill the live tiles, and a
                // status tab that stays blank because one big response went
                // missing is worse than a status tab with "—" for disk/IP.
                val base = info ?: SysInfo(
                    hostname = null,
                    model = null,
                    kernel = null,
                    uptimeS = 0L,
                    cpuTempC = null,
                    cpuCount = 0,
                    load1 = 0.0,
                    load5 = 0.0,
                    load15 = 0.0,
                    mem = null,
                    disk = null,
                    net = null,
                    wifi = null,
                    throttled = null,
                )

                val temp = if (data.has("cpu_temp_c") && !data.isNull("cpu_temp_c")) {
                    data.optDouble("cpu_temp_c").takeIf { !it.isNaN() }
                } else {
                    null
                }
                val memPct = if (data.has("mem_used_pct") && !data.isNull("mem_used_pct")) {
                    data.optDouble("mem_used_pct").takeIf { !it.isNaN() }
                } else {
                    null
                }
                val mem = base.mem

                val clockObj = data.optJSONObject("time")
                info = base.copy(
                    cpuTempC = temp ?: base.cpuTempC,
                    load1 = data.optDouble("load1", base.load1),
                    uptimeS = data.optLong("uptime_s", base.uptimeS),
                    mem = if (mem != null && memPct != null) mem.copy(usedPct = memPct) else mem,
                    // The push repeats every ~2 s, so the clock tile ticks
                    // without asking the Pi for a full snapshot.
                    clock = clockObj?.let { Json.piClock(it) } ?: base.clock,
                )
                notifyChanged()
            }

            "error" -> listener?.onError(obj.optString("error", "树莓派报告了一个错误"))
            else -> Unit
        }
    }

    private fun routeResponse(obj: JSONObject) {
        val id = obj.optLong("id", -1L)
        pending.remove(id)?.complete(obj)
    }

    private suspend fun call(
        cmd: String,
        timeoutMs: Long = 15_000,
        build: JSONObject.() -> Unit = {},
    ): JSONObject = callOnce(cmd, timeoutMs, build, retry = true)

    /**
     * @param retry re-run the handshake once when the Pi answers
     *   `code: "unauthorized"`. Sessions end with the link and after an idle
     *   timeout, so the first command of a new session legitimately lands
     *   unauthenticated; re-authenticating is invisible to the user.
     */
    private suspend fun callOnce(
        cmd: String,
        timeoutMs: Long,
        build: JSONObject.() -> Unit,
        retry: Boolean,
    ): JSONObject {
        val id = nextId.getAndIncrement()
        val deferred = CompletableDeferred<JSONObject>()
        pending[id] = deferred
        try {
            val message = JSONObject().put("id", id).put("cmd", cmd).apply(build)
            ble.send(message.toString())
            val response = withTimeout(timeoutMs) { deferred.await() }
            if (!response.optBoolean("ok", false)) {
                val code = response.optString("code", "")
                if (retry && code == "unauthorized" && cmd != "hello" && cmd != "auth") {
                    if (handshake(lastMtu)) return callOnce(cmd, timeoutMs, build, retry = false)
                }
                throw PiError(response.optString("error", "命令失败"))
            }
            return response
        } finally {
            pending.remove(id)
        }
    }

    private fun launchSafe(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (t: TimeoutCancellationException) {
                // Must come before CancellationException: a timeout is one, but
                // here it means "the Pi did not answer", not "we got cancelled".
                fail("树莓派没有响应（超时）")
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                fail(friendly(t))
            }
        }
    }

    private fun fail(message: String) {
        addLine(message, LineKind.Error)
        listener?.onError(message)
    }

    private fun friendly(t: Throwable): String = when (t) {
        is PiError -> t.message ?: "命令失败"
        // Before the IOException branch: BleTimeout extends it.
        is BleTimeout -> t.message ?: "蓝牙写入超时"
        is IOException -> "蓝牙连接已断开"
        is SecurityException -> "缺少蓝牙权限"
        else -> t.message ?: t.javaClass.simpleName
    }

    private fun notifyChanged() {
        listener?.onChanged()
    }

    fun release() {
        pending.values.forEach { it.cancel() }
        pending.clear()
        ble.release()
        scope.cancel()
    }
}
