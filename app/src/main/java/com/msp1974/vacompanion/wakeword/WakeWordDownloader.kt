package com.msp1974.vacompanion.wakeword

import android.content.Context
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.utils.AuthUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URL
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

enum class WakeWordType {
    OPENWAKEWORD,
    MICROWAKEWORD
}

/**
 * Represents the status of a wake word download.
 */
sealed class DownloadStatus {
    data class Progress(val fileName: String, val progress: Int) : DownloadStatus()
    data class Success(val fileName: String, val filePath: String) : DownloadStatus()
    data class Error(val fileName: String, val message: String) : DownloadStatus()
}

/**
 * Utility class to download wake word models from a URL and store them in the app's internal storage.
 * Emits download status via Flow.
 */
class WakeWordDownloader(private val context: Context, val config: APPConfig) {

    private val client = OkHttpClient()

    companion object {
        private const val CUSTOM_DIR = "custom"
        private const val WAKEWORDS_DIR = "wakewords"
    }

    /**
     * Downloads all files for a specific wake word type and name.
     */
    fun downloadWakeWord(wakeWordType: WakeWordType, name: String): Flow<DownloadStatus> = flow {
        val fileNameBase = name.split(".")[0]
        val files = when(wakeWordType) {
            WakeWordType.MICROWAKEWORD -> listOf("$fileNameBase.json", "$fileNameBase.tflite")
            WakeWordType.OPENWAKEWORD -> listOf("$fileNameBase.onnx", "$fileNameBase.tflite")
        }

        val type = wakeWordType.toString().lowercase()
        val baseUrl = AuthUtils.getHAUrl(config, false)
        val urlBase = URL(URL(baseUrl), "vaca/$CUSTOM_DIR/$type/")

        for (file in files) {
            val fileUrl = URL(urlBase, file).toString()
            downloadFile(type, fileUrl, file).collect { status ->
                emit(status)
            }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Downloads a single file and emits its status.
     */
    private fun downloadFile(type: String, url: String, fileName: String): Flow<DownloadStatus> = flow {
        val targetDir = Path(context.filesDir.absolutePath, CUSTOM_DIR, WAKEWORDS_DIR, type)
        if (!targetDir.exists()) {
            try {
                targetDir.createDirectories()
            } catch (e: Exception) {
                emit(DownloadStatus.Error(fileName, "Failed to create directory: $targetDir"))
                return@flow
            }
        }

        val targetFile = File(targetDir.toString(), fileName)
        val request = Request.Builder().url(url).build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    emit(DownloadStatus.Error(fileName, "Download failed: HTTP ${response.code}"))
                    return@flow
                }

                val body = response.body

                val contentLength = body.contentLength()
                body.byteStream().use { input ->
                    FileOutputStream(targetFile).use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        var totalBytesRead: Long = 0
                        
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            totalBytesRead += bytesRead
                            
                            if (contentLength > 0) {
                                val progress = ((totalBytesRead * 100) / contentLength).toInt()
                                emit(DownloadStatus.Progress(fileName, progress))
                            }
                        }
                    }
                }
                
                Timber.i("Successfully downloaded $fileName to $targetFile")
                emit(DownloadStatus.Success(fileName, targetFile.toString()))
            }
        } catch (e: IOException) {
            Timber.e(e, "Error downloading wake word from $url")
            emit(DownloadStatus.Error(fileName, e.message ?: "Unknown I/O error"))
        }
    }

    /**
     * Returns the file for a previously downloaded wake word.
     */
    fun getDownloadedFile(type: String, fileName: String): File? {
        val file = File("${context.filesDir}/$CUSTOM_DIR/$WAKEWORDS_DIR/$type", fileName)
        return if (file.exists()) file else null
    }

    fun fileExists(type: String, fileName: String): Boolean {
        val file = File("${context.filesDir}/$CUSTOM_DIR/$WAKEWORDS_DIR/$type", fileName)
        return file.exists()
    }
    
    /**
     * Deletes a downloaded wake word file.
     */
    fun deleteFile(type: String, fileName: String): Boolean {
        return getDownloadedFile(type, fileName)?.delete() ?: false
    }

    suspend fun downloadsNeeded(availableWakeWords: JsonElement) {
        for (wakeWordType in availableWakeWords as JsonObject) {
            for (wakeWord in wakeWordType.value as JsonObject) {
                Timber.i("WAKEWORDS: $wakeWord")
                val wakeWordType = if (wakeWordType.key == "microwakeword") WakeWordType.MICROWAKEWORD else WakeWordType.OPENWAKEWORD
                val file = "${wakeWord.key}.tflite"
                if (!fileExists(wakeWordType.toString().lowercase(), file)) {
                    Timber.i("Download of ${wakeWord.key} needed")
                    downloadWakeWord(wakeWordType, file).collect { state ->
                        Timber.d("WAKEWORD DOWNLOAD STATE: $state")
                    }
                }
            }
        }
    }
}
