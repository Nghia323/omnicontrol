package com.omnicontrol.core.webrtc

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.webrtc.PeerConnection
import org.webrtc.RTCStatsReport
import org.webrtc.RtpSender
import java.math.BigInteger

enum class StreamQualityProfile(
    val label: String,
    val maxBitrateBps: Int,
    val scaleResolutionDownBy: Double,
    val targetFps: Int
) {
    ULTRA_LOW_LATENCY("Tiết kiệm băng thông (720p • 20fps)", 1_200_000, 1.5, 20),
    BALANCED("Cân bằng (720p • 30fps)", 2_800_000, 1.0, 30),
    HIGH_CLARITY("Độ nét cao (1080p • 60fps)", 6_000_000, 1.0, 60)
}

data class NetworkTelemetry(
    val rttMs: Double = 0.0,
    val packetLossPercent: Double = 0.0,
    val activeProfile: StreamQualityProfile = StreamQualityProfile.BALANCED
)

class AdaptiveBitrateController(
    private val peerConnection: PeerConnection,
    private val videoSender: RtpSender?,
    private val onApplyQualityProfile: (StreamQualityProfile) -> Unit
) {
    companion object {
        private const val TAG = "AdaptiveBitrateCtrl"
        private const val POLL_INTERVAL_MS = 2_000L
        private const val CONSECUTIVE_SAMPLES_REQUIRED = 2
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _telemetry = MutableStateFlow(NetworkTelemetry())
    val telemetry: StateFlow<NetworkTelemetry> = _telemetry.asStateFlow()

    private var lastPacketsLost: Long? = null
    private var lastPacketsReceived: Long? = null
    private var currentProfile = StreamQualityProfile.BALANCED
    private var degradeStreak = 0
    private var upgradeStreak = 0

    fun start() {
        stop()
        scope.launch {
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                sampleStatsReport()
            }
        }
    }

    fun stop() {
        scope.coroutineContext.cancelChildren()
        lastPacketsLost = null
        lastPacketsReceived = null
        degradeStreak = 0
        upgradeStreak = 0
    }

    fun release() {
        stop()
        scope.cancel()
    }

    private fun sampleStatsReport() {
        peerConnection.getStats { report: RTCStatsReport ->
            var rttSec = 0.0
            var totalLost = 0L
            var totalReceived = 0L

            for (stats in report.statsMap.values) {
                when (stats.type) {
                    "candidate-pair" -> {
                        val state = stats.members["state"] as? String
                        val nominated = stats.members["nominated"] as? Boolean ?: false
                        if (state == "succeeded" || nominated) {
                            val rttVal = stats.members["currentRoundTripTime"]
                            if (rttVal is Number) rttSec = rttVal.toDouble()
                        }
                    }
                    "inbound-rtp", "outbound-rtp" -> {
                        if (stats.members["kind"] == "video") {
                            totalLost += extractLong(stats.members["packetsLost"])
                            totalReceived += extractLong(
                                stats.members[if (stats.type == "inbound-rtp") "packetsReceived" else "packetsSent"]
                            )
                        }
                    }
                }
            }
            val intervalLossPercent = computeIntervalPacketLossPercent(totalLost, totalReceived)
            evaluateQualityAdjustment(rttMs = rttSec * 1000.0, lossPercent = intervalLossPercent)
        }
    }

    private fun computeIntervalPacketLossPercent(currentLost: Long, currentTotal: Long): Double {
        val prevLost = lastPacketsLost
        val prevTotal = lastPacketsReceived
        lastPacketsLost = currentLost
        lastPacketsReceived = currentTotal
        if (prevLost == null || prevTotal == null) return 0.0
        val deltaLost = (currentLost - prevLost).coerceAtLeast(0L)
        val deltaTotal = (currentTotal - prevTotal).coerceAtLeast(0L)
        val denominator = deltaLost + deltaTotal
        return if (denominator == 0L) 0.0 else (deltaLost.toDouble() / denominator.toDouble()) * 100.0
    }

    private fun evaluateQualityAdjustment(rttMs: Double, lossPercent: Double) {
        val shouldDegrade = lossPercent > 3.5 || rttMs > 160.0
        val canUpgrade = lossPercent < 0.8 && rttMs < 65.0 && rttMs > 0.0

        if (shouldDegrade) {
            degradeStreak++
            upgradeStreak = 0
            if (degradeStreak >= CONSECUTIVE_SAMPLES_REQUIRED) {
                degradeStreak = 0
                val nextProfile = when (currentProfile) {
                    StreamQualityProfile.HIGH_CLARITY -> StreamQualityProfile.BALANCED
                    else -> StreamQualityProfile.ULTRA_LOW_LATENCY
                }
                applyProfileIfChanged(nextProfile, rttMs, lossPercent)
            }
        } else if (canUpgrade) {
            upgradeStreak++
            degradeStreak = 0
            if (upgradeStreak >= CONSECUTIVE_SAMPLES_REQUIRED) {
                upgradeStreak = 0
                val nextProfile = when (currentProfile) {
                    StreamQualityProfile.ULTRA_LOW_LATENCY -> StreamQualityProfile.BALANCED
                    else -> StreamQualityProfile.HIGH_CLARITY
                }
                applyProfileIfChanged(nextProfile, rttMs, lossPercent)
            }
        } else {
            degradeStreak = 0
            upgradeStreak = 0
        }

        _telemetry.value = NetworkTelemetry(rttMs, lossPercent, currentProfile)
    }

    private fun applyProfileIfChanged(target: StreamQualityProfile, rttMs: Double, lossPercent: Double) {
        if (target == currentProfile) return
        currentProfile = target
        videoSender?.let { sender ->
            val params = sender.parameters
            params.encodings.forEach { encoding ->
                encoding.maxBitrateBps = target.maxBitrateBps
                encoding.scaleResolutionDownBy = target.scaleResolutionDownBy
                encoding.maxFramerate = target.targetFps
            }
            sender.parameters = params
        }
        Log.i(TAG, "Chuyển cấu hình luồng sang ${target.label} (RTT=${rttMs.toInt()}ms, Loss=${"%.2f".format(lossPercent)}%)")
        onApplyQualityProfile(target)
    }

    private fun extractLong(raw: Any?): Long = when (raw) {
        is Number -> raw.toLong()
        is BigInteger -> raw.toLong()
        else -> 0L
    }
}
