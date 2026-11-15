package com.raspi.piblue.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * The control page is driven by the Pi, not by the app.
 *
 * `status` returns the list of actions that this particular Pi allows (config
 * `control.allow`) and is capable of (nmcli/wpa_cli present, lsblk present), so
 * the app never draws a button that is guaranteed to fail. Adding a new action
 * on the Pi side therefore needs no app release for the button to appear - only
 * for a nicer label.
 */

data class WifiStatus(
    val available: Boolean,
    val backend: String?,
    val iface: String?,
    val enabled: Boolean,
    val ssid: String?,
    val signal: Int?,
    val state: String?,
    /** SSIDs the Pi already has credentials for; these need no password. */
    val saved: List<String> = emptyList(),
)

data class WifiNetwork(
    val ssid: String,
    val signal: Int?,
    val security: String?,
    /** The Pi holds a profile for this SSID, so tapping can skip the password. */
    val saved: Boolean = false,
) {
    val secured: Boolean get() = !security.isNullOrBlank()
}

data class DiskEntry(
    val path: String,
    val name: String,
    val sizeBytes: Long,
    val fstype: String?,
    val label: String?,
    val mountpoint: String?,
    val canUnmount: Boolean,
    val removable: Boolean,
    val model: String?,
) {
    val mounted: Boolean get() = !mountpoint.isNullOrBlank()

    /**
     * NTFS in any of the spellings that reach us: `ntfs` from lsblk on an
     * unmounted volume, `ntfs3` when the kernel driver owns it, `fuseblk` when
     * ntfs-3g does. All three mean the Pi will mount it with `-t ntfs3`.
     */
    val ntfs: Boolean
        get() = fstype?.trim()?.lowercase() in setOf("ntfs", "ntfs3", "fuseblk")
}

data class CustomAction(
    val index: Int,
    val name: String,
    /** What tapping it will run. Shown in the confirmation, not editable here. */
    val line: String = "",
)

data class ControlStatus(
    val enabled: Boolean,
    val actions: Set<String>,
    val wifi: WifiStatus?,
    val disks: List<DiskEntry>,
    val custom: List<CustomAction>,
    val mountRoot: String?,
    val diskError: String?,
    /** How many commands the Pi will store; 5 unless the agent says otherwise. */
    val customMax: Int = 5,
    /** True when the Pi lets this phone add and remove custom commands. */
    val canEditCustom: Boolean = false,
) {
    fun allows(action: String): Boolean = actions.contains(action)

    fun isEmpty(): Boolean = actions.isEmpty()

    /** The card is worth drawing even with zero saved commands, to add one. */
    val showsCustom: Boolean
        get() = canEditCustom || (allows("custom") && custom.isNotEmpty())

    val canAddCustom: Boolean
        get() = canEditCustom && custom.size < customMax

    /** Where a mount named [name] would land, for the dialog's preview. */
    fun mountPathFor(name: String): String {
        val root = (mountRoot ?: "/mnt").trimEnd('/')
        return "$root/$name"
    }
}

object ControlJson {

    private fun JSONObject.str(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key).takeIf { it.isNotBlank() && it != "null" }
    }

    private fun JSONObject.int(key: String): Int? {
        if (!has(key) || isNull(key)) return null
        val v = optInt(key, Int.MIN_VALUE)
        return if (v == Int.MIN_VALUE) null else v
    }

    private fun JSONObject.strings(key: String): List<String> {
        val arr = optJSONArray(key) ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotBlank) }
    }

    fun status(data: JSONObject): ControlStatus {
        val actions = mutableSetOf<String>()
        data.optJSONArray("actions")?.let { arr ->
            for (i in 0 until arr.length()) actions.add(arr.optString(i))
        }

        val wifiObj = data.optJSONObject("wifi")
        val wifi = wifiObj?.let {
            WifiStatus(
                available = it.optBoolean("available", false),
                backend = it.str("backend"),
                iface = it.str("iface"),
                enabled = it.optBoolean("enabled", true),
                ssid = it.str("ssid"),
                signal = it.int("signal"),
                state = it.str("state"),
                saved = it.strings("saved"),
            )
        }

        val custom = mutableListOf<CustomAction>()
        data.optJSONArray("custom")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                custom.add(
                    CustomAction(
                        index = o.optInt("index", i),
                        name = o.optString("name"),
                        line = o.optString("line"),
                    )
                )
            }
        }

        val errors = data.optJSONObject("errors")

        return ControlStatus(
            enabled = data.optBoolean("enabled", true),
            actions = actions,
            wifi = wifi,
            disks = disks(data),
            custom = custom,
            mountRoot = data.str("mount_root"),
            diskError = errors?.str("disk"),
            customMax = data.optInt("custom_max", 5).coerceAtLeast(1),
            canEditCustom = actions.contains("custom-save"),
        )
    }

    fun disks(data: JSONObject): List<DiskEntry> = parseDisks(data.optJSONArray("disks"))

    private fun parseDisks(arr: JSONArray?): List<DiskEntry> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val path = o.str("path") ?: return@mapNotNull null
            DiskEntry(
                path = path,
                name = o.str("name") ?: path,
                sizeBytes = o.optLong("size", 0L),
                fstype = o.str("fstype"),
                label = o.str("label"),
                mountpoint = o.str("mountpoint"),
                canUnmount = o.optBoolean("can_unmount", false),
                removable = o.optBoolean("removable", false),
                model = o.str("model"),
            )
        }
    }

    fun networks(data: JSONObject): List<WifiNetwork> {
        val arr = data.optJSONArray("networks") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val ssid = o.str("ssid") ?: return@mapNotNull null
            WifiNetwork(
                ssid = ssid,
                signal = o.int("signal"),
                security = o.str("security"),
                saved = o.optBoolean("saved", false),
            )
        }
    }
}
