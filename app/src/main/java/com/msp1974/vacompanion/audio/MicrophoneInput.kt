package com.msp1974.vacompanion.audio

import android.Manifest
import android.content.Context
import android.media.AudioManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import androidx.annotation.VisibleForTesting
import androidx.annotation.RequiresPermission
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow

class MicrophoneInput(
    private val context: Context? = null,
    val audioSource: Int = DEFAULT_AUDIO_SOURCE,
    val sampleRateInHz: Int = DEFAULT_SAMPLE_RATE_IN_HZ,
    val channelConfig: Int = DEFAULT_CHANNEL_CONFIG,
    val audioFormat: Int = DEFAULT_AUDIO_FORMAT,
    val frameSize: Int = 0,
    /**
     * Provides the current mic gain in dB (0 to 20).
     * 0 dB = unity gain (1x multiplier, no change).
     * 6 dB ≈ 2x amplitude multiplier.
     * 12 dB ≈ 4x amplitude multiplier.
     * 20 dB = 10x amplitude multiplier.
     */
    private val gainProvider: () -> Int = { 0 },
    /**
     * Provides the current noise suppression level for Speex (0 = off, 15 = max).
     */
    private val noiseSuppressionProvider: () -> Int = { 50 },
    /**
     * Audio input processing mode:
     * - "hardware": use Android AcousticEchoCanceler/AGC/NS
     * - "webrtc": use WebRTC APM (AEC3/NS/AGC/HPF/TS)
     * - "speex": use Speex processing in-app
     *
     * Keeping this configurable is intentional: the best pipeline can differ by device age,
     * Android version, and OEM audio implementation quality.
     */
    private val audioInputProcessingModeProvider: () -> String = { "hardware" },
    /**
     * Baseline render-to-capture path delay for WebRTC AEC, in milliseconds.
     * Runtime logic adapts this baseline based on recent render activity.
     */
    private val webRtcStreamDelayMsProvider: () -> Int = { DEFAULT_WEBRTC_STREAM_DELAY_MS },
) : AutoCloseable {
    data class InputProcessingDiagnostics(
        val configuredInputProcessingMode: String,
        val activeProcessingPipeline: String,
        val hardwareAecAvailable: Boolean,
        val hardwareAecEnabled: Boolean,
        val activePipelineAecEnabled: Boolean,
        val activePipelineAgcEnabled: Boolean,
        val activePipelineNsEnabled: Boolean,
        val webRtcApmReady: Boolean,
    )

    private val logTag = "MicrophoneInput"
    private val bufferSize =
        AudioRecord.getMinBufferSize(sampleRateInHz, channelConfig, audioFormat)
    private var audioRecord: AudioRecord? = null

    private var aec: AcousticEchoCanceler? = null
    private var agc: AutomaticGainControl? = null
    private var ns: NoiseSuppressor? = null
    private var previousAudioMode: Int? = null
    private var apm: com.viewassist.webrtc.WebRtcApm? = null
    private var lastAppliedWebRtcDelayMs: Int = DEFAULT_WEBRTC_STREAM_DELAY_MS
    private var lastWebRtcDelayUpdateAtMs: Long = 0L

    // High-Pass Filter State (approx 80Hz cutoff at 16kHz)
    private var hpfLastIn = 0f
    private var hpfLastOut = 0f
    private val hpfAlpha = 0.96f

    // Audio level estimators (normalized 0.0f to 1.0f)
    @Volatile
    var currentRms: Float = 0f
        private set
    @Volatile
    var currentPeak: Float = 0f
        private set

    private var audioDSP = AudioDSP()

    val isRecording get() = audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING
    val speex = SpeexProcessor(sampleRate = DEFAULT_SAMPLE_RATE_IN_HZ, frameSize = if (frameSize > 0) frameSize else bufferSize )

    /** Expose the WebRTC APM instance for render stream feeding (AEC far-end reference). */
    val webRtcApm: com.viewassist.webrtc.WebRtcApm? get() = apm

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start() {
        if (audioRecord == null) {
            try {
                audioRecord = createAudioRecord()
                configureCommunicationAudioMode(enable = true)
                setupAudioEffects()
            } catch (e: Exception) {
                Timber.e("$logTag: Failed to initialize microphone input: ${e.message}")
                close()
                throw e
            }
        }

        if (!isRecording) {
            val processingMode = normalizeInputProcessingMode(audioInputProcessingModeProvider())
            val activePipelineAecEnabled = when (processingMode) {
                "hardware" -> aec?.enabled == true
                "webrtc" -> apm != null
                "speex" -> true
                else -> false
            }
            val activePipelineAgcEnabled = when (processingMode) {
                "hardware" -> agc?.enabled == true
                "webrtc" -> apm != null
                "speex" -> false
                else -> false
            }
            val activePipelineNsEnabled = when (processingMode) {
                "hardware" -> ns?.enabled == true
                "webrtc" -> apm != null && noiseSuppressionProvider() > 0
                "speex" -> noiseSuppressionProvider() > 0
                else -> false
            }
            Timber.d(
                "Starting microphone with mode=$processingMode, " +
                    "AEC=$activePipelineAecEnabled, AGC=$activePipelineAgcEnabled, NS=$activePipelineNsEnabled"
            )
            audioRecord?.startRecording()
        } else {
            Timber.w("Microphone already started")
        }
    }

    fun readBytes(): ByteBuffer {
        val audioShortBuffer = readShort(bufferSize)
        val buffer = ByteBuffer.allocateDirect(audioShortBuffer.size * 2)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        buffer.asShortBuffer().put(audioShortBuffer)
        buffer.rewind()
        return buffer
    }

    fun readShort(bufferSize: Int = BUFFER_SIZE_IN_SHORTS, applySoftwareEffects: Boolean = true): ShortArray {
        val audioBuffer = ShortArray(bufferSize)
        val audioRecord = this.audioRecord ?: error("Microphone not started")
        val readCount = audioRecord.read(audioBuffer, 0, audioBuffer.size)
        if (readCount > 0) {
            // Apply mic gain in-place on the raw buffer BEFORE Speex.
            // This avoids an extra intermediate array allocation.
            val gainDb = gainProvider()
            if (gainDb != 0) {
                val multiplier = 10.0.pow(gainDb / 20.0).toFloat()
                for (i in 0 until readCount) {
                    audioBuffer[i] = (audioBuffer[i] * multiplier)
                        .toInt()
                        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                        .toShort()
                }
            }

            val processingMode = normalizeInputProcessingMode(audioInputProcessingModeProvider())
            val shouldRunSpeex = applySoftwareEffects && processingMode == "speex"
            val shouldRunWebRtc = applySoftwareEffects && processingMode == "webrtc" && apm != null

            // 1. High-Pass Filter (Skip if WebRTC handles it internally)
            if (!shouldRunWebRtc) {
                for (i in 0 until readCount) {
                    val input = audioBuffer[i].toFloat()
                    val filtered = input - hpfLastIn + hpfAlpha * hpfLastOut
                    hpfLastIn = input
                    hpfLastOut = filtered
                    audioBuffer[i] = filtered
                        .toInt()
                        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                        .toShort()
                }
            }

            // 2. Main Processing Block (AEC and NS)
            val processedBuffer = if (shouldRunWebRtc) {
                maybeUpdateWebRtcStreamDelay()
                for (offset in 0 until readCount step 160) {
                    if (offset + 160 <= readCount) {
                        apm?.processCaptureStream(audioBuffer, offset)
                    }
                }
                audioBuffer.copyOfRange(0, readCount)
            } else if (shouldRunSpeex) {
                speex.echoSuppressionEnabled = true
                speex.setDenoiseSuppression(noiseSuppressionProvider())
                speex.setAGCLevel(20000)
                speex.processFrame(audioBuffer.copyOfRange(0, readCount))
            } else {
                audioBuffer.copyOfRange(0, readCount)
            }

            // 3. Level Estimation (Metrics extraction from finalized buffer)
            var sumSquares = 0f
            var peakVal = 0f

            for (i in 0 until processedBuffer.size) {
                val amplitude = Math.abs(processedBuffer[i].toInt()).toFloat()
                sumSquares += amplitude * amplitude
                if (amplitude > peakVal) {
                    peakVal = amplitude
                }
            } // Update volatile metrics (normalized 0..1)
            this.currentRms = kotlin.math.sqrt(sumSquares / processedBuffer.size) / 32768f
            this.currentPeak = peakVal / 32768f

            return processedBuffer
        }
        return ShortArray(0)
    }

    fun readFloat(bufferSize: Int = BUFFER_SIZE_IN_SHORTS): FloatArray {
        val audioBuffer = readShort(bufferSize)

        if (audioBuffer.isNotEmpty()) {
            return audioDSP.normaliseAudioBuffer(audioBuffer)
        }
        return FloatArray(0)
    }


    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun createAudioRecord(): AudioRecord {
        val audioRecord = AudioRecord(
            audioSource,
            sampleRateInHz,
            channelConfig,
            audioFormat,
            bufferSize * 2
        )
        check(audioRecord.state == AudioRecord.STATE_INITIALIZED) {
            "Failed to initialize AudioRecord"
        }
        return audioRecord
    }

    private fun setupAudioEffects() {
        val sessionId = audioRecord?.audioSessionId ?: return
        val processingMode = normalizeInputProcessingMode(audioInputProcessingModeProvider())
        val isHardwareMode = shouldUseHardwarePlatformEffects(processingMode)
        val isWebRtcMode = processingMode == "webrtc"
        val webRtcNsConfig = mapWebRtcNoiseSuppression(noiseSuppressionProvider())

        // Prevent stale platform effects from previous sessions from causing double processing.
        releasePlatformAudioEffects()

        val configuredInputProcessingMode = processingMode
        val hardwareAecAvailable = AcousticEchoCanceler.isAvailable()
        var hardwareAecEnabled = false
        var activeProcessingPipeline = if (isHardwareMode) "none" else configuredInputProcessingMode
        
        try {
            if (isHardwareMode && hardwareAecAvailable) {
                aec = AcousticEchoCanceler.create(sessionId)
                aec?.enabled = true
                hardwareAecEnabled = aec?.enabled == true
                if (hardwareAecEnabled) {
                    activeProcessingPipeline = "hardware"
                }
            } else if (!isHardwareMode) {
                disablePlatformAudioPreprocessing(sessionId, configuredInputProcessingMode)
            } else {
                Timber.d("$logTag: AEC is not available on this device")
            }
        } catch (e: Exception) {
            Timber.w("$logTag: Failed to enable AEC: ${e.message}")
        }

        if (isWebRtcMode) {
            try {
                val config = com.viewassist.webrtc.WebRtcApm.Config().apply {
                    aecEnabled = true
                    nsEnabled = webRtcNsConfig.enabled
                    nsLevel = webRtcNsConfig.level
                    agcEnabled = true           // AGC2 adaptive digital
                    hpfEnabled = true           // DC offset / rumble removal
                    transientSuppressionEnabled = true  // Keyboard clicks / taps
                    vadEnabled = false
                }
                apm = com.viewassist.webrtc.WebRtcApm(config)
                val configuredDelayMs = webRtcStreamDelayMsProvider().coerceIn(0, 500)
                lastAppliedWebRtcDelayMs = configuredDelayMs
                lastWebRtcDelayUpdateAtMs = 0L
                apm?.setStreamDelay(configuredDelayMs)
                setCurrentWebRtcStreamDelayMs(configuredDelayMs)
                // Register static render sink so VoicePlayer can feed AEC far-end audio
                updateRenderStreamSink { pcmBytes -> apm?.feedRenderAudioBytes(pcmBytes) }
                Timber.d(
                    "$logTag: WebRTC APM initialized successfully " +
                        "(streamDelayMs=$configuredDelayMs, " +
                        "nsEnabled=${webRtcNsConfig.enabled}, nsLevel=${webRtcNsConfig.level})"
                )
            } catch (e: Exception) {
                Timber.e(e, "$logTag: Failed to initialize WebRTC APM")
                apm = null
                activeProcessingPipeline = "none"
            }
        }

        try {
            if (isHardwareMode && AutomaticGainControl.isAvailable()) {
                agc = AutomaticGainControl.create(sessionId)
                agc?.enabled = true
            } else if (isHardwareMode) {
                Timber.d("$logTag: AGC is not available on this device")
            } else {
                Timber.d("$logTag: Hardware AGC skipped in $configuredInputProcessingMode mode")
            }
        } catch (e: Exception) {
            Timber.w("$logTag: Failed to enable AGC: ${e.message}")
        }

        try {
            if (isHardwareMode && NoiseSuppressor.isAvailable()) {
                ns = NoiseSuppressor.create(sessionId)
                ns?.enabled = true
            } else if (isHardwareMode) {
                Timber.d("$logTag: NS is not available on this device")
            } else {
                Timber.d("$logTag: Hardware NS skipped in $configuredInputProcessingMode mode")
            }
        } catch (e: Exception) {
            Timber.w("$logTag: Failed to enable NS: ${e.message}")
        }

        updateInputProcessingDiagnostics(
            configuredInputProcessingMode = configuredInputProcessingMode,
            activeProcessingPipeline = activeProcessingPipeline,
            hardwareAecAvailable = hardwareAecAvailable,
            hardwareAecEnabled = hardwareAecEnabled,
            activePipelineAecEnabled = when (configuredInputProcessingMode) {
                "hardware" -> hardwareAecEnabled
                "webrtc" -> apm != null
                "speex" -> true
                else -> false
            },
            activePipelineAgcEnabled = when (configuredInputProcessingMode) {
                "hardware" -> agc?.enabled == true
                "webrtc" -> apm != null
                "speex" -> false
                else -> false
            },
            activePipelineNsEnabled = when (configuredInputProcessingMode) {
                "hardware" -> ns?.enabled == true
                "webrtc" -> apm != null && webRtcNsConfig.enabled
                "speex" -> noiseSuppressionProvider() > 0
                else -> false
            },
            webRtcApmReady = apm != null,
        )
    }

    private fun configureCommunicationAudioMode(enable: Boolean) {
        if (audioSource != MediaRecorder.AudioSource.VOICE_COMMUNICATION) {
            return
        }
        val audioManager = context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (audioManager == null) {
            Timber.w("$logTag: Unable to access AudioManager for VOICE_COMMUNICATION mode")
            return
        }
        if (enable) {
            if (previousAudioMode == null) {
                previousAudioMode = audioManager.mode
            }
            if (audioManager.mode != AudioManager.MODE_IN_COMMUNICATION) {
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            }
            Timber.d("$logTag: Enabled MODE_IN_COMMUNICATION for VOICE_COMMUNICATION source")
        } else {
            previousAudioMode?.let { mode ->
                audioManager.mode = mode
                Timber.d("$logTag: Restored audio mode to $mode")
            }
            previousAudioMode = null
        }
    }

    private fun maybeUpdateWebRtcStreamDelay() {
        val now = System.currentTimeMillis()
        if (now - lastWebRtcDelayUpdateAtMs < WEBRTC_DELAY_UPDATE_INTERVAL_MS) {
            return
        }
        lastWebRtcDelayUpdateAtMs = now

        val baseDelayMs = webRtcStreamDelayMsProvider().coerceIn(0, 500)
        val adaptiveDelayMs = estimateAdaptiveWebRtcStreamDelayMs(baseDelayMs, now)
        if (adaptiveDelayMs != lastAppliedWebRtcDelayMs) {
            val result = apm?.setStreamDelay(adaptiveDelayMs) ?: -1
            if (result == 0) {
                Timber.d(
                    "$logTag: Updated WebRTC stream delay to $adaptiveDelayMs ms " +
                        "(base=$baseDelayMs ms)"
                )
                lastAppliedWebRtcDelayMs = adaptiveDelayMs
                setCurrentWebRtcStreamDelayMs(adaptiveDelayMs)
            }
        }
    }

    private fun releasePlatformAudioEffects() {
        aec?.release()
        aec = null
        agc?.release()
        agc = null
        ns?.release()
        ns = null
    }

    private fun disablePlatformAudioPreprocessing(sessionId: Int, mode: String) {
        try {
            val effect = AcousticEchoCanceler.create(sessionId)
            if (effect == null) {
                Timber.d("$logTag: Hardware AEC not present/attachable in $mode mode")
            } else {
                effect.enabled = false
                effect.release()
                Timber.d("$logTag: Hardware AEC disabled in $mode mode")
            }
        } catch (e: Exception) {
            Timber.w("$logTag: Unable to disable hardware AEC in $mode mode: ${e.message}")
        }
        try {
            val effect = AutomaticGainControl.create(sessionId)
            if (effect == null) {
                Timber.d("$logTag: Hardware AGC not present/attachable in $mode mode")
            } else {
                effect.enabled = false
                effect.release()
                Timber.d("$logTag: Hardware AGC disabled in $mode mode")
            }
        } catch (e: Exception) {
            Timber.w("$logTag: Unable to disable hardware AGC in $mode mode: ${e.message}")
        }
        try {
            val effect = NoiseSuppressor.create(sessionId)
            if (effect == null) {
                Timber.d("$logTag: Hardware NS not present/attachable in $mode mode")
            } else {
                effect.enabled = false
                effect.release()
                Timber.d("$logTag: Hardware NS disabled in $mode mode")
            }
        } catch (e: Exception) {
            Timber.w("$logTag: Unable to disable hardware NS in $mode mode: ${e.message}")
        }
    }


    override fun close() {
        updateRenderStreamSink(null)
        setCurrentWebRtcStreamDelayMs(null)
        apm?.close()
        apm = null
        releasePlatformAudioEffects()

        audioRecord?.let {
            if (isRecording) {
                it.stop()
            }
            it.release()
            audioRecord = null
        }
        configureCommunicationAudioMode(enable = false)
        updateInputProcessingDiagnostics(
            configuredInputProcessingMode = _inputProcessingDiagnostics.configuredInputProcessingMode,
            activeProcessingPipeline = "none",
            hardwareAecAvailable = _inputProcessingDiagnostics.hardwareAecAvailable,
            hardwareAecEnabled = false,
            activePipelineAecEnabled = false,
            activePipelineAgcEnabled = false,
            activePipelineNsEnabled = false,
            webRtcApmReady = false,
        )
    }

    companion object {
        const val DEFAULT_AUDIO_SOURCE = MediaRecorder.AudioSource.VOICE_RECOGNITION
        const val DEFAULT_SAMPLE_RATE_IN_HZ = VacaAudioFormat.SAMPLE_RATE_HZ
        val DEFAULT_CHANNEL_CONFIG = VacaAudioFormat.CHANNEL_IN_CONFIG
        val DEFAULT_AUDIO_FORMAT = VacaAudioFormat.ENCODING
        const val BUFFER_SIZE_IN_SHORTS = 1280
        const val DEFAULT_WEBRTC_STREAM_DELAY_MS = 80
        private const val WEBRTC_DELAY_UPDATE_INTERVAL_MS = 500L
        @Volatile
        private var lastRenderFeedAtMs: Long = 0L
        @Volatile
        private var currentWebRtcStreamDelayMs: Int? = null

        fun mapAudioSource(source: String): Int = when (source.lowercase()) {
            "voice_communication" -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
            "voice_recognition" -> MediaRecorder.AudioSource.VOICE_RECOGNITION
            else -> DEFAULT_AUDIO_SOURCE
        }

        @Volatile
        private var _inputProcessingDiagnostics = InputProcessingDiagnostics(
            configuredInputProcessingMode = "hardware",
            activeProcessingPipeline = "none",
            hardwareAecAvailable = false,
            hardwareAecEnabled = false,
            activePipelineAecEnabled = false,
            activePipelineAgcEnabled = false,
            activePipelineNsEnabled = false,
            webRtcApmReady = false,
        )

        @Synchronized
        private fun updateInputProcessingDiagnostics(
            configuredInputProcessingMode: String,
            activeProcessingPipeline: String,
            hardwareAecAvailable: Boolean,
            hardwareAecEnabled: Boolean,
            activePipelineAecEnabled: Boolean,
            activePipelineAgcEnabled: Boolean,
            activePipelineNsEnabled: Boolean,
            webRtcApmReady: Boolean,
        ) {
            _inputProcessingDiagnostics = InputProcessingDiagnostics(
                configuredInputProcessingMode = configuredInputProcessingMode,
                activeProcessingPipeline = activeProcessingPipeline,
                hardwareAecAvailable = hardwareAecAvailable,
                hardwareAecEnabled = hardwareAecEnabled,
                activePipelineAecEnabled = activePipelineAecEnabled,
                activePipelineAgcEnabled = activePipelineAgcEnabled,
                activePipelineNsEnabled = activePipelineNsEnabled,
                webRtcApmReady = webRtcApmReady,
            )
        }

        fun getInputProcessingDiagnostics(): InputProcessingDiagnostics = _inputProcessingDiagnostics
        fun getCurrentWebRtcStreamDelayMs(): Int? = currentWebRtcStreamDelayMs
        fun getRenderFeedAgeMs(nowMs: Long = System.currentTimeMillis()): Long? {
            if (lastRenderFeedAtMs == 0L) {
                return null
            }
            return (nowMs - lastRenderFeedAtMs).coerceAtLeast(0L)
        }

        /**
         * Static render stream sink for WebRTC AEC.
         * Set when a MicrophoneInput with WebRTC mode creates an APM,
         * cleared when the MicrophoneInput is closed.
         * VoicePlayer reads this to feed far-end audio to AEC3.
         */
        @Volatile
        var renderStreamSink: ((ByteArray) -> Unit)? = null
            private set

        @Synchronized
        internal fun updateRenderStreamSink(sink: ((ByteArray) -> Unit)?) {
            renderStreamSink = if (sink == null) {
                lastRenderFeedAtMs = 0L
                null
            } else {
                { pcmBytes ->
                    lastRenderFeedAtMs = System.currentTimeMillis()
                    sink(pcmBytes)
                }
            }
        }

        @Synchronized
        private fun setCurrentWebRtcStreamDelayMs(value: Int?) {
            currentWebRtcStreamDelayMs = value
        }

        @VisibleForTesting
        internal fun setInputProcessingDiagnosticsForTesting(
            configuredInputProcessingMode: String,
            activeProcessingPipeline: String,
            hardwareAecAvailable: Boolean,
            hardwareAecEnabled: Boolean,
            activePipelineAecEnabled: Boolean = false,
            activePipelineAgcEnabled: Boolean = false,
            activePipelineNsEnabled: Boolean = false,
            webRtcApmReady: Boolean = false,
        ) {
            updateInputProcessingDiagnostics(
                configuredInputProcessingMode = configuredInputProcessingMode,
                activeProcessingPipeline = activeProcessingPipeline,
                hardwareAecAvailable = hardwareAecAvailable,
                hardwareAecEnabled = hardwareAecEnabled,
                activePipelineAecEnabled = activePipelineAecEnabled,
                activePipelineAgcEnabled = activePipelineAgcEnabled,
                activePipelineNsEnabled = activePipelineNsEnabled,
                webRtcApmReady = webRtcApmReady,
            )
        }

        @VisibleForTesting
        internal fun setLastRenderFeedTimestampForTesting(timestampMs: Long) {
            lastRenderFeedAtMs = timestampMs
        }

        private fun normalizeInputProcessingMode(mode: String): String {
            return when (mode.lowercase()) {
                "hardware", "webrtc", "speex" -> mode.lowercase()
                else -> "hardware"
            }
        }

        internal fun shouldUseHardwarePlatformEffects(mode: String): Boolean =
            normalizeInputProcessingMode(mode) == "hardware"

        internal fun shouldEnableWebRtcRenderTap(mode: String): Boolean =
            normalizeInputProcessingMode(mode) == "webrtc"

        private data class WebRtcNsConfig(
            val enabled: Boolean,
            val level: com.viewassist.webrtc.WebRtcApm.NsLevel,
        )

        private fun mapWebRtcNoiseSuppression(level: Int): WebRtcNsConfig {
            val normalized = level.coerceIn(0, 100)
            if (normalized == 0) {
                return WebRtcNsConfig(
                    enabled = false,
                    level = com.viewassist.webrtc.WebRtcApm.NsLevel.LOW,
                )
            }
            val nsLevel = when {
                normalized <= 33 -> com.viewassist.webrtc.WebRtcApm.NsLevel.LOW
                normalized <= 66 -> com.viewassist.webrtc.WebRtcApm.NsLevel.MODERATE
                normalized <= 85 -> com.viewassist.webrtc.WebRtcApm.NsLevel.HIGH
                else -> com.viewassist.webrtc.WebRtcApm.NsLevel.VERY_HIGH
            }
            return WebRtcNsConfig(enabled = true, level = nsLevel)
        }

        internal fun estimateAdaptiveWebRtcStreamDelayMs(
            baseDelayMs: Int,
            nowMs: Long = System.currentTimeMillis(),
        ): Int {
            val sinceRenderMs =
                if (lastRenderFeedAtMs == 0L) Long.MAX_VALUE else nowMs - lastRenderFeedAtMs
            val targetDelayMs = when {
                sinceRenderMs <= 80L -> baseDelayMs
                sinceRenderMs <= 220L -> baseDelayMs + 30
                sinceRenderMs <= 450L -> baseDelayMs + 60
                // During silence/no render, keep baseline to avoid carrying a stale
                // inflated delay into the next playback burst.
                else -> baseDelayMs
            }
            return targetDelayMs.coerceIn(0, 500)
        }
    }
}
