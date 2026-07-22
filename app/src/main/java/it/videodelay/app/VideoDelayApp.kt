package it.videodelay.app

import android.app.Application
import it.videodelay.app.data.db.AppDatabase
import it.videodelay.app.data.repository.CameraRepository

import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

class VideoDelayApp : Application() {

    val database by lazy { AppDatabase.getInstance(this) }
    val cameraRepository by lazy { CameraRepository(database) }

    override fun onCreate() {
        super.onCreate()
        
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val stackTrace = sw.toString()
                
                Log.e("CRASH", "Uncaught exception in thread ${thread.name}:", throwable)
                
                val targetDir = externalCacheDir ?: cacheDir
                if (targetDir != null) {
                    val logFile = File(targetDir, "crash_log.txt")
                    logFile.writeText("Thread: ${thread.name}\nException:\n$stackTrace")
                    Log.d("CRASH", "Crash log written to: ${logFile.absolutePath}")
                }
            } catch (e: Exception) {
                Log.e("CRASH", "Failed to write crash log", e)
            }
            
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
