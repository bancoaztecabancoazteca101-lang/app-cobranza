package com.example.matrizapp
import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

class AudioHelper(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var currentFilePath: String? = null

    fun startRecording(): String {
        if (recorder != null) return currentFilePath ?: ""
        val fileName = "AUDIO_${System.currentTimeMillis()}.m4a"
        val audioDir = File(context.filesDir, "audios").apply { if (!exists()) mkdirs() }
        currentFilePath = File(audioDir, fileName).absolutePath
        recorder = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else MediaRecorder()).apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setOutputFile(currentFilePath)
            prepare(); start()
        }
        return currentFilePath!!
    }

    fun stopRecording(): String? {
        val activeRecorder = recorder ?: return null
        return try {
            activeRecorder.apply { stop(); release() }
            recorder = null
            currentFilePath
        } catch (e: Exception) {
            activeRecorder.release(); recorder = null
            currentFilePath?.let { File(it).delete() }; null
        }
    }

    fun cancelRecording() {
        val activeRecorder = recorder ?: return
        try { activeRecorder.apply { stop(); release() } } catch (e: Exception) { activeRecorder.release() }
        finally { recorder = null; currentFilePath?.let { File(it).delete() }; currentFilePath = null }
    }
}