package com.first.ufotw

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

@SuppressLint("MissingPermission")
class BleManager(private val context: Context) : BleSender {

    companion object {
        private const val TAG = "BleManager"
        private const val SCAN_TIMEOUT_MS = 15_000L
    }

    enum class State { DISCONNECTED, SCANNING, CONNECTING, CONNECTED }

    var onStateChanged: ((State) -> Unit)? = null
    var onDeviceFound: ((BluetoothDevice) -> Unit)? = null

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter get() = bluetoothManager.adapter
    private val mainHandler = Handler(Looper.getMainLooper())

    private var gatt: BluetoothGatt? = null
    private var txChar: BluetoothGattCharacteristic? = null
    private var scanCallback: ScanCallback? = null

    @Volatile private var isWriting = false
    @Volatile private var pendingWrite: ByteArray? = null

    private var _state = State.DISCONNECTED
    var state: State
        get() = _state
        private set(value) {
            _state = value
            mainHandler.post { onStateChanged?.invoke(value) }
        }

    fun isBluetoothEnabled() = adapter.isEnabled

    // ── Scan ──────────────────────────────────────────────────────────────────

    fun startScan() {
        state = State.SCANNING
        val scanner = adapter.bluetoothLeScanner ?: run {
            state = State.DISCONNECTED
            return
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.device.name ?: result.scanRecord?.deviceName ?: return
                Log.d(TAG, "Found: $name / ${result.device.address}")
                if (name != "UFO TW" && name != "UFO-TW") return
                mainHandler.post { onDeviceFound?.invoke(result.device) }
            }
            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "Scan failed: $errorCode")
                mainHandler.post { state = State.DISCONNECTED }
            }
        }
        scanCallback = cb
        scanner.startScan(null, settings, cb)

        mainHandler.postDelayed({
            stopScan()
        }, SCAN_TIMEOUT_MS)
    }

    fun stopScan() {
        scanCallback?.let { adapter.bluetoothLeScanner?.stopScan(it) }
        scanCallback = null
        if (_state == State.SCANNING) state = State.DISCONNECTED
    }

    // ── Connect ───────────────────────────────────────────────────────────────

    fun connect(device: BluetoothDevice) {
        stopScan()
        state = State.CONNECTING
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        synchronized(this) { isWriting = false; pendingWrite = null }
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        txChar = null
        state = State.DISCONNECTED
    }

    // ── Write ─────────────────────────────────────────────────────────────────

    override fun send(bytes: ByteArray) {
        synchronized(this) {
            if (isWriting) { pendingWrite = bytes; return }
            isWriting = true
        }
        doWrite(bytes)
    }

    private fun doWrite(bytes: ByteArray) {
        val g = gatt ?: run { synchronized(this) { isWriting = false }; return }
        val ch = txChar ?: run { synchronized(this) { isWriting = false }; return }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(ch, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        } else {
            @Suppress("DEPRECATION")
            ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            ch.value = bytes
            @Suppress("DEPRECATION")
            g.writeCharacteristic(ch)
        }
    }

    // ── GATT callbacks ────────────────────────────────────────────────────────

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "Connected, discovering services")
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "Disconnected (status=$status)")
                    txChar = null
                    this@BleManager.gatt?.close()
                    this@BleManager.gatt = null
                    state = State.DISCONNECTED
                }
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int
        ) {
            val next = synchronized(this@BleManager) {
                val p = pendingWrite; pendingWrite = null; isWriting = p != null; p
            }
            if (next != null) doWrite(next)
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Service discovery failed: $status")
                disconnect()
                return
            }
            val service = gatt.getService(UfoTwProtocol.SERVICE_UUID)
            txChar = service?.getCharacteristic(UfoTwProtocol.TX_CHAR_UUID)
            if (txChar != null) {
                Log.d(TAG, "TX characteristic ready")
                state = State.CONNECTED
            } else {
                Log.e(TAG, "TX characteristic not found")
                disconnect()
            }
        }
    }
}
