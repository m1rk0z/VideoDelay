package it.videodelay.app.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

data class DiscoveredCamera(
    val serviceName: String,
    val displayName: String,
    val host: String,
    val port: Int,
    val rtspUrl: String
)

/**
 * Scopre automaticamente le telecamere IPCAM sulla rete locale via NSD (mDNS),
 * evitando all'utente di inserire manualmente IP/porta o scansionare un QR code.
 */
class CameraDiscoveryManager(context: Context) {

    companion object {
        private const val TAG = "CameraDiscoveryManager"
        const val SERVICE_TYPE = "_ipcam._tcp."
    }

    private val nsdManager =
        context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager

    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private val resolveQueue = ConcurrentLinkedQueue<NsdServiceInfo>()
    private val isResolving = AtomicBoolean(false)
    private val resolvedServiceNames = mutableSetOf<String>()

    fun startDiscovery(onFound: (DiscoveredCamera) -> Unit, onLost: (String) -> Unit) {
        stopDiscovery()
        resolvedServiceNames.clear()

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.d(TAG, "Discovery started for $serviceType")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                resolveQueue.add(service)
                resolveNext(onFound)
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                resolvedServiceNames.remove(service.serviceName)
                onLost(service.serviceName)
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(TAG, "Discovery stopped")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Start discovery failed: $errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "Stop discovery failed: $errorCode")
            }
        }
        discoveryListener = listener

        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting discovery", e)
        }
    }

    /** Risolve un servizio alla volta: NsdManager non supporta resolve concorrenti. */
    private fun resolveNext(onFound: (DiscoveredCamera) -> Unit) {
        if (!isResolving.compareAndSet(false, true)) return
        val service = resolveQueue.poll()
        if (service == null) {
            isResolving.set(false)
            return
        }

        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "Resolve failed for ${info.serviceName}: $errorCode")
                isResolving.set(false)
                resolveNext(onFound)
            }

            override fun onServiceResolved(info: NsdServiceInfo) {
                isResolving.set(false)
                val host = info.host?.hostAddress
                if (host != null && resolvedServiceNames.add(info.serviceName)) {
                    val path = info.attributes["path"]?.toString(Charsets.UTF_8) ?: "/live"
                    val displayName =
                        info.attributes["name"]?.toString(Charsets.UTF_8) ?: info.serviceName
                    onFound(
                        DiscoveredCamera(
                            serviceName = info.serviceName,
                            displayName = displayName,
                            host = host,
                            port = info.port,
                            rtspUrl = "rtsp://$host:${info.port}$path"
                        )
                    )
                }
                resolveNext(onFound)
            }
        }

        try {
            nsdManager.resolveService(service, resolveListener)
        } catch (e: Exception) {
            Log.e(TAG, "Error resolving service", e)
            isResolving.set(false)
            resolveNext(onFound)
        }
    }

    fun stopDiscovery() {
        discoveryListener?.let {
            try {
                nsdManager.stopServiceDiscovery(it)
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping discovery", e)
            }
        }
        discoveryListener = null
        resolveQueue.clear()
        isResolving.set(false)
    }
}
