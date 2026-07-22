// Top-level build file
plugins {
    alias(libs.plugins.android.application) apply false
    // NOTA: kotlin-android NON serve in AGP 9+: Kotlin è integrato nativamente
    alias(libs.plugins.ksp) apply false
}