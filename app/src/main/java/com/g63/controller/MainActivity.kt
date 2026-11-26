package com.g63.controller

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvBrightness: TextView
    private lateinit var tvLog: TextView
    private lateinit var btnScan: Button
    private lateinit var spinnerProtocol: Spinner
    private lateinit var etRawHex: EditText

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var writableCharacteristic: BluetoothGattCharacteristic? = null

    private val discoveredDevices = mutableListOf<BluetoothDevice>()
    private val discoveredNames = mutableListOf<String>()
    private var isScanning = false
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvBrightness = findViewById(R.id.tvBrightness)
        tvLog = findViewById(R.id.tvLog)
        btnScan = findViewById(R.id.btnScan)
        spinnerProtocol = findViewById(R.id.spinnerProtocol)
        etRawHex = findViewById(R.id.etRawHex)

        val protocols = arrayOf(
            "ELK-BLEDOM (Standar G63)",
            "JieLi / Lotus Lantern (AA-55)"
        )
        spinnerProtocol.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            protocols
        )

        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = btManager.adapter

        setupControls()
    }

    private fun setupControls() {
        btnScan.setOnClickListener {
            if (checkPermissions()) {
                startBleScan()
            }
        }

        findViewById<Button>(R.id.btnPowerOn).setOnClickListener {
            sendCommand(buildPowerPacket(true))
        }
        findViewById<Button>(R.id.btnPowerOff).setOnClickListener {
            sendCommand(buildPowerPacket(false))
        }

        findViewById<Button>(R.id.btnColorRed).setOnClickListener { sendCommand(buildColorPacket(255, 0, 0)) }
        findViewById<Button>(R.id.btnColorGreen).setOnClickListener { sendCommand(buildColorPacket(0, 255, 0)) }
        findViewById<Button>(R.id.btnColorBlue).setOnClickListener { sendCommand(buildColorPacket(0, 0, 255)) }
        findViewById<Button>(R.id.btnColorPurple).setOnClickListener { sendCommand(buildColorPacket(180, 0, 255)) }
        findViewById<Button>(R.id.btnColorCyan).setOnClickListener { sendCommand(buildColorPacket(0, 255, 255)) }
        findViewById<Button>(R.id.btnColorWarm).setOnClickListener { sendCommand(buildColorPacket(255, 160, 40)) }

        findViewById<Button>(R.id.btnModeRainbow).setOnClickListener {
            sendCommand(buildModePacket(0x87))
        }
        findViewById<Button>(R.id.btnModeBreath).setOnClickListener {
            sendCommand(buildModePacket(0x8B))
        }

        findViewById<SeekBar>(R.id.seekBrightness).setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvBrightness.text = "Kecerahan: $progress%"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val level = seekBar?.progress ?: 100
                sendCommand(buildBrightnessPacket(level))
            }
        })

        findViewById<Button>(R.id.btnSendHex).setOnClickListener {
            val raw = etRawHex.text.toString().replace(" ", "").trim()
            if (raw.isNotEmpty() && raw.length % 2 == 0) {
                sendCommand(hexStringToBytes(raw))
            } else {
                appendLog("Format Hex tidak valid (harus genap).")
            }
        }
    }

    private fun checkPermissions(): Boolean {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 101)
            return false
        }
        return true
    }

    @SuppressLint("MissingPermission")
    private fun startBleScan() {
        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null) {
            appendLog("Bluetooth mati atau tidak mendukung BLE.")
            return
        }
        if (isScanning) return

        discoveredDevices.clear()
        discoveredNames.clear()
        isScanning = true
        btnScan.text = "Memindai (4 detik)..."
        appendLog("Memulai pemindaian BLE...")

        scanner.startScan(scanCallback)
        handler.postDelayed({
            scanner.stopScan(scanCallback)
            isScanning = false
            btnScan.text = "Scan & Hubungkan G63 (BLE)"
            showDevicePickerDialog()
        }, 4000)
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            if (!discoveredDevices.any { it.address == device.address }) {
                val name = device.name ?: result.scanRecord?.deviceName ?: "Perangkat Tanpa Nama"
                discoveredDevices.add(device)
                discoveredNames.add("$name\n(${device.address})")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun showDevicePickerDialog() {
        if (discoveredDevices.isEmpty()) {
            appendLog("Tidak ada perangkat BLE ditemukan. Pastikan G63 menyala.")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Pilih Perangkat BLE")
            .setItems(discoveredNames.toTypedArray()) { _, which ->
                val target = discoveredDevices[which]
                appendLog("Menghubungkan ke ${target.name ?: target.address}...")
                tvStatus.text = "Status: Menghubungkan..."
                bluetoothGatt?.close()
                bluetoothGatt = target.connectGatt(this, false, gattCallback)
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                runOnUiThread {
                    tvStatus.text = "Status: Terhubung (${gatt.device.name ?: gatt.device.address})"
                    appendLog("Terhubung! Mencari GATT Service...")
                }
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                writableCharacteristic = null
                runOnUiThread {
                    tvStatus.text = "Status: Terputus"
                    appendLog("Koneksi BLE terputus.")
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return

            var foundChar: BluetoothGattCharacteristic? = null
            for (service in gatt.services) {
                val sUuid = service.uuid.toString().lowercase()
                runOnUiThread { appendLog("Service: ${sUuid.substring(4, 8)}") }

                for (char in service.characteristics) {
                    val props = char.properties
                    val canWrite = (props and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0 ||
                                   (props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                    if (canWrite) {
                        val cUuid = char.uuid.toString().lowercase()
                        if (cUuid.contains("fff3") || cUuid.contains("ffe1") || cUuid.contains("ae01")) {
                            foundChar = char
                            break
                        } else if (foundChar == null) {
                            foundChar = char
                        }
                    }
                }
            }

            writableCharacteristic = foundChar
            runOnUiThread {
                if (foundChar != null) {
                    appendLog("SIAP! Target Write UUID: ${foundChar.uuid.toString().substring(4, 8)}")
                } else {
                    appendLog("PERINGATAN: Tidak ada jalur kontrol LED ditemukan (kemungkinan G63 versi audio-only).")
                }
            }
        }
    }

    private fun isJieLiProtocol(): Boolean = spinnerProtocol.selectedItemPosition == 1

    private fun buildPowerPacket(on: Boolean): ByteArray {
        return if (isJieLiProtocol()) {
            byteArrayOf(0xAA.toByte(), 0x01, if (on) 0x01 else 0x00, 0x00, 0x00, 0x55)
        } else {
            byteArrayOf(0x7E, 0x00, 0x04, if (on) 0xF0.toByte() else 0x00, 0x00, 0x01, 0xFF.toByte(), 0x00, 0xEF.toByte())
        }
    }

    private fun buildColorPacket(r: Int, g: Int, b: Int): ByteArray {
        return if (isJieLiProtocol()) {
            byteArrayOf(0xAA.toByte(), 0x02, r.toByte(), g.toByte(), b.toByte(), 0x55)
        } else {
            byteArrayOf(0x7E, 0x00, 0x05, 0x03, r.toByte(), g.toByte(), b.toByte(), 0x00, 0xEF.toByte())
        }
    }

    private fun buildBrightnessPacket(percent: Int): ByteArray {
        val valByte = ((percent.coerceIn(0, 100) * 255) / 100).toByte()
        return if (isJieLiProtocol()) {
            byteArrayOf(0xAA.toByte(), 0x03, valByte, 0x00, 0x00, 0x55)
        } else {
            byteArrayOf(0x7E, 0x00, 0x01, percent.coerceIn(0, 100).toByte(), 0x00, 0x00, 0x00, 0x00, 0xEF.toByte())
        }
    }

    private fun buildModePacket(modeCode: Int): ByteArray {
        return if (isJieLiProtocol()) {
            byteArrayOf(0xAA.toByte(), 0x04, modeCode.toByte(), 0x00, 0x00, 0x55)
        } else {
            byteArrayOf(0x7E, 0x00, 0x03, modeCode.toByte(), 0x03, 0x00, 0x00, 0x00, 0xEF.toByte())
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendCommand(bytes: ByteArray) {
        val gatt = bluetoothGatt
        val char = writableCharacteristic
        if (gatt == null || char == null) {
            appendLog("Gagal kirim: Belum terhubung ke jalur kontrol LED.")
            return
        }

        char.writeType = if ((char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }
        char.value = bytes
        val success = gatt.writeCharacteristic(char)
        val hexStr = bytes.joinToString(" ") { "%02X".format(it) }
        appendLog("TX [$success]: $hexStr")
    }

    private fun hexStringToBytes(hex: String): ByteArray {
        return ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    private fun appendLog(msg: String) {
        tvLog.append("> $msg\n")
    }

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        super.onDestroy()
        bluetoothGatt?.close()
    }
}
