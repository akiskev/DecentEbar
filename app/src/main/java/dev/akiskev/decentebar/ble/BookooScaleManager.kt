package dev.akiskev.decentebar.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

data class ScaleReading(
    val timestampMs: Long,
    val weightG: Double,
    val flowGps: Double,
    val batteryPercent: Int
)

enum class ScaleConnectionState {
    DISCONNECTED, SCANNING, CONNECTING, CONNECTED, ERROR
}

@SuppressLint("MissingPermission")
class BookooScaleManager(private val context: Context) {

    companion object {
        private val SERVICE_UUID = UUID.fromString("00000FFE-0000-1000-8000-00805F9B34FB")
        private val WEIGHT_CHAR_UUID = UUID.fromString("0000FF11-0000-1000-8000-00805F9B34FB")
        private val COMMAND_CHAR_UUID = UUID.fromString("0000FF12-0000-1000-8000-00805F9B34FB")
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

        // Fixed command bytes as documented in the Bookoo Mini protocol spec
        private val CMD_TARE = byteArrayOf(0x03, 0x0A, 0x01, 0x00, 0x00, 0x08)
        private val CMD_START_TIMER = byteArrayOf(0x03, 0x0A, 0x04, 0x00, 0x00, 0x0A)
        // Flow smoothing on: checksum = 0x03^0x0A^0x08^0x00^0x01 = 0x00
        private val CMD_FLOW_SMOOTHING_ON = byteArrayOf(0x03, 0x0A, 0x08, 0x00, 0x01, 0x00)

        // Stop scanning and report failure if no Bookoo scale is found in this window.
        private const val SCAN_TIMEOUT_MS = 15_000L
        private const val COMMAND_GAP_MS = 150L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var scanTimeoutJob: Job? = null

    private val _connectionState = MutableStateFlow(ScaleConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ScaleConnectionState> = _connectionState.asStateFlow()

    private val _latestReading = MutableStateFlow<ScaleReading?>(null)
    val latestReading: StateFlow<ScaleReading?> = _latestReading.asStateFlow()

    private var gatt: BluetoothGatt? = null
    private var scanner = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
        ?.adapter?.bluetoothLeScanner

    fun startScan() {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            _connectionState.value = ScaleConnectionState.ERROR
            return
        }
        scanner = adapter.bluetoothLeScanner
        _connectionState.value = ScaleConnectionState.SCANNING

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner?.startScan(listOf(filter), settings, scanCallback)

        // Don't sit in SCANNING forever if no scale is in range.
        scanTimeoutJob?.cancel()
        scanTimeoutJob = scope.launch {
            delay(SCAN_TIMEOUT_MS)
            if (_connectionState.value == ScaleConnectionState.SCANNING) {
                scanner?.stopScan(scanCallback)
                _connectionState.value = ScaleConnectionState.ERROR
            }
        }
    }

    fun disconnect() {
        scanTimeoutJob?.cancel()
        scanner?.stopScan(scanCallback)
        gatt?.disconnect()
        // close() is called in onConnectionStateChange after STATE_DISCONNECTED
        _connectionState.value = ScaleConnectionState.DISCONNECTED
        _latestReading.value = null
    }

    fun close() {
        disconnect()
        scanTimeoutJob?.cancel()
        gatt?.close()
        gatt = null
    }

    /** Send start timer command so the scale begins computing flow rate. */
    fun startTimer() = sendCommand(CMD_START_TIMER)

    /** Zero the scale at the current load. */
    fun tare() = sendCommand(CMD_TARE)

    /** Tare first, then start the scale timer/flow calculation for the shot. */
    fun prepareForShotStart() {
        scope.launch {
            tare()
            delay(COMMAND_GAP_MS)
            startTimer()
        }
    }

    private fun sendCommand(bytes: ByteArray) {
        val g = gatt ?: return
        val service = g.getService(SERVICE_UUID) ?: return
        val char = service.getCharacteristic(COMMAND_CHAR_UUID) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(char, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        } else {
            @Suppress("DEPRECATION")
            char.value = bytes
            @Suppress("DEPRECATION")
            g.writeCharacteristic(char)
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            scanTimeoutJob?.cancel()
            scanner?.stopScan(this)
            _connectionState.value = ScaleConnectionState.CONNECTING
            result.device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        }

        override fun onScanFailed(errorCode: Int) {
            scanTimeoutJob?.cancel()
            _connectionState.value = ScaleConnectionState.ERROR
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            this@BookooScaleManager.gatt = gatt
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> gatt.discoverServices()
                BluetoothProfile.STATE_DISCONNECTED -> {
                    gatt.close()
                    if (this@BookooScaleManager.gatt === gatt) this@BookooScaleManager.gatt = null
                    _connectionState.value = ScaleConnectionState.DISCONNECTED
                    _latestReading.value = null
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _connectionState.value = ScaleConnectionState.ERROR
                return
            }
            val weightChar = gatt.getService(SERVICE_UUID)?.getCharacteristic(WEIGHT_CHAR_UUID)
            if (weightChar == null) {
                _connectionState.value = ScaleConnectionState.ERROR
                return
            }
            gatt.setCharacteristicNotification(weightChar, true)
            val descriptor = weightChar.getDescriptor(CCCD_UUID) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(descriptor)
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.uuid == CCCD_UUID && status == BluetoothGatt.GATT_SUCCESS) {
                _connectionState.value = ScaleConnectionState.CONNECTED
                // Enable flow smoothing immediately so flow data is ready when the shot starts
                sendCommand(CMD_FLOW_SMOOTHING_ON)
            }
        }

        // API < 33
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU &&
                characteristic.uuid == WEIGHT_CHAR_UUID
            ) {
                parseWeightData(characteristic.value ?: return)
            }
        }

        // API 33+
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid == WEIGHT_CHAR_UUID) {
                parseWeightData(value)
            }
        }
    }

    private fun parseWeightData(data: ByteArray) {
        if (data.size < 20) return
        if (data[0] != 0x03.toByte() || data[1] != 0x0B.toByte()) return

        // XOR checksum: bytes[0..18] XORed must equal bytes[19]
        val computed = (0 until 19).fold(0) { acc, i -> acc xor (data[i].toInt() and 0xFF) }
        if (computed != (data[19].toInt() and 0xFF)) return

        // Weight: byte[6] = sign (0x00 = negative/below-tare, non-zero = positive)
        //         bytes[7..9] = weight*100, 3-byte big-endian unsigned int
        val weightSign = if (data[6].toInt() and 0xFF == 0) -1.0 else 1.0
        val weightRaw = ((data[7].toInt() and 0xFF) shl 16) or
                ((data[8].toInt() and 0xFF) shl 8) or
                (data[9].toInt() and 0xFF)
        val weightG = weightSign * weightRaw / 100.0

        // Flow: byte[10] = sign (same convention), bytes[11..12] = flow*100, 2-byte big-endian
        val flowSign = if (data[10].toInt() and 0xFF == 0) -1.0 else 1.0
        val flowRaw = ((data[11].toInt() and 0xFF) shl 8) or (data[12].toInt() and 0xFF)
        val flowGps = flowSign * flowRaw / 100.0

        val batteryPercent = data[13].toInt() and 0xFF

        _latestReading.value = ScaleReading(
            timestampMs = System.currentTimeMillis(),
            weightG = weightG,
            flowGps = flowGps,
            batteryPercent = batteryPercent
        )
    }
}
