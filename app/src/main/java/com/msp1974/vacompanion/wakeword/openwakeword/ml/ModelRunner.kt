package com.msp1974.vacompanion.wakeword.openwakeword.ml

import com.msp1974.vacompanion.wakeword.openwakeword.model.WakeWordModel

interface ModelRunner: AutoCloseable {

    fun loadModel(model: WakeWordModel): ByteArray
    fun predictWakeWord(inputArray: Array<Array<FloatArray>>): Float
    override fun close()
}