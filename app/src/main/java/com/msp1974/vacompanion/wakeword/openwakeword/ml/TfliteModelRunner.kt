package com.msp1974.vacompanion.wakeword.openwakeword.ml

import android.content.res.AssetManager
import com.msp1974.vacompanion.wakeword.openwakeword.model.WakeWordModel
import org.tensorflow.lite.Interpreter
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.io.path.Path

/**
 * Handles LiteRT (TFLite) model loading and inference for wake word detection.
 */
internal class TfliteModelRunner(
    private val assetManager: AssetManager,
    private val model: WakeWordModel
) : ModelRunner {

    private var interpreter: Interpreter = createInterpreter()

    private fun createInterpreter(): Interpreter {
        return try {
            val modelBytes = loadModel(model)
            val buffer = ByteBuffer.allocateDirect(modelBytes.size).apply {
                order(ByteOrder.nativeOrder())
                put(modelBytes)
                rewind()
            }
            val options = Interpreter.Options().apply {
                setNumThreads(1)
            }
            Interpreter(buffer, options)
        } catch (e: IOException) {
            throw RuntimeException("Failed to load model: ${model.modelPath}", e)
        }
    }

    override fun loadModel(model: WakeWordModel): ByteArray {
        return if (model.builtIn) {
            assetManager.open(model.modelPath).use { inputStream ->
                inputStream.readBytes()
            }
        } else {
            val file = Path(model.modelPath).toFile()
            file.readBytes()
        }
    }

    /**
     * Run inference on the wake word detection model.
     *
     * @param inputArray 3D float array of shape [1, features, embeddings]
     * @return Prediction score between 0.0 and 1.0
     */
    override fun predictWakeWord(inputArray: Array<Array<FloatArray>>): Float {
        try {
            // Explicitly resize input and allocate tensors to ensure consistency
            val batchSize = inputArray.size
            val features = inputArray[0].size
            val embeddings = inputArray[0][0].size
            
            interpreter.resizeInput(0, intArrayOf(batchSize, features, embeddings))
            interpreter.allocateTensors()
            
            val outputShape = interpreter.getOutputTensor(0).shape()
            val output = Array(outputShape[0]) { FloatArray(outputShape[1]) }
            
            interpreter.run(inputArray, output)
            return output[0][0]
        } catch (e: Exception) {
            throw RuntimeException("Failed to run inference: ${e.message}", e)
        }
    }

    override fun close() {
        interpreter.close()
    }
}
