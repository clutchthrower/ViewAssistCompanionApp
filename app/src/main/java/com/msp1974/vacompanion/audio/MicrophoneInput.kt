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

    private var audioDSP = AudioDSP()

    val isRecording get() = audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING
    val speex = SpeexProcessor(sampleRate = DEFAULT_SAMPLE_RATE_IN_HZ, frameSize = if (frameSize > 0) frameSize else bufferSize )
    
    private fun isSoftwareEchoMode(): Boolean =
        echoCancellationModeProvider().lowercase() == "software"

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

    fun readShort(bufferSize: Int = BUFFER_SIZE_IN_SHORTS, useSpeex: Boolean = true): ShortArray {
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

            if (useSpeex) {
                speex.echoSuppressionEnabled = isSoftwareEchoMode()
                speex.setDenoiseSuppression(noiseSuppressionProvider())
                speex.setAGCLevel(20000)
                return speex.processFrame(audioBuffer.copyOfRange(0, readCount))
            }

            return audioBuffer.copyOfRange(0, readCount)
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
        val useSoftwareEcho = isSoftwareEchoMode()
        val requestedEchoMode = if (useSoftwareEcho) "software" else "platform"
        val platformAecAvailable = AcousticEchoCanceler.isAvailable()
        var platformAecEnabled = false
        var activeEchoMode = if (useSoftwareEcho) "software" else "none"
        try {
            if (!useSoftwareEcho && platformAecAvailable) {
                aec = AcousticEchoCanceler.create(sessionId)
                aec?.enabled = true
                platformAecEnabled = aec?.enabled == true
                if (platformAecEnabled) {
                    activeEchoMode = "platform"
                }
            } else if (useSoftwareEcho) {
                Timber.d("$logTag: Using software echo suppression (Speex); platform AEC disabled")
            } else {
                Timber.d("$logTag: AEC is not available on this device")
            }
        } catch (e: Exception) {
            Timber.w("$logTag: Failed to enable AEC: ${e.message}")
        }

        try {
            if (AutomaticGainControl.isAvailable()) {
                agc = AutomaticGainControl.create(sessionId)
                agc?.enabled = true
            } else {
                Timber.d("$logTag: AGC is not available on this device")
            }
        } catch (e: Exception) {
            Timber.w("$logTag: Failed to enable AGC: ${e.message}")
        }

        try {
            if (NoiseSuppressor.isAvailable()) {
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
        const val DEFAULT_SAMPLE_RATE_IN_HZ = 16000
        const val DEFAULT_CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val DEFAULT_AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
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
