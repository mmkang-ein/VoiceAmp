package com.jason.voiceamp

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import kotlin.math.sqrt

/**
 * Android 네이티브 AudioEffect(AEC·NS·AGC) 기반 실시간 패스스루.
 *
 * 추가 기능
 *  - 기침/캑캑 자동 뮤트 : ZCR + 에너지 급등 + 짧은 버스트(≤50ms) 3중 조건
 */
class AudioProcessor {

    // ── 기능 토글 ────────────────────────────────────────────────
    @Volatile var aecEnabled       = true
    @Volatile var nsEnabled        = true
    @Volatile var agcEnabled       = true
    @Volatile var smartMuteEnabled = true
    @Volatile var debugLogging     = false

    /** 소프트웨어 게인 배율. 1.0f = 원본, 2.0f = 2배 증폭 */
    @Volatile var gain = 1.0f

    /** 뮤트 상태 변화 콜백 — 오디오 스레드에서 호출, UI 갱신 시 runOnUiThread 필요 */
    var onMuteChanged: ((isMuted: Boolean) -> Unit)? = null

    // ── 상수 ────────────────────────────────────────────────────
    companion object {
        private const val TAG         = "VoiceAmp"
        private const val SAMPLE_RATE = 44100

        // 기침 감지 — 서브프레임 (5ms 단위)
        private const val SUB_FRAME_MS = 5
        private val SUB_FRAME_SIZE     = SAMPLE_RATE * SUB_FRAME_MS / 1000  // 220 samples

        // 기침 감지 파라미터
        private const val SPIKE_RATIO       = 0.6f    // 실측: 기침 rms 553 / base 800 = 0.69, 마진 포함
        private const val ZCR_THRESHOLD     = 0.01f   // 0.20 → 0.01: 실측 zcr 범위(0.005~0.046) 반영
        private const val BURST_MIN_FRAMES  = 1       // 버스트 최소 길이 (1 frame = 5ms)
        private const val BURST_MAX_FRAMES  = 10      // 버스트 최대 길이 (10 frames = 50ms)
        private const val MUTE_HOLD_MS      = 700L    // 뮤트 유지 시간
        private const val BASELINE_ALPHA    = 0.008f  // EMA 기준선 적응 속도 (느릴수록 안정)
        private const val BASELINE_FLOOR    = 300f    // 기준선 최솟값
        private const val SILENCE_THR       = 300f    // 이하: 무음 → 기준선 업데이트 안 함
    }

