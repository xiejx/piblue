package com.raspi.piblue.ble

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * BLE GATT client for the Pi agent.
 *
 * Two things make this code longer than the naive version:
 *
 * 1. **Chunking.** A GATT write is limited to MTU-3 bytes, so a long shell
 *    command has to be split and sent one chunk at a time, waiting for the
 *    write callback before queueing the next one (Android's GATT only allows a
 *    single outstanding operation).
 * 2. **Byte-level reassembly.** Notifications arrive in arbitrary slices and
 *    the Pi sends UTF-8 with Chinese labels, so slicing mid-character is a real
 *    risk. We buffer bytes and only decode when a whole line is complete.
 */
/**
 * The GATT write itself never got an answer from the phone's own Bluetooth
 * stack. Different from "the Pi did not answer the command": this one fails
 * before the frame has even left the phone, and telling the two apart is the
 * difference between debugging the app and debugging the Pi.
 */
class BleTimeout(message: String) : IOException(message)

class BleClient(private val appContext: Context) {

    sealed interface State {
        data object Idle : State
        data object Scanning : State
        data class Connecting(val name: String) : State
        data class Connected(val name: String, val mtu: Int) : State
        data class Failed(val message: String) : State
    }

    data class Hit(val address: String, val name: String, val rssi: Int)

    private val tag = "PiBlue/Ble"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _hits = MutableStateFlow<List<Hit>>(emptyList())
    val hits: StateFlow<List<Hit>> = _hits.asStateFlow()

    private val _frames = MutableSharedFlow<String>(extraBufferCapacity = 256)
    val frames: SharedFlow<String> = _frames.asSharedFlow()

    private val adapter: BluetoothAdapter?
        get() = appContext.getSystemService(BluetoothManager::class.java)?.adapter

    private var gatt: BluetoothGatt? = null
    private var rxChar: BluetoothGattCharacteristic? = null
    private var txChar: BluetoothGattCharacteristic? = null
    private var scanCallback: ScanCallback? = null
    private var connectTimeout: Job? = null

    private var mtu = DEFAULT_MTU
    private var pendingWrite: CancellableContinuation<Unit>? = null
    private val writeLock = Mutex()
    private val inbound = ByteArrayOutputStream()
    private var peerName = ""

    /** Name hint so a renamed Pi still shows up before its UUID is parsed. */
    var nameHint: String = "PiBlue"

    // --------------------------------------------------------------- permissions

    fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    fun hasPermissions(): Boolean = requiredPermissions().all {
        ContextCompat.checkSelfPermission(appContext, it) == PackageManager.PERMISSION_GRANTED
    }

    // ------------------------------------------------------------------- scanning

