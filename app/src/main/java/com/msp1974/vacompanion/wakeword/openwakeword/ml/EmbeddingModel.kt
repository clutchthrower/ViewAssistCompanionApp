package com.msp1974.vacompanion.wakeword.openwakeword.ml

import android.content.res.AssetManager
import com.msp1974.vacompanion.utils.loadMappedAsset
import org.tensorflow.lite.Interpreter

/**
 * Handles embedding generation from mel-spectrograms using TFLite model.
 */
internal class EmbeddingModel(
    assetManager: AssetManager
) : AutoCloseable {

    companion object {
        private const val EMBEDDING_MODEL = "openwakeword/embedding_model.tflite"
    }

    private val interpreter: Interpreter

    init {
        val model = assetManager.loadMappedAsset(EMBEDDING_MODEL)
        try {
            interpreter = Interpreter(model)
        } catch (e: Exception) {
            throw RuntimeException("Failed to initialize TFLite interpreter for EmbeddingModel: ${e.message}", e)
        }
    }

    /**
     * Generate embeddings from mel-spectrogram windows.
     *
     * @param input 4D array of shape [batch, height, width, channels]
     * @return 2D array of embeddings
     */
    fun generateEmbeddings(input: Array<Array<Array<FloatArray>>>): Array<FloatArray> {
        try {
            val batchSize = input.size
            val height = input[0].size
            val width = input[0][0].size
            val channels = input[0][0][0].size
            
            interpreter.resizeInput(0, intArrayOf(batchSize, height, width, channels))
            interpreter.allocateTensors()
            
            val outputShape = interpreter.getOutputTensor(0).shape() // Expected [batch, 1, 1, 96]
            
            val rawOutput = Array(outputShape[0]) {
                Array(outputShape[1]) {
                    Array(outputShape[2]) {
                        FloatArray(outputShape[3])
                    }
                }
            }

            interpreter.run(input, rawOutput)

            // Reshape from (batch, 1, 1, 96) to (batch, 96)
            return Array(rawOutput.size) { i ->
                rawOutput[i][0][0].copyOf()
            }
        } catch (e: Exception) {
            throw RuntimeException("Failed to generate embeddings: ${e.message}", e)
        }
    }

    override fun close() {
        interpreter.close()
    }
}