    // ── 장치 ────────────────────────────────────────────────────
    private val bufferSize = maxOf(
        AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        ), 4096
    ) * 4

    @Volatile private var isRunning = false
    private var thread:   Thread?               = null
    private var recorder: AudioRecord?          = null
    private var player:   AudioTrack?           = null
    private var aec:      AcousticEchoCanceler? = null
    private var ns:       NoiseSuppressor?      = null
    private var agc:      AutomaticGainControl? = null

    // ── 기침 감지 상태 ───────────────────────────────────────────
    private var baselineRms  = 0f
    private var inBurst      = false
    private var burstFrames  = 0
    private var burstMaxZcr  = 0f
    private var muteUntil    = 0L
    private var prevMuted    = false

    // ── 디버그 로그 스로틀 ────────────────────────────────────────
    private var logFrameCount = 0      // 누적 서브프레임 수 (스로틀용)

    // ════════════════════════════════════════════════════════════
    // 공개 API
    // ════════════════════════════════════════════════════════════

    fun start() {
        if (isRunning) return
        resetDetectionState()

        recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, bufferSize
        )
        setupEffects(recorder!!.audioSessionId)

        player = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        isRunning = true
        recorder!!.startRecording()
        player!!.play()

        thread = Thread(::processingLoop, "VoiceAmpProcessor").also { it.start() }
    }

    fun stop() {
        isRunning = false
        thread?.join(500)
        thread = null
        releaseEffects()
        runCatching { recorder?.stop() }
        recorder?.release(); recorder = null
        runCatching { player?.stop() }
        player?.release(); player = null
    }

    // ════════════════════════════════════════════════════════════
    // AudioEffect 설정
    // ════════════════════════════════════════════════════════════

    private fun setupEffects(sessionId: Int) {
        Log.d(TAG, "=== AudioEffect 설정 (sessionId=$sessionId) ===")

        // AEC
        if (aecEnabled) {
            Log.d(TAG, "AEC isAvailable=${AcousticEchoCanceler.isAvailable()}")
            aec = AcousticEchoCanceler.create(sessionId)
            if (aec != null) {
                val r = aec!!.setEnabled(true)
                Log.d(TAG, "AEC: enabled=${aec!!.enabled}, setEnabled=$r")
            } else {
                Log.w(TAG, "AEC: create() null — 기기 미지원")
            }
        }

        // NS — isAvailable()은 일부 기기에서 실제 지원 여부와 무관하게 false 반환.
        //      create()를 직접 시도해 null 여부로 지원을 판단한다.
        if (nsEnabled) {
            val avail = NoiseSuppressor.isAvailable()
            Log.d(TAG, "NS isAvailable=$avail (create 시도 중...)")
            ns = NoiseSuppressor.create(sessionId)
            if (ns != null) {
                val r = ns!!.setEnabled(true)
                Log.d(TAG, "NS: created, enabled=${ns!!.enabled}, setEnabled=$r")
            } else {
                Log.w(TAG, "NS: create() null — 기기 미지원 (isAvailable=$avail)")
            }
        }

        // AGC
        if (agcEnabled) {
            Log.d(TAG, "AGC isAvailable=${AutomaticGainControl.isAvailable()}")
            agc = AutomaticGainControl.create(sessionId)
            if (agc != null) {
                val r = agc!!.setEnabled(true)
                Log.d(TAG, "AGC: enabled=${agc!!.enabled}, setEnabled=$r")
            } else {
                Log.w(TAG, "AGC: create() null — 기기 미지원")
            }
        }
    }

    private fun releaseEffects() {
        aec?.release(); aec = null
        ns?.release();  ns  = null
        agc?.release(); agc = null
    }

    // ════════════════════════════════════════════════════════════
    // 오디오 처리 루프
    // read() 로 꺼낸 데이터에는 이미 AEC+NS+AGC 적용 상태.
    // 여기서 5ms 서브프레임으로 분할해 기침 감지 추가 적용.
    // ════════════════════════════════════════════════════════════

    private fun processingLoop() {
        val readBuf   = ShortArray(bufferSize / 2)
        val subBuf    = ShortArray(SUB_FRAME_SIZE)
        val silenceBuf = ShortArray(SUB_FRAME_SIZE)  // 뮤트 시 출력할 무음

        while (isRunning) {
            val read = recorder?.read(readBuf, 0, readBuf.size) ?: break
            if (read <= 0) continue

            // 큰 버퍼를 5ms 서브프레임으로 쪼개 기침 감지 적용
            var offset = 0
            while (offset + SUB_FRAME_SIZE <= read) {
                System.arraycopy(readBuf, offset, subBuf, 0, SUB_FRAME_SIZE)

                val muted = if (smartMuteEnabled) detectCough(subBuf) else false

                if (muted) {
                    player?.write(silenceBuf, 0, SUB_FRAME_SIZE)
                } else {
                    applyGain(subBuf, SUB_FRAME_SIZE)
                    player?.write(subBuf, 0, SUB_FRAME_SIZE)
                }

                // 상태 변화 시만 콜백 (과도 호출 방지)
                if (muted != prevMuted) {
                    prevMuted = muted
                    onMuteChanged?.invoke(muted)
                }

                offset += SUB_FRAME_SIZE
            }

            // 5ms 미만 나머지 샘플은 그냥 출력 (뮤트 중이면 무음)
            val remaining = read - offset
            if (remaining > 0) {
                if (prevMuted) {
                    player?.write(ShortArray(remaining), 0, remaining)
                } else {
                    applyGain(readBuf, remaining, offset)
                    player?.write(readBuf, offset, remaining)
                }
            }
        }
    }

    // ════════════════════════════════════════════════════════════
    // 기침/캑캑 감지 — 상태 머신 (5ms 서브프레임 단위)
    //
    // 감지 조건 (3중 AND):
    //  ① 에너지 급등: RMS > baseline × SPIKE_RATIO
    //  ② 높은 ZCR   : zcr > ZCR_THRESHOLD (기침의 광대역 잡음 특성)
    //  ③ 짧은 버스트 : 1 ≤ 연속 스파이크 프레임 ≤ BURST_MAX_FRAMES (50ms 이하)
    //
    // 버스트가 50ms 초과 지속 → 정상 발화로 판단, 감지 취소
    // ════════════════════════════════════════════════════════════

    private fun detectCough(subBuf: ShortArray): Boolean {
        val rms = calcRms(subBuf, SUB_FRAME_SIZE)
        val zcr = calcZcr(subBuf, SUB_FRAME_SIZE)
        val now = System.currentTimeMillis()
        logFrameCount++

        // 기준선 업데이트: 뮤트 중 아님 + 무음 아님 + 스파이크 아님
        if (now >= muteUntil && !inBurst && rms > SILENCE_THR) {
            if (baselineRms < 1f) baselineRms = rms.coerceIn(BASELINE_FLOOR, 800f)
            else {
                baselineRms += (rms - baselineRms) * BASELINE_ALPHA
                baselineRms  = baselineRms.coerceIn(BASELINE_FLOOR, 800f)  // 최댓값 800 고정
            }
        }

        val effectiveBase = baselineRms.coerceAtLeast(BASELINE_FLOOR)
        val ratio         = rms / effectiveBase
        val isSpike       = ratio > SPIKE_RATIO

        when {
            // ─ 버스트 시작 ─
            !inBurst && isSpike -> {
                inBurst     = true
                burstFrames = 1
                burstMaxZcr = zcr
                if (debugLogging) Log.i(TAG,
                    "▶ BURST_START  rms=${rms.toInt()} base=${effectiveBase.toInt()} " +
                    "ratio=${"%.2f".format(ratio)} zcr=${"%.3f".format(zcr)}")
            }

            // ─ 버스트 지속 ─
            inBurst && isSpike -> {
                burstFrames++
                burstMaxZcr = maxOf(burstMaxZcr, zcr)
                if (burstFrames > BURST_MAX_FRAMES) {
                    if (debugLogging) Log.d(TAG,
                        "▷ BURST_CANCEL(too long) ${burstFrames * SUB_FRAME_MS}ms > 50ms → 정상 발화")
                    inBurst = false; burstFrames = 0; burstMaxZcr = 0f
                }
            }

            // ─ 버스트 종료 ─
            inBurst && !isSpike -> {
                val durMs = burstFrames * SUB_FRAME_MS
                val detected = burstFrames in BURST_MIN_FRAMES..BURST_MAX_FRAMES
                        && burstMaxZcr > ZCR_THRESHOLD

                if (detected) {
                    muteUntil = now + MUTE_HOLD_MS
                    // 기침 감지 — Log.w 로 Logcat 에서 노란색으로 강조
                    Log.w(TAG,
                        "★★★ DETECT  dur=${durMs}ms burst=$burstFrames " +
                        "maxZcr=${"%.3f".format(burstMaxZcr)} " +
                        "ratio=${"%.2f".format(ratio)} → MUTE ${MUTE_HOLD_MS}ms")
                } else {
                    if (debugLogging) Log.d(TAG,
                        "▷ BURST_END(miss) dur=${durMs}ms burst=$burstFrames " +
                        "maxZcr=${"%.3f".format(burstMaxZcr)} " +
                        "zcrOK=${burstMaxZcr > ZCR_THRESHOLD} " +
                        "framesOK=${burstFrames in BURST_MIN_FRAMES..BURST_MAX_FRAMES}")
                }
                inBurst = false; burstFrames = 0; burstMaxZcr = 0f
            }
        }

        // ── 주기적 상태 로그 ──────────────────────────────────────
        // • BURST 중: 매 프레임 출력 (상세 추적)
        // • MUTED   : 4프레임(20ms)마다 출력
        // • normal  : 8프레임(40ms)마다 출력 → ~25줄/초로 Logcat 감당 가능
        if (debugLogging) {
            val isMuted   = now < muteUntil
            val printNow  = when {
                inBurst  -> true                      // 버스트 중 매 프레임
                isMuted  -> logFrameCount % 4 == 0   // 뮤트 중 20ms마다
                else     -> logFrameCount % 8 == 0   // 일반 40ms마다
            }
            if (printNow) {
                val state = when {
                    isMuted -> "MUTED "
                    inBurst -> "BURST${burstFrames.toString().padStart(2)}"
                    else    -> "normal"
                }
                Log.d(TAG, "[$state] " +
                    "rms=${rms.toInt().toString().padStart(5)} " +
                    "base=${effectiveBase.toInt().toString().padStart(5)} " +
                    "ratio=${"%.2f".format(ratio).padStart(5)} " +
                    "zcr=${"%.3f".format(zcr)} " +
                    "burst=$burstFrames")
            }
        }

        return now < muteUntil
    }

    // ════════════════════════════════════════════════════════════
    // 신호 분석 헬퍼
    // ════════════════════════════════════════════════════════════

    private fun applyGain(buf: ShortArray, size: Int, offset: Int = 0) {
        val g = gain
        if (g == 1.0f) return
        for (i in offset until offset + size) {
            buf[i] = (buf[i] * g).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    private fun calcRms(buf: ShortArray, size: Int): Float {
        if (size == 0) return 0f
        var sum = 0.0
        for (i in 0 until size) sum += buf[i].toLong() * buf[i]
        return sqrt(sum / size).toFloat()
    }

    private fun calcZcr(buf: ShortArray, size: Int): Float {
        if (size < 2) return 0f
        var crossings = 0
        for (i in 1 until size) {
            if ((buf[i] >= 0) != (buf[i - 1] >= 0)) crossings++
        }
        return crossings.toFloat() / (size - 1)
    }

    private fun resetDetectionState() {
        baselineRms   = 0f
        inBurst       = false
        burstFrames   = 0
        burstMaxZcr   = 0f
        muteUntil     = 0L
        prevMuted     = false
        logFrameCount = 0
    }
}
