# 🎥 VideoDelay

[![Android](https://img.shields.io/badge/Platform-Android-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://developer.android.com/)
[![Kotlin](https://img.shields.io/badge/Language-Kotlin-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![ExoPlayer](https://img.shields.io/badge/Media3-ExoPlayer-2563EB?style=for-the-badge)](https://developer.android.com/media/media3)
[![License](https://img.shields.io/badge/License-MIT-blue.svg?style=for-the-badge)](LICENSE)

**VideoDelay** è un'applicazione Android professionale progettata per l'assistenza tecnica e l'analisi tattico-sportiva a bordo campo. Consente ad allenatori, preparatori atletici e sportivi di riprodurre flussi video IP in tempo reale con un **ritardo temporale (time-shift delay)** configurabile, per revisionare i gesti tecnici immediatamente dopo la loro esecuzione.

---

## ✨ Funzionalità Principali

### ⏱️ Time-Shift & Delay Configurabile
- **Differita Personalizzabile**: Imposta il ritardo desiderato (es. 5s, 10s, 20s, 30s) per consentire all'atleta di finire l'esercizio e guardare subito il monitor.
- **Replay Istantaneo**: Tasti rapidi per riavvolgere al volo gli ultimi secondi dell'azione.
- **Timeline Interattiva**: Navigazione fluida lungo tutto il buffer di memoria registrato.
- **Servizio in Foreground (`StreamingForegroundService`)**: Garantisce la registrazione continua e stabile del buffer anche con app in background.

### 📹 Gestione Fotocamere & Streaming IP
- **Supporto Sorgenti Multiple**: Compatibile con flussi video **RTSP** e **HLS**.
- **Scanner QR Code Integrato**: Scansiona il codice QR di una videocamera IP per aggiungerla all'istante.
- **Monitoraggio Latenza (Ping)**: Verifica in tempo reale la qualità della connessione di ogni telecamera con indicatori di ping in millisecondi.
- **Modalità Demo**: Permette di testare tutte le funzionalità dell'app anche senza sorgenti IP collegate.

### 📸 Cattura Screenshot & Editor Tecnico
- **Cattura Pulita (Clean Capture)**: Durante la cattura dello screenshot, l'interfaccia (HUD, loghi e pannelli) viene nascosta per una frazione di secondo, estraendo il frame video nativo a risoluzione piena.
- **Editor di Disegno Integrato**: Strumento per disegnare a mano libera, tracciare linee e aggiungere note tecniche direttamente sul fermo immagine.

### 🖼️ Galleria & Gestione Avanzata Immagini
- **Modalità Selezione Multipla**: Seleziona più immagini tenendo premuto o tramite il tasto di selezione dedicato.
- **Condivisione & Eliminazione di Massa**: Condividi multipli screenshot contemporaneamente via WhatsApp, Email o altre app.
- **Piena Compatibilità Android 11+ (Scoped Storage)**: Eliminazione sicura conforme alle policy di sicurezza Android tramite l'API `MediaStore.createDeleteRequest`.

### 🎨 Design System "Cyber Teal"
- **Tema Scuro Tecnologico**: Sfondo Navy scuro (`#0F172A`) e superfici Blu Tech (`#192134`) con accenti Ciano (`#06B6D4`) e Teal (`#0EA5E9`).
- **Supporto Window Insets (`fitsSystemWindows`)**: Layout ottimizzato per evitare sovrapposizioni con notch, fotocamera frontale o barra delle notifiche di sistema sia in modalità Verticale che Orizzontale.

---

## 🛠️ Architettura & Tecnologie

| Componente | Tecnologia / Libreria |
| --- | --- |
| **Linguaggio** | Kotlin |
| **Min SDK** | API 24 (Android 7.0) |
| **Target SDK** | API 34+ (Android 14) |
| **Media Engine** | AndroidX Media3 / ExoPlayer |
| **QR Scanner** | ZXing Embedded |
| **Async Tasks & State** | Kotlin Coroutines, LiveData, ViewModel |
| **UI Components** | Material Components, ConstraintLayout, ViewBinding |

---

## 🚀 Istruzioni di Compilazione

### Prerequisiti
- Android Studio Hedgehog (2023.1.1) o superiore
- JDK 17
- Android SDK 34

### Comandi Gradle Rapidi
```bash
# Verifica compilazione Kotlin
.\gradlew compileDebugKotlin

# Generazione APK Debug
.\gradlew assembleDebug

# Generazione APK Release firmato
.\gradlew assembleRelease
```
L'APK compilato si troverà in: `app/build/outputs/apk/release/app-release.apk`

---

## 📄 Licenza

Questo progetto è distribuito sotto licenza MIT. Per maggiori informazioni consulta il file [LICENSE](LICENSE).
