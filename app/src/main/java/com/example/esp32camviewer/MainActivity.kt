package com.example.esp32camviewer   // ganti sesuai nama package project kamu

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.util.UUID

@SuppressLint("MissingPermission")
class MainActivity : AppCompatActivity() {

    private val sppUuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private val magic = intArrayOf(0xAA, 0x55, 0xAA, 0x55)

    private var adapter: BluetoothAdapter? = null
    private var socket: BluetoothSocket? = null
    @Volatile private var running = false
    private var devices: List<BluetoothDevice> = emptyList()

    private lateinit var imageView: ImageView
    private lateinit var statusText: TextView
    private lateinit var spinner: Spinner

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) loadPairedDevices()
        else toast("Izin Bluetooth ditolak")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        buildUi()
        if (adapter == null) {
            setStatus("Perangkat tidak mendukung Bluetooth")
            return
        }
        checkPermissionsAndLoad()
    }

    // ---------- UI (tanpa XML) ----------
    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(24, 24, 24, 24)
        }

        statusText = TextView(this).apply {
            setTextColor(Color.WHITE)
            text = "Belum terhubung"
        }
        spinner = Spinner(this)

        val rowConnect = row(
            button("Refresh") { checkPermissionsAndLoad() },
            button("Connect") { connect() },
            button("Putus") { disconnect("Terputus") }
        )
        val rowCmd = row(
            button("Flash") { sendCmd('F') },
            button("160p") { sendCmd('q') },
            button("QVGA") { sendCmd('Q') },
            button("VGA") { sendCmd('V') }
        )

        imageView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }

        root.addView(statusText)
        root.addView(spinner)
        root.addView(rowConnect)
        root.addView(rowCmd)
        root.addView(imageView)
        setContentView(root)
    }

    private fun row(vararg views: Button) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        views.forEach {
            addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    // ---------- Izin & daftar perangkat ----------
    private fun checkPermissionsAndLoad() {
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT)
        else emptyArray()

        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) loadPairedDevices()
        else permLauncher.launch(missing.toTypedArray())
    }

    private fun loadPairedDevices() {
        val bt = adapter ?: return
        if (!bt.isEnabled) {
            setStatus("Bluetooth mati. Aktifkan dulu, lalu tekan Refresh.")
            return
        }
        devices = bt.bondedDevices.toList()
        val names = devices.map { "${it.name} (${it.address})" }
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)

        val idx = devices.indexOfFirst { it.name == "ESP32-CAM" }
        if (idx >= 0) spinner.setSelection(idx)

        setStatus(
            if (devices.isEmpty()) "Belum ada perangkat ter-pair. Pair 'ESP32-CAM' lewat Pengaturan Bluetooth."
            else "Pilih perangkat lalu tekan Connect"
        )
    }

    // ---------- Koneksi ----------
    private fun connect() {
        if (running) { toast("Sudah terhubung"); return }
        val pos = spinner.selectedItemPosition
        if (pos < 0 || pos >= devices.size) { toast("Pilih perangkat dulu"); return }
        val device = devices[pos]

        setStatus("Menghubungkan ke ${device.name}…")
        Thread {
            try {
                adapter?.cancelDiscovery()
                val s = device.createRfcommSocketToServiceRecord(sppUuid)
                s.connect()
                socket = s
                running = true
                runOnUiThread { setStatus("Terhubung ke ${device.name}") }
                readLoop(s)
            } catch (e: Exception) {
                runOnUiThread { disconnect("Gagal/terputus: ${e.message}") }
            }
        }.start()
    }

    private fun readLoop(s: BluetoothSocket) {
        try {
            val input = DataInputStream(BufferedInputStream(s.inputStream, 16 * 1024))
            var matched = 0
            while (running) {
                val b = input.read()
                if (b < 0) break

                matched = when {
                    b == magic[matched] -> matched + 1
                    b == magic[0] -> 1
                    else -> 0
                }
                if (matched < magic.size) continue
                matched = 0

                // 4 byte panjang, little-endian
                val len = input.read() or (input.read() shl 8) or
                        (input.read() shl 16) or (input.read() shl 24)
                if (len <= 0 || len > 300_000) continue

                val buf = ByteArray(len)
                input.readFully(buf)

                val bmp = BitmapFactory.decodeByteArray(buf, 0, len)
                if (bmp != null) runOnUiThread { imageView.setImageBitmap(bmp) }
            }
        } catch (_: Exception) {
            // koneksi putus
        } finally {
            if (running) runOnUiThread { disconnect("Koneksi terputus") }
        }
    }

    private fun sendCmd(c: Char) {
        val s = socket
        if (!running || s == null) { toast("Belum terhubung"); return }
        Thread {
            try {
                s.outputStream.write(c.code)
                s.outputStream.flush()
            } catch (_: Exception) { }
        }.start()
    }

    private fun disconnect(message: String) {
        running = false
        try { socket?.close() } catch (_: Exception) { }
        socket = null
        setStatus(message)
    }

    private fun setStatus(t: String) { statusText.text = t }
    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        super.onDestroy()
        running = false
        try { socket?.close() } catch (_: Exception) { }
    }
}
