package com.msp1974.vacompanion.utils

import android.content.Context
import android.os.Environment
import com.msp1974.vacompanion.wakeword.WakeWord
import timber.log.Timber
import java.io.File


class WakeWords(val context: Context, val ext: String) {
    var availableWakeWords = mapOf(
        "alexa" to WakeWord("Alexa", "openwakeword/alexa.$ext"),
        "hey_jarvis" to WakeWord("Hey Jarvis", "openwakeword/hey_jarvis.$ext"),
        "hey_mycroft" to WakeWord("Hey Mycroft", "openwakeword/hey_mycroft.$ext"),
        "hey_raspy" to WakeWord("Hey Rhasspy", "openwakeword/hey_rhasspy.$ext"),
        "ok_nabu" to WakeWord("Ok Nabu", "openwakeword/ok_nabu.$ext"),
        "ok_computer" to WakeWord("Ok Computer", "openwakeword/ok_computer.$ext")
    )

    fun getCustomWakeWords(path: String): Map<String, WakeWord> {
        val customWakeWords = mutableMapOf<String, WakeWord>()
        val downloadPath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val vacaDownloadDir = File(downloadPath, path)
        val vacaFilesDir = File(context.filesDir, path)

        if (vacaDownloadDir.isDirectory) {
            Timber.d("Custom wake words directory found in Downloads - ${vacaDownloadDir.absolutePath}")
            vacaDownloadDir.listFiles { f -> f.name.endsWith(".$ext") }?.forEach { entry ->
                Timber.d("Found custom wake word: ${entry.name}")
                val key = entry.name.replace(".$ext", "").lowercase()
                val name = key.replace("_", " ")
                customWakeWords[key] = WakeWord(name.capitalizeWords(), entry.absolutePath, false)
            }
        }

        if (vacaFilesDir.isDirectory) {
            Timber.d("Custom wake words directory found in App files - ${vacaFilesDir.absolutePath}")
            vacaFilesDir.listFiles { f -> f.name.endsWith(".$ext") }?.forEach { entry ->
                Timber.d("Found custom wake word: ${entry.name}")
                val key = entry.name.replace(".$ext", "").lowercase()
                val name = key.replace("_", " ")
                customWakeWords[key] = WakeWord(name.capitalizeWords(), entry.absolutePath, false)
            }
        }
        return customWakeWords
    }

    fun String.capitalizeWords(delimiter: String = " ") =
        split(delimiter).joinToString(delimiter) { word ->

            val smallCaseWord = word.lowercase()
            smallCaseWord.replaceFirstChar(Char::titlecaseChar)

        }

    fun getWakeWords(): Map<String, WakeWord> {
        return  mutableMapOf<String, WakeWord>().apply {
            putAll(availableWakeWords)
            putAll(getCustomWakeWords("vaca"))
        }
    }
}