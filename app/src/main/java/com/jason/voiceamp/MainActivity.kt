package com.jason.voiceamp

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var audioManager: AudioManager
    private var audioProcessor: AudioProcessor? = null
    private var isRunning = false

    private lateinit var tvStatus:  TextView
    private lateinit var tvBt:      TextView
    private lateinit var btnToggle: Button
    private lateinit var cbAec:     CheckBox
    private lateinit var cbNs:      CheckBox
    private lateinit var cbAgc:     CheckBox
    private lateinit var seekGain:  SeekBar
    private lateinit var tvGain:    TextView

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result[Manifest.permission.RECORD_AUDIO] == true) startAudio()
        else tvStatus.text = "❌ 마이크 권한 필요"
    }

    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = refreshBtStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        tvStatus  = findViewById(R.id.tvStatus)
        tvBt      = findViewById(R.id.tvBtDevice)
        btnToggle = findViewById(R.id.btnToggle)
        cbAec     = findViewById(R.id.cbAec)
        cbNs      = findViewById(R.id.cbNs)
        cbAgc     = findViewById(R.id.cbAgc)
        seekGain  = findViewById(R.id.seekGain)
        tvGain    = findViewById(R.id.tvGain)

        btnToggle.setOnClickListener {
            if (isRunning) stopAudio() else checkPermissionsAndStart()
        }

        // 실행 중 토글 변경 → 즉시 반영
        cbAec.setOnCheckedChangeListener { _, v -> audioProcessor?.aecEnabled = v }
        cbNs.setOnCheckedChangeListener  { _, v -> audioProcessor?.nsEnabled  = v }
        cbAgc.setOnCheckedChangeListener { _, v -> audioProcessor?.agcEnabled = v }

        // 볼륨 슬라이더: progress 0–30 → gain 0.0–3.0 (progress 10 = 1.0×)
        seekGain.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                val g = progress / 10f
                tvGain.text = "${"%.1f".format(g)}×"
                audioProcessor?.gain = g
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        registerReceiver(btReceiver, IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED)
        })
        refreshBtStatus()
    }

    private fun checkPermissionsAndStart() {
        val required = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        val missing = required.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startAudio() else permLauncher.launch(missing.toTypedArray())
    }

    private fun startAudio() {
        audioProcessor = AudioProcessor().also { proc ->
            proc.aecEnabled        = cbAec.isChecked
            proc.nsEnabled         = cbNs.isChecked
            proc.agcEnabled        = cbAgc.isChecked
            proc.smartMuteEnabled  = true
            proc.debugLogging      = true
            proc.gain              = seekGain.progress / 10f
            proc.onMuteChanged = { isMuted ->
                runOnUiThread {
                    tvStatus.text = if (isMuted) "🤫 뮤트 (기침 감지)" else "🎙️ 실시간 송출 중..."
                    tvStatus.setTextColor(
                        if (isMuted) 0xFFFFA726.toInt() else 0xFF58A6FF.toInt()
                    )
                }
            }
            proc.start()
        }
        isRunning = true
        tvStatus.text = "🎙️ 실시간 송출 중..."
        tvStatus.setTextColor(0xFF58A6FF.toInt())
        btnToggle.text = "■  중지"
        btnToggle.backgroundTintList =
            android.content.res.ColorStateList.valueOf(0xFFD32F2F.toInt())
    }

    private fun stopAudio() {
        audioProcessor?.stop()
        audioProcessor = null
        isRunning = false
        tvStatus.text = "대기 중"
        tvStatus.setTextColor(0xFF8B949E.toInt())
        btnToggle.text = "▶  시작"
        btnToggle.backgroundTintList =
            android.content.res.ColorStateList.valueOf(0xFF1565C0.toInt())
    }

    private fun refreshBtStatus() {
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        tvBt.text = when {
            adapter == null || !adapter.isEnabled -> "⚪ 블루투스 꺼짐"
            audioManager.isBluetoothA2dpOn        -> "🔵 블루투스 스피커 연결됨"
            else                                  -> "⚪ 블루투스 스피커 없음"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAudio()
        unregisterReceiver(btReceiver)
    }
}
