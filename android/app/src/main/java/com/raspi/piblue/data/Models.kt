package com.raspi.piblue.data

import org.json.JSONArray
import org.json.JSONObject

/** Parsed forms of the JSON the Pi sends, plus tolerant helpers. */

data class Mem(
    val totalMb: Double?,
    val usedMb: Double?,
    val availableMb: Double?,
    val usedPct: Double?,
)

data class Disk(
    val path: String?,
    val totalGb: Double?,
    val usedGb: Double?,
    val freeGb: Double?,
    val usedPct: Double?,
)

data class Net(val iface: String?, val ipv4: String?)

data class Wifi(val ssid: String?, val signal: Int?)

data class Throttled(val flags: List<String>, val ok: Boolean)

data class SysInfo(
    val hostname: String?,
    val model: String?,
    val kernel: String?,
    val uptimeS: Long,
    val cpuTempC: Double?,
    val cpuCount: Int,
    val load1: Double,
    val load5: Double,
    val load15: Double,
    val mem: Mem?,
    val disk: Disk?,
    val net: Net?,
    val wifi: Wifi?,
    val throttled: Throttled?,
)

data class SensorMeta(val id: String, val name: String, val type: String, val unit: String)

data class SensorReading(
    val id: String,
    val name: String?,
    val unit: String?,
    val value: Double?,
    val values: Map<String, Double>,
    val error: String?,
)

data class ExecResult(
    val stdout: String,
    val stderr: String,
    val code: Int,
    val timedOut: Boolean,
    val truncated: Boolean,
    val durationMs: Long,
)

object Json {

    private fun JSONObject.num(key: String): Double? {
        if (!has(key) || isNull(key)) return null
        val v = optDouble(key, Double.NaN)
        return if (v.isNaN() || v.isInfinite()) null else v
    }

    private fun JSONObject.str(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key).takeIf { it.isNotBlank() && it != "null" }
    }

    fun sysInfo(data: JSONObject): SysInfo {
        val memObj = data.optJSONObject("mem")
        val diskObj = data.optJSONObject("disk")
        val netObj = data.optJSONObject("net")
        val wifiObj = data.optJSONObject("wifi")
        val thrObj = data.optJSONObject("throttled")
        val loadArr = data.optJSONArray("load")

        fun loadAt(index: Int): Double =
            loadArr?.optDouble(index, 0.0)?.takeIf { !it.isNaN() } ?: 0.0

        return SysInfo(
            hostname = data.str("hostname"),
            model = data.str("model"),
            kernel = data.str("kernel"),
            uptimeS = data.optLong("uptime_s", 0L),
            cpuTempC = data.num("cpu_temp_c"),
            cpuCount = data.optInt("cpu_count", 0),
            load1 = loadAt(0),
            load5 = loadAt(1),
            load15 = loadAt(2),
            mem = memObj?.let {
                Mem(
                    numOf(it, "total_mb"),
                    numOf(it, "used_mb"),
                    numOf(it, "available_mb"),
                    numOf(it, "used_pct"),
                )
            },
            disk = diskObj?.let {
                Disk(
                    it.str("path"),
                    numOf(it, "total_gb"),
                    numOf(it, "used_gb"),
                    numOf(it, "free_gb"),
                    numOf(it, "used_pct"),
                )
            },
            net = netObj?.let { Net(it.str("iface"), it.str("ipv4")) },
            wifi = wifiObj?.let { Wifi(it.str("ssid"), it.optInt("signal", 0)) },
            throttled = thrObj?.let { t ->
                val arr: JSONArray = t.optJSONArray("flags") ?: JSONArray()
                Throttled(
                    flags = (0 until arr.length()).map { arr.optString(it) },
                    ok = t.optBoolean("ok", true),
                )
            },
        )
    }

    private fun numOf(o: JSONObject, key: String): Double? = o.num(key)

    fun sensors(data: JSONObject): List<SensorMeta> {
        val arr = data.optJSONArray("sensors") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            SensorMeta(
                id = o.optString("id"),
                name = o.optString("name", o.optString("id")),
                type = o.optString("type", "value"),
                unit = o.optString("unit", ""),
            )
        }
    }

    fun reading(o: JSONObject): SensorReading {
        val values = mutableMapOf<String, Double>()
        when (val raw = o.opt("values")) {
            is JSONObject -> raw.keys().forEach { key ->
                val v = raw.optDouble(key, Double.NaN)
                if (!v.isNaN()) values[key] = v
            }
            is Number -> values["value"] = raw.toDouble()
        }
        val direct = o.optDouble("value", Double.NaN)
        return SensorReading(
            id = o.optString("id"),
            // org.json turns a null value into the literal string "null", so
            // check for absence explicitly instead of relying on a fallback.
            name = if (o.has("name") && !o.isNull("name")) o.optString("name") else null,
            unit = o.optString("unit", ""),
            value = if (direct.isNaN() || direct.isInfinite()) null else direct,
            values = values,
            error = if (o.has("error") && !o.isNull("error")) o.optString("error") else null,
        )
    }

    fun readings(data: JSONObject): List<SensorReading> {
        val arr = data.optJSONArray("sensors") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { reading(it) }
        }
    }

    fun exec(data: JSONObject): ExecResult = ExecResult(
        stdout = data.optString("stdout", ""),
        stderr = data.optString("stderr", ""),
        code = data.optInt("code", -1),
        timedOut = data.optBoolean("timed_out", false),
        truncated = data.optBoolean("truncated", false),
        durationMs = data.optLong("duration_ms", 0L),
    )
}
