package it.videodelay.app.util

import android.util.Log
import it.videodelay.app.service.StreamingForegroundService
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Proxy TCP locale per RTSP che corregge i CSeq errati nelle risposte del server.
 *
 * Alcuni server RTSP Android (es. "AndroidIPCamLive") inviano CSeq=0 nelle
 * risposte SETUP, violando RFC 2326. FFmpegKit è strict e rifiuta la connessione.
 * Questo proxy si interpone tra FFmpegKit e il server reale, leggendo il flusso
 * RTSP linea per linea e sostituendo il valore CSeq nelle risposte del server.
 */
class RtspCSeqProxy {

    private var acceptSock: ServerSocket? = null
    var proxyPort: Int = 0
        private set
    private var targetHost = ""
    private var targetPort = 0

    @Volatile private var clientSocket: Socket? = null
    @Volatile private var serverSocket: Socket? = null

    // ──────────────────────────── Public API ───────────────────────────

    fun start(targetHost: String, targetPort: Int) {
        this.targetHost = targetHost
        this.targetPort = targetPort
        try {
            acceptSock = ServerSocket(0).also { proxyPort = it.localPort }
            logInfo("Proxy RTSP avviato su localhost:$proxyPort → $targetHost:$targetPort")
        } catch (e: Exception) {
            logError("Impossibile creare il ServerSocket del proxy", e)
            throw e
        }

        thread(name = "rtsp-proxy-accept", isDaemon = true) {
            try {
                val client = acceptSock?.accept() ?: return@thread
                logInfo("FFmpegKit connesso al proxy")
                client.tcpNoDelay = true
                clientSocket = client

                val server = Socket(targetHost, targetPort)
                server.tcpNoDelay = true
                logInfo("Proxy connesso al server reale $targetHost:$targetPort")
                serverSocket = server

                bridge(client, server)
            } catch (_: SocketException) {
                logInfo("Proxy socket chiuso")
            } catch (e: Exception) {
                logError("Errore accettazione/connessione proxy", e)
                stop()
            }
        }
    }

    fun stop() {
        logInfo("Arresto del proxy RTSP")
        try { acceptSock?.close() } catch (_: Exception) {}
        try { clientSocket?.close() } catch (_: Exception) {}
        try { serverSocket?.close() } catch (_: Exception) {}
    }

    // ──────────────────────────── Bridge ───────────────────────────────

    private fun bridge(client: Socket, server: Socket) {
        val cIn  = client.inputStream
        val cOut = client.outputStream
        val sIn  = server.inputStream
        val sOut = server.outputStream
        val lastCSeq = AtomicInteger(0)

        // Thread client→server: traccia CSeq, riscrive URL proxy→server e gestisce body
        val c2s = thread(name = "rtsp-c2s", isDaemon = true) {
            try { pumpC2S(cIn, sOut, lastCSeq) } catch (_: Exception) {}
            silentClose(server)
        }

        // Thread server→client: corregge CSeq, riscrive URL server→proxy, gestisce body e RTP
        thread(name = "rtsp-s2c", isDaemon = true) {
            try { pumpS2C(sIn, cOut, lastCSeq) } catch (_: Exception) {}
            silentClose(client)
            c2s.interrupt()
        }
    }

    // ──────────────────────────── Client → Server ───────────────────────

