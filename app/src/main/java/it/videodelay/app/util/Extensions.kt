package it.videodelay.app.util

import android.view.View

/** Mostra la View */
fun View.show() { visibility = View.VISIBLE }

/** Nasconde la View (GONE) */
fun View.hide() { visibility = View.GONE }

/** Formatta ms in stringa MM:SS */
fun Long.toTimeString(): String {
    val totalSec = this / 1000
    val m = totalSec / 60
    val s = totalSec % 60
    return "%02d:%02d".format(m, s)
}

/** Formatta secondi in stringa MM:SS */
fun Int.toTimeString(): String = (this * 1000L).toTimeString()

/** Sanifica una stringa per l'uso in nomi di file/cartelle MediaStore */
fun String.sanitizedForFilename(): String = replace(Regex("[^A-Za-z0-9_-]"), "_")

/** Rileva se l'app è in esecuzione su un emulatore Android */
fun isEmulator(): Boolean {
    val fingerprint = android.os.Build.FINGERPRINT
    val model = android.os.Build.MODEL
    val hardware = android.os.Build.HARDWARE
    return fingerprint.startsWith("generic")
            || fingerprint.startsWith("unknown")
            || model.contains("google_sdk")
            || model.contains("Emulator")
            || model.contains("Android SDK built for x86")
            || hardware.contains("goldfish")
            || hardware.contains("ranchu")
            || (android.os.Build.BRAND.startsWith("generic") && android.os.Build.DEVICE.startsWith("generic"))
            || "google_sdk" == android.os.Build.PRODUCT
}

