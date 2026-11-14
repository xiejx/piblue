package com.raspi.piblue.ble

import java.util.UUID

/**
 * Must match `pi-agent/agent/ble_server.py`.
 *
 * Nordic-UART layout: the phone writes commands to RX and receives newline
 * terminated JSON on TX via notifications.
 */
object Uuids {
    val SERVICE: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
    val RX_WRITE: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
    val TX_NOTIFY: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")

    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}