    private fun pumpC2S(cIn: InputStream, sOut: OutputStream, lastCSeq: AtomicInteger) {
        while (true) {
            val lines = readHeaderLines(cIn) ?: break   // null = EOF

            var contentLength = 0
            for (line in lines) {
                if (line.startsWith("CSeq:", ignoreCase = true)) {
                    line.substringAfter(":").trim().toIntOrNull()?.let { lastCSeq.set(it) }
                    logInfo("→ CSeq tracciato: ${lastCSeq.get()}")
                } else if (line.startsWith("Content-Length:", ignoreCase = true)) {
                    contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
                }
            }

            var bodyBytes = ByteArray(0)
            if (contentLength > 0) {
                val rawBody = ByteArray(contentLength)
                if (!readFully(cIn, rawBody, contentLength)) {
                    logWarn("C→S: Impossibile leggere il body di lunghezza $contentLength")
                    break
                }
                val bodyStr = String(rawBody, ISO)
                val rewrittenBodyStr = rewriteUrlProxyToServer(bodyStr)
                bodyBytes = rewrittenBodyStr.toByteArray(ISO)
                contentLength = bodyBytes.size
            }

            val fixed = lines.map { line ->
                when {
                    line.startsWith("Content-Length:", ignoreCase = true) -> {
                        "Content-Length: $contentLength"
                    }
                    else -> rewriteUrlProxyToServer(line)
                }
            }

            val headerLines = fixed.filter { it.isNotEmpty() }
            val out = headerLines.joinToString("\r\n") + "\r\n\r\n"
            
            sOut.write(out.toByteArray(ISO))
            if (contentLength > 0) {
                sOut.write(bodyBytes)
            }
            sOut.flush()
        }
    }

    // ──────────────────────────── Server → Client ───────────────────────

    private fun pumpS2C(sIn: InputStream, cOut: OutputStream, lastCSeq: AtomicInteger) {
        while (true) {
            var first = sIn.read()
            if (first == -1) {
                logInfo("Connessione server chiusa (EOF)")
                break
            }

            // Salta eventuali caratteri di spaziatura/CRLF prima del messaggio effettivo
            while (first == '\r'.code || first == '\n'.code) {
                first = sIn.read()
                if (first == -1) break
            }
            if (first == -1) {
                logInfo("Connessione server chiusa dopo CRLF di padding (EOF)")
                break
            }

            if (first == DOLLAR) {
                // Dati RTP interleaved: $CH(1) LEN(2) DATA(LEN)
                val hdr = ByteArray(3)
                if (!readFully(sIn, hdr, 3)) {
                    logWarn("Impossibile leggere header RTP interleaved")
                    break
                }
                val len = ((hdr[1].toInt() and 0xFF) shl 8) or (hdr[2].toInt() and 0xFF)
                val pkt = ByteArray(4 + len)
                pkt[0] = DOLLAR.toByte()
                pkt[1] = hdr[0]
                pkt[2] = hdr[1]
                pkt[3] = hdr[2]
                if (!readFully(sIn, pkt, len, offset = 4)) {
                    logWarn("Impossibile leggere payload RTP interleaved (lunghezza $len)")
                    break
                }
                cOut.write(pkt)
                cOut.flush()

            } else {
                // Risposta RTSP testuale: leggi header linea per linea
                val lines = readHeaderLinesRest(sIn, first.toChar()) ?: break

                var contentLength = 0
                for (line in lines) {
                    if (line.startsWith("Content-Length:", ignoreCase = true)) {
                        contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
                        break
                    }
                }

                var bodyBytes = ByteArray(0)
                if (contentLength > 0) {
                    val rawBody = ByteArray(contentLength)
                    if (!readFully(sIn, rawBody, contentLength)) {
                        logWarn("S→C: Impossibile leggere il body di lunghezza $contentLength")
                        break
                    }
                    val bodyStr = String(rawBody, ISO)
                    val rewrittenBodyStr = rewriteUrlServerToProxy(bodyStr)
                    bodyBytes = rewrittenBodyStr.toByteArray(ISO)
                    contentLength = bodyBytes.size
                }

                val expected = lastCSeq.get()
                val fixed = lines.map { line ->
                    when {
                        line.startsWith("CSeq:", ignoreCase = true) -> {
                            val serverVal = line.substringAfter(":").trim().toIntOrNull() ?: 0
                            if (serverVal != expected && expected > 0) {
                                logWarn("CSeq fix: server=$serverVal → atteso=$expected")
                                "CSeq: $expected"
                            } else line
                        }
                        line.startsWith("Content-Length:", ignoreCase = true) -> {
                            "Content-Length: $contentLength"
                        }
                        else -> rewriteUrlServerToProxy(line)
                    }
                }

                val headerLines = fixed.filter { it.isNotEmpty() }
                val out = headerLines.joinToString("\r\n") + "\r\n\r\n"
                
                cOut.write(out.toByteArray(ISO))
                if (contentLength > 0) {
                    cOut.write(bodyBytes)
                }
                cOut.flush()
            }
        }
    }