    fun startScan() {
        val bt = adapter ?: return fail("这台设备没有蓝牙适配器")
        if (!bt.isEnabled) return fail("请先打开手机蓝牙")
        if (!hasPermissions()) return fail("缺少蓝牙权限，请授予后重试")

        stopScan()
        _hits.value = emptyList()
        _state.value = State.Scanning

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val record = result.scanRecord
                val advertised = record?.serviceUuids?.map { it.uuid }.orEmpty()
                val name = result.device.safeName() ?: record?.deviceName
                val matches = advertised.contains(Uuids.SERVICE) ||
                    (name?.contains(nameHint, ignoreCase = true) == true)
                if (!matches || name.isNullOrBlank()) return

                val hit = Hit(result.device.address, name, result.rssi)
                _hits.update { list ->
                    (list.filterNot { it.address == hit.address } + hit)
                        .sortedByDescending { it.rssi }
                }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.w(tag, "scan failed: $errorCode")
                fail("扫描失败（错误码 $errorCode），请确认蓝牙已打开")
            }
        }
        scanCallback = callback

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        // No ScanFilter: some phones ignore the service-UUID filter for
        // non-connectable advertisements, and filtering by hand costs nothing.
        val scanner = bt.bluetoothLeScanner
        if (scanner == null) return fail("蓝牙扫描器不可用")
        scanner.startScan(null, settings, callback)
    }

    fun stopScan() {
        scanCallback?.let { cb ->
            runCatching { adapter?.bluetoothLeScanner?.stopScan(cb) }
        }
        scanCallback = null
        if (_state.value is State.Scanning) _state.value = State.Idle
    }

    // ------------------------------------------------------------------ connecting

    fun connect(hit: Hit) = connect(hit.address, hit.name)

    fun connect(address: String, name: String) {
        val bt = adapter ?: return fail("这台设备没有蓝牙适配器")
        if (!hasPermissions()) return fail("缺少蓝牙权限")

        stopScan()
        teardown()

        peerName = name
        _state.value = State.Connecting(name)

        val device = try {
            bt.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            return fail("无效的设备地址 $address")
        }

        gatt = device.connectGatt(appContext, false, callbacks, BluetoothDevice.TRANSPORT_LE)

        if (gatt == null) return fail("无法建立连接")

        connectTimeout?.cancel()
        connectTimeout = scope.launch {
            delay(CONNECT_TIMEOUT_MS)
            if (_state.value is State.Connecting) {
                fail("连接超时，请确认树莓派已开机并在广播")
                teardown()
            }
        }
    }

    fun disconnect() {
        teardown()
        _state.value = State.Idle
    }

    private fun teardown() {
        connectTimeout?.cancel()
        connectTimeout = null
        pendingWrite?.let { if (it.isActive) it.cancel() }
        pendingWrite = null

        gatt?.let { g ->
            runCatching { g.disconnect() }
            runCatching { g.close() }
        }
        gatt = null
        rxChar = null
        txChar = null
        mtu = DEFAULT_MTU
        synchronized(inbound) { inbound.reset() }
    }

    private fun fail(message: String) {
        _state.value = State.Failed(message)
    }

    // ------------------------------------------------------------------ callbacks

    private val callbacks = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            Log.d(tag, "connection state=$newState status=$status")
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                if (!g.discoverServices()) fail("无法发现服务")
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val wasConnected = _state.value is State.Connected
                teardown()
                if (wasConnected) {
                    fail("连接已断开")
                } else if (_state.value is State.Connecting) {
                    fail("连接被拒绝（status=$status）")
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return fail("服务发现失败（status=$status）")

            val service = g.getService(Uuids.SERVICE)
                ?: return fail("这台设备上没有 PiBlue 服务，确认树莓派端 agent 已启动")

            rxChar = service.getCharacteristic(Uuids.RX_WRITE)
            txChar = service.getCharacteristic(Uuids.TX_NOTIFY)
            if (rxChar == null || txChar == null) return fail("特征值缺失，服务实现与 App 不匹配")

            // A bigger MTU means fewer chunks; falling back is harmless.
            if (!g.requestMtu(REQUESTED_MTU)) enableNotifications(g)
        }

        override fun onMtuChanged(g: BluetoothGatt, mtuSize: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) mtu = mtuSize
            Log.d(tag, "mtu=$mtuSize status=$status")
            enableNotifications(g)
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (descriptor.uuid != Uuids.CCCD) return
            connectTimeout?.cancel()
            if (status == BluetoothGatt.GATT_SUCCESS) {
                _state.value = State.Connected(peerName, mtu)
                Log.i(tag, "ready: $peerName mtu=$mtu")
            } else {
                fail("订阅通知失败（status=$status）")
            }
        }

        /**
         * API 33+ dispatch. On Android 13 and newer the framework delivers
         * notifications to this three-argument overload and does *not* forward
         * them to the deprecated two-argument one. Overriding only the old
         * signature gives the worst possible failure mode: the scan finds the
         * Pi, the connection completes, the CCCD write succeeds, the state even
         * flips to Connected - and then not a single frame ever arrives, so
         * every command times out.
         *
         * The reverse hole exists too (a few OEM stacks still call only the old
         * one), so both are implemented. If a stack calls both, the duplicate
         * frame is harmless: responses are matched by id and the second copy of
         * an id is dropped by the pending map.
         */
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            emitFrames(value)
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val bytes = characteristic.value ?: return
            emitFrames(bytes)
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            val cont = pendingWrite
            pendingWrite = null
            if (cont == null || !cont.isActive) return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                cont.resume(Unit)
            } else {
                cont.resumeWithException(IOException("写入失败（status=$status）"))
            }
        }
    }

    private fun enableNotifications(g: BluetoothGatt) {
        val tx = txChar ?: return fail("TX 特征值缺失")
        if (!g.setCharacteristicNotification(tx, true)) return fail("无法开启通知")

        val descriptor = tx.getDescriptor(Uuids.CCCD) ?: return fail("CCCD 描述符缺失")
        val enabled = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(descriptor, enabled) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                descriptor.value = enabled
                g.writeDescriptor(descriptor)
            }
        }
        if (!ok) fail("写入 CCCD 失败")
    }

    // --------------------------------------------------------------- reassembly

    private fun emitFrames(bytes: ByteArray) {
        val complete = mutableListOf<String>()
        synchronized(inbound) {
            inbound.write(bytes)
            val buf = inbound.toByteArray()
            var start = 0
            for (i in buf.indices) {
                if (buf[i] == NEWLINE) {
                    if (i > start) {
                        complete += String(buf, start, i - start, Charsets.UTF_8).trim()
                    }
                    start = i + 1
                }
            }
            if (start > 0) {
                inbound.reset()
                inbound.write(buf, start, buf.size - start)
            }
            if (inbound.size() > MAX_BUFFER) inbound.reset()
        }
        complete.filter { it.isNotEmpty() }.forEach { _frames.tryEmit(it) }
    }

    // -------------------------------------------------------------------- writing

    /** Sends one newline-terminated JSON frame, chunked to fit the MTU. */
    suspend fun send(json: String) {
        val payload = (json.trimEnd('\n') + "\n").toByteArray(Charsets.UTF_8)
        writeLock.withLock {
            val chunkSize = (mtu - ATT_HEADER).coerceIn(MIN_CHUNK, MAX_CHUNK)
            var offset = 0
            while (offset < payload.size) {
                val end = minOf(offset + chunkSize, payload.size)
                writeChunk(payload.copyOfRange(offset, end))
                offset = end
            }
        }
    }

    private suspend fun writeChunk(chunk: ByteArray) {
        val g = gatt ?: throw IOException("未连接")
        val characteristic = rxChar ?: throw IOException("RX 特征值未就绪")

        try {
            withTimeout(WRITE_TIMEOUT_MS) {
                suspendCancellableCoroutine<Unit> { cont ->
                    pendingWrite = cont
                    val queued = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        g.writeCharacteristic(
                            characteristic,
                            chunk,
                            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                        ) == BluetoothStatusCodes.SUCCESS
                    } else {
                        @Suppress("DEPRECATION")
                        run {
                            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                            characteristic.value = chunk
                            g.writeCharacteristic(characteristic)
                        }
                    }
                    if (!queued) {
                        pendingWrite = null
                        cont.resumeWithException(IOException("写入请求被拒绝"))
                    }
                    cont.invokeOnCancellation { pendingWrite = null }
                }
            }
        } catch (e: TimeoutCancellationException) {
            pendingWrite = null
            // A real timeout (5 s) is far shorter than the stack's own ATT
            // timeout, so reaching here means the write never completed.
            if (!currentCoroutineContext().isActive) throw e
            throw BleTimeout("写入超时：数据没有发出去（手机侧 GATT 无响应）")
        }
    }

    private fun BluetoothDevice.safeName(): String? = try {
        name
    } catch (e: SecurityException) {
        null
    }

    fun release() {
        teardown()
        scope.coroutineContext[Job]?.cancel()
    }

    private companion object {
        const val DEFAULT_MTU = 23
        const val REQUESTED_MTU = 517
        const val ATT_HEADER = 3
        const val MIN_CHUNK = 20
        const val MAX_CHUNK = 512
        const val WRITE_TIMEOUT_MS = 5_000L
        const val CONNECT_TIMEOUT_MS = 15_000L
        const val MAX_BUFFER = 64 * 1024
        const val NEWLINE: Byte = 0x0A
    }
}
