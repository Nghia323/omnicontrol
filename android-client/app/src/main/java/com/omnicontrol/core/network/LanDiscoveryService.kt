package com.omnicontrol.core.network

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Base64
import com.omnicontrol.core.security.DeviceIdentityKeyManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

data class DiscoveredLanDevice(
    val deviceId: String,
    val deviceName: String,
    val platform: String,
    val hostIp: String,
    val port: Int,
    val ed25519PublicKey: ByteArray,
    val keyFingerprint: String,
    val lastSeenEpochMs: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DiscoveredLanDevice) return false
        return deviceId == other.deviceId &&
            hostIp == other.hostIp &&
            port == other.port &&
            ed25519PublicKey.contentEquals(other.ed25519PublicKey)
    }

    override fun hashCode(): Int {
        var result = deviceId.hashCode()
        result = 31 * result + hostIp.hashCode()
        result = 31 * result + ed25519PublicKey.contentHashCode()
        return result
    }
}

class LanDiscoveryService(
    context: Context,
    private val localDeviceId: String,
    private val localDeviceName: String,
    private val localPlatform: String,
    private val listenPort: Int,
    private val keyManager: DeviceIdentityKeyManager
) {
    companion object {
        const val DISCOVERY_PORT = 49152
        private const val BEACON_MAGIC = "OMNI_LAN_V1"
        private const val BROADCAST_INTERVAL_MS = 3_000L
        private const val STALE_DEVICE_TIMEOUT_MS = 12_000L
    }

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val isRunning = AtomicBoolean(false)
    private val discoveredMap = ConcurrentHashMap<String, DiscoveredLanDevice>()

    private val _lanDevices = MutableStateFlow<List<DiscoveredLanDevice>>(emptyList())
    val lanDevices: StateFlow<List<DiscoveredLanDevice>> = _lanDevices.asStateFlow()

    private var multicastLock: WifiManager.MulticastLock? = null
    private var socket: DatagramSocket? = null

    fun start() {
        if (isRunning.getAndSet(true)) return
        val wifiManager = appContext.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        multicastLock = wifiManager?.createMulticastLock("OmniControlLanLock")?.apply {
            setReferenceCounted(true)
            acquire()
        }
        val udpSocket = DatagramSocket(DISCOVERY_PORT).apply {
            broadcast = true
            soTimeout = 1_000
        }
        this.socket = udpSocket
        startReceiveLoop(udpSocket)
        startBroadcastLoop(udpSocket)
        startCleanupLoop()
    }

    fun stop() {
        if (!isRunning.getAndSet(false)) return
        scope.coroutineContext.cancelChildren()
        socket?.close()
        socket = null
        multicastLock?.let { if (it.isHeld) it.release() }
        multicastLock = null
        discoveredMap.clear()
        _lanDevices.value = emptyList()
    }

    fun release() {
        stop()
        scope.cancel()
    }

    private fun startReceiveLoop(udpSocket: DatagramSocket) {
        scope.launch {
            val buf = ByteArray(2048)
            while (isActive && isRunning.get()) {
                try {
                    val packet = DatagramPacket(buf, buf.size)
                    udpSocket.receive(packet)
                    val rawJson = String(packet.data, 0, packet.length, StandardCharsets.UTF_8)
                    val senderIp = packet.address?.hostAddress ?: continue
                    parseBeacon(rawJson, senderIp)?.let { device ->
                        if (device.deviceId != localDeviceId) {
                            discoveredMap[device.deviceId] = device
                            publishSortedDevices()
                        }
                    }
                } catch (_: SocketTimeoutException) {
                } catch (_: Exception) {
                    if (!isRunning.get()) break
                }
            }
        }
    }

    private fun startBroadcastLoop(udpSocket: DatagramSocket) {
        scope.launch {
            val broadcastAddr = InetAddress.getByName("255.255.255.255")
            val pubKeyB64 = Base64.encodeToString(keyManager.getPublicIdentityKey(), Base64.NO_WRAP)
            while (isActive && isRunning.get()) {
                try {
                    val payload = JSONObject().apply {
                        put("magic", BEACON_MAGIC)
                        put("device_id", localDeviceId)
                        put("device_name", localDeviceName)
                        put("platform", localPlatform)
                        put("port", listenPort)
                        put("pub_key", pubKeyB64)
                    }.toString().toByteArray(StandardCharsets.UTF_8)
                    udpSocket.send(DatagramPacket(payload, payload.size, broadcastAddr, DISCOVERY_PORT))
                } catch (_: Exception) {
                }
                delay(BROADCAST_INTERVAL_MS)
            }
        }
    }

    private fun startCleanupLoop() {
        scope.launch {
            while (isActive && isRunning.get()) {
                delay(4_000L)
                val now = System.currentTimeMillis()
                if (discoveredMap.entries.removeIf { (_, dev) -> now - dev.lastSeenEpochMs > STALE_DEVICE_TIMEOUT_MS }) {
                    publishSortedDevices()
                }
            }
        }
    }

    private fun parseBeacon(rawJson: String, senderIp: String): DiscoveredLanDevice? {
        return try {
            val obj = JSONObject(rawJson)
            if (obj.optString("magic") != BEACON_MAGIC) return null
            val deviceId = obj.getString("device_id")
            val deviceName = obj.getString("device_name")
            val platform = obj.optString("platform", "unknown")
            val port = obj.optInt("port", listenPort)
            val pubKeyBytes = Base64.decode(obj.getString("pub_key"), Base64.NO_WRAP)
            require(pubKeyBytes.size == 32)
            DiscoveredLanDevice(
                deviceId = deviceId,
                deviceName = deviceName,
                platform = platform,
                hostIp = senderIp,
                port = port,
                ed25519PublicKey = pubKeyBytes,
                keyFingerprint = keyManager.computeFingerprint(pubKeyBytes),
                lastSeenEpochMs = System.currentTimeMillis()
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun publishSortedDevices() {
        _lanDevices.value = discoveredMap.values.sortedBy { it.deviceName.lowercase() }
    }
}
