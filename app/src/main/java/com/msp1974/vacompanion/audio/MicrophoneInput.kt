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
     * Echo cancellation mode:
     * - "platform": use Android AcousticEchoCanceler
     * - "software": use Speex echo suppression in-app
     */
    private val echoCancellationModeProvider: () -> String = { "platform" },
) : AutoCloseable {
    data class EchoDiagnostics(
        val requestedEchoMode: String,
        val activeEchoMode: String,
        val platformAecAvailable: Boolean,
        val platformAecEnabled: Boolean,
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
            Timber.d("Starting microphone with AEC=${aec != null}, AGC=${agc != null}, NS=${ns != null}")
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

            val echoMode = echoCancellationModeProvider().lowercase()
            val shouldRunSpeex = applySoftwareEffects && echoMode == "speex"
            val shouldRunWebRtc = applySoftwareEffects && echoMode == "webrtc" && apm != null

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
        val echoMode = echoCancellationModeProvider().lowercase()
        val isHardwareMode = echoMode == "hardware"
        val isWebRtcMode = echoMode == "webrtc"
        val isSoftwareMode = echoMode == "speex"
        
        val requestedEchoMode = echoMode
        val platformAecAvailable = AcousticEchoCanceler.isAvailable()
        var platformAecEnabled = false
        var activeEchoMode = if (isHardwareMode) "none" else requestedEchoMode
        
        try {
            if (isHardwareMode && platformAecAvailable) {
                aec = AcousticEchoCanceler.create(sessionId)
                aec?.enabled = true
                platformAecEnabled = aec?.enabled == true
                if (platformAecEnabled) {
                    activeEchoMode = "hardware"
                }
            } else if (!isHardwareMode) {
                Timber.d("$logTag: Platform AEC disabled manually. Requested: $requestedEchoMode")
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
                    aecMobileMode = true       // Mobile-optimised AEC3
                    nsEnabled = true
                    nsLevel = com.viewassist.webrtc.WebRtcApm.NsLevel.HIGH
                    agcEnabled = true           // AGC2 adaptive digital
                    hpfEnabled = true           // DC offset / rumble removal
                    transientSuppressionEnabled = true  // Keyboard clicks / taps
                    vadEnabled = false
                }
                apm = com.viewassist.webrtc.WebRtcApm(config)
                // Register static render sink so VoicePlayer can feed AEC far-end audio
                updateRenderStreamSink { pcmBytes -> apm?.feedRenderAudioBytes(pcmBytes) }
                Timber.d("$logTag: WebRTC APM initialized successfully")
            } catch (e: Exception) {
                Timber.e(e, "$logTag: Failed to initialize WebRTC APM")
                apm = null
                activeEchoMode = "none"
            }
        }

        try {
            if (isHardwareMode && AutomaticGainControl.isAvailable()) {
                agc = AutomaticGainControl.create(sessionId)
                agc?.enabled = true
            } else {
                Timber.d("$logTag: AGC is not available on this device")
            }
        } catch (e: Exception) {
            Timber.w("$logTag: Failed to enable AGC: ${e.message}")
        }

        try {
            if (isHardwareMode && NoiseSuppressor.isAvailable()) {
                ns = NoiseSuppressor.create(sessionId)
                ns?.enabled = true
            } else {
                Timber.d("$logTag: NS is not available on this device")
            }
        } catch (e: Exception) {
            Timber.w("$logTag: Failed to enable NS: ${e.message}")
        }

        updateEchoDiagnostics(
            requestedEchoMode = requestedEchoMode,
            activeEchoMode = activeEchoMode,
            platformAecAvailable = platformAecAvailable,
            platformAecEnabled = platformAecEnabled,
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


    override fun close() {
        updateRenderStreamSink(null)
        apm?.close()
        apm = null
        aec?.release()
        aec = null
        agc?.release()
        agc = null
        ns?.release()
        ns = null

        audioRecord?.let {
            if (isRecording) {
                it.stop()
            }
            it.release()
            audioRecord = null
        }
        configureCommunicationAudioMode(enable = false)
    }

    companion object {
        const val DEFAULT_AUDIO_SOURCE = MediaRecorder.AudioSource.VOICE_RECOGNITION
        const val DEFAULT_SAMPLE_RATE_IN_HZ = VacaAudioFormat.SAMPLE_RATE_HZ
        val DEFAULT_CHANNEL_CONFIG = VacaAudioFormat.CHANNEL_IN_CONFIG
        val DEFAULT_AUDIO_FORMAT = VacaAudioFormat.ENCODING
        const val BUFFER_SIZE_IN_SHORTS = 1280

        fun mapAudioSource(source: String): Int = when (source.lowercase()) {
            "voice_communication" -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
            "voice_recognition" -> MediaRecorder.AudioSource.VOICE_RECOGNITION
            else -> DEFAULT_AUDIO_SOURCE
        }

        @Volatile
        private var _echoDiagnostics = EchoDiagnostics(
            requestedEchoMode = "platform",
            activeEchoMode = "none",
            platformAecAvailable = false,
            platformAecEnabled = false,
        )

        @Synchronized
        private fun updateEchoDiagnostics(
            requestedEchoMode: String,
            activeEchoMode: String,
            platformAecAvailable: Boolean,
            platformAecEnabled: Boolean,
        ) {
            _echoDiagnostics = EchoDiagnostics(
                requestedEchoMode = requestedEchoMode,
                activeEchoMode = activeEchoMode,
                platformAecAvailable = platformAecAvailable,
                platformAecEnabled = platformAecEnabled,
            )
        }

        fun getEchoDiagnostics(): EchoDiagnostics = _echoDiagnostics

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
            renderStreamSink = sink
        }

        @VisibleForTesting
        internal fun setEchoDiagnosticsForTesting(
            requestedEchoMode: String,
            activeEchoMode: String,
            platformAecAvailable: Boolean,
            platformAecEnabled: Boolean,
        ) {
            updateEchoDiagnostics(
                requestedEchoMode = requestedEchoMode,
                activeEchoMode = activeEchoMode,
                platformAecAvailable = platformAecAvailable,
                platformAecEnabled = platformAecEnabled,
            )
        }
    }
}
