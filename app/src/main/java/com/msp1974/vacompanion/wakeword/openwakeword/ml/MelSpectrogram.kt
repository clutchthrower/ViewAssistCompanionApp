package com.msp1974.vacompanion.wakeword.openwakeword.ml

import android.content.res.AssetManager
import com.msp1974.vacompanion.utils.loadMappedAsset
import org.tensorflow.lite.Interpreter
import java.nio.FloatBuffer

/**
 * Handles mel-spectrogram computation using TFLite model.
 */
internal class MelSpectrogram(
    assetManager: AssetManager
) : AutoCloseable {

    companion object {
        private const val MEL_SPECTROGRAM_MODEL = "openwakeword/melspectrogram.tflite"
        private const val BATCH_SIZE = 1
    }

    private val interpreter: Interpreter

    init {
        val model = assetManager.loadMappedAsset(MEL_SPECTROGRAM_MODEL)
        try {
            interpreter = Interpreter(model)
        } catch (e: Exception) {
            throw RuntimeException("Failed to initialize TFLite interpreter for MelSpectrogram: ${e.message}", e)
        }
    }

    /**
     * Compute mel-spectrogram from audio samples.
     *
     * @param audioSamples Float array of audio samples
     * @return 2D array representing mel-spectrogram
     */
    fun computeMelSpectrogram(audioSamples: FloatArray): Array<FloatArray> {
        try {
            // Explicitly resize input and allocate tensors to get correct output shape
            val floatBuffer = FloatBuffer.wrap(audioSamples)
            interpreter.resizeInput(0, intArrayOf(BATCH_SIZE, audioSamples.size), true)
            interpreter.allocateTensors()

            val outputDetails = interpreter.getOutputTensor(0)
            val outputShape = outputDetails.shape() 
            
            // Allocate output buffer based on the updated shape
            val output = Array(outputShape[0]) {
                Array(outputShape[1]) {
                    Array(outputShape[2]) {
                        FloatArray(outputShape[3])
                    }
                }
            }

            val inputs = arrayOf<Any>(floatBuffer)
            val outputs = mutableMapOf<Int, Any>(0 to output)
            
            interpreter.runForMultipleInputsOutputs(inputs, outputs)
            
            // Squeeze dimensions and apply transform
            return applyMelSpecTransform(squeeze(output))
        } catch (e: Exception) {
            throw RuntimeException("Failed to compute mel-spectrogram: ${e.message}", e)
        }
    }

    private fun squeeze(originalArray: Array<Array<Array<FloatArray>>>): Array<FloatArray> {
        val squeezed = Array(originalArray[0][0].size) { i ->
            FloatArray(originalArray[0][0][0].size) { j ->
                originalArray[0][0][i][j]
            }
        }
        return squeezed
    }

    private fun applyMelSpecTransform(array: Array<FloatArray>): Array<FloatArray> {
        return Array(array.size) { i ->
            FloatArray(array[i].size) { j ->
                array[i][j] / 10.0f + 2.0f
            }
        }
    }

    override fun close() {
        interpreter.close()
    }
}