    // ──────────────────────────── Line readers ─────────────────────────

    /**
     * Legge le righe header RTSP (include la riga vuota terminale).
     * Ritorna null solo se il flusso è chiuso prima di leggere qualunque byte.
     */
    private fun readHeaderLines(input: InputStream): MutableList<String>? {
        val lines = mutableListOf<String>()
        while (true) {
            val line = readLine(input) ?: return if (lines.isEmpty()) null else lines
            if (line.isEmpty() && lines.isEmpty()) continue // ignora righe vuote iniziali
            lines.add(line)
            if (line.isEmpty()) return lines   // riga vuota = fine header
        }
    }

    /**
     * Come [readHeaderLines] ma il primo byte è già stato letto dal chiamante.
     */
    private fun readHeaderLinesRest(input: InputStream, first: Char): MutableList<String>? {
        val lines = mutableListOf<String>()
        val sb = StringBuilder().append(first)
        while (true) {
            val b = input.read()
            if (b == -1) { lines.add(sb.toString()); return lines }
            if (b == '\r'.code) continue          // ignora CR
            if (b == '\n'.code) break             // fine riga
            sb.append(b.toChar())
        }
        lines.add(sb.toString())
        
        while (true) {
            val line = readLine(input) ?: return lines
            lines.add(line)
            if (line.isEmpty()) return lines
        }
    }

    /** Legge una singola riga CRLF dal flusso (senza il terminatore). */
    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        var bytesRead = 0
        while (true) {
            val b = input.read()
            if (b == -1) return if (bytesRead == 0) null else sb.toString()
            bytesRead++
            if (b == '\r'.code) continue          // ignora CR
            if (b == '\n'.code) return sb.toString()
            sb.append(b.toChar())
        }
    }

    // ──────────────────────────── Utilities ────────────────────────────

    private fun readFully(input: InputStream, buf: ByteArray, len: Int, offset: Int = 0): Boolean {
        var read = 0
        while (read < len) {
            val r = input.read(buf, offset + read, len - read)
            if (r == -1) return false
            read += r
        }
        return true
    }

    private fun silentClose(socket: Socket) = try { socket.close() } catch (_: Exception) {}

    private fun proxyAddr() = "127.0.0.1:$proxyPort"
    private fun serverAddr() = "$targetHost:$targetPort"

    private fun rewriteUrlProxyToServer(input: String): String {
        return input
            .replace("rtsp://${proxyAddr()}", "rtsp://${serverAddr()}")
            .replace(proxyAddr(), serverAddr())
    }

    private fun rewriteUrlServerToProxy(input: String): String {
        var rewritten = input
        rewritten = rewritten.replace("rtsp://${serverAddr()}", "rtsp://${proxyAddr()}")
        rewritten = rewritten.replace(serverAddr(), proxyAddr())
        rewritten = rewritten.replace("rtsp://$targetHost", "rtsp://${proxyAddr()}")
        return rewritten
    }

    // ──────────────────────────── Logging ─────────────────────────────

    private fun logInfo(msg: String) {
        Log.i(TAG, msg)
        try {
            StreamingForegroundService.logBuffer.add("[Proxy] INFO: $msg")
        } catch (_: Exception) {}
    }

    private fun logWarn(msg: String) {
        Log.w(TAG, msg)
        try {
            StreamingForegroundService.logBuffer.add("[Proxy] WARN: $msg")
        } catch (_: Exception) {}
    }

    private fun logError(msg: String, t: Throwable? = null) {
        val errorMsg = if (t != null) "$msg: ${t.message}" else msg
        Log.e(TAG, errorMsg, t)
        try {
            StreamingForegroundService.logBuffer.add("[Proxy] ERROR: $errorMsg")
        } catch (_: Exception) {}
    }

    companion object {
        private const val TAG = "RtspCSeqProxy"
        private const val DOLLAR = '$'.code
        private val ISO = Charsets.ISO_8859_1
    }
}
