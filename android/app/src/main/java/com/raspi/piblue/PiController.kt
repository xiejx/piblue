package com.raspi.piblue

import android.content.Context
import com.raspi.piblue.ble.BleClient
import com.raspi.piblue.ble.BleTimeout
import com.raspi.piblue.data.Json
import com.raspi.piblue.data.SensorMeta
import com.raspi.piblue.data.SensorReading
import com.raspi.piblue.data.SysInfo
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
import kotlinx.coroutines.withTimeout
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
    }

    var listener: Listener? = null

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

    var autoRefresh: Boolean = true
        private set

    val isConnected: Boolean
        get() = state is BleClient.State.Connected

    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JSONObject>>()
    private val nextId = AtomicLong(1L)
    private var subJob: Job? = null

    init {
        scope.launch {
            ble.state.collect { incoming ->
                state = incoming
                when (incoming) {
                    is BleClient.State.Connected -> {
                        addLine("已连接 ${incoming.name}，MTU ${incoming.mtu}", LineKind.Note)
                        if (autoRefresh) startSubscription()
                        // Handshake first, and hand over the MTU while we are at
                        // it: the Pi cannot read the negotiated MTU out of BlueZ,
                        // and it must not send a notification bigger than MTU-3.
                        // Without this the first `info` reply (566 bytes) is
                        // silently dropped by bluetoothd and shows up here as a
                        // plain timeout.
                        scope.launch {
                            runCatching {
                                call("hello", 5_000) { put("mtu", incoming.mtu) }
                            }
                            refreshInfo()
                            refreshSensors()
                        }
                    }

                    is BleClient.State.Failed -> {
                        addLine(incoming.message, LineKind.Error)
                        listener?.onError(incoming.message)
                    }

                    else -> Unit
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
        ble.connect(hit)
        notifyChanged()
    }

    fun disconnect() {
        stopSubscription()
        ble.disconnect()
        addLine("已断开连接", LineKind.Note)
        notifyChanged()
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

    fun setAutoRefresh(enabled: Boolean) {
        autoRefresh = enabled
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

                info = base.copy(
                    cpuTempC = temp ?: base.cpuTempC,
                    load1 = data.optDouble("load1", base.load1),
                    uptimeS = data.optLong("uptime_s", base.uptimeS),
                    mem = if (mem != null && memPct != null) mem.copy(usedPct = memPct) else mem,
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
    ): JSONObject {
        val id = nextId.getAndIncrement()
        val deferred = CompletableDeferred<JSONObject>()
        pending[id] = deferred
        try {
            val message = JSONObject().put("id", id).put("cmd", cmd).apply(build)
            ble.send(message.toString())
            val response = withTimeout(timeoutMs) { deferred.await() }
            if (!response.optBoolean("ok", false)) {
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
