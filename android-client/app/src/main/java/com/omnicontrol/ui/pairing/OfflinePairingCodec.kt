package com.omnicontrol.ui.pairing

import android.util.Base64
import com.google.crypto.tink.subtle.Ed25519Verify
import com.omnicontrol.core.security.DeviceIdentityKeyManager
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException

data class VerifiedQrPairingPayload(
    val deviceId: String,
    val deviceName: String,
    val platform: String,
    val ed25519PublicKey: ByteArray,
    val keyFingerprint: String,
    val issuedAtEpochSec: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VerifiedQrPairingPayload) return false
        return deviceId == other.deviceId &&
            deviceName == other.deviceName &&
            platform == other.platform &&
            ed25519PublicKey.contentEquals(other.ed25519PublicKey) &&
            issuedAtEpochSec == other.issuedAtEpochSec
    }

    override fun hashCode(): Int {
        var result = deviceId.hashCode()
        result = 31 * result + ed25519PublicKey.contentHashCode()
        result = 31 * result + issuedAtEpochSec.hashCode()
        return result
    }
}

sealed interface QrDecodeResult {
    data class Valid(val payload: VerifiedQrPairingPayload) : QrDecodeResult
    data object Expired : QrDecodeResult
    data object SelfScanIgnored : QrDecodeResult
    data class Invalid(val reason: String) : QrDecodeResult
}

object OfflinePairingCodec {
    private const val URI_PREFIX = "omnicontrol://pair/v1#"
    private const val BINARY_VERSION: Byte = 0x01
    private const val ED25519_PUB_LEN = 32
    private const val ED25519_SIG_LEN = 64
    private const val MAX_QR_AGE_SEC = 120L
    private const val MAX_CLOCK_SKEW_SEC = 30L

    fun encodeSignedPairingUri(
        deviceId: String,
        deviceName: String,
        platform: String,
        keyManager: DeviceIdentityKeyManager,
        nowEpochSec: Long = System.currentTimeMillis() / 1000L
    ): String {
        val idBytes = deviceId.toByteArray(StandardCharsets.UTF_8)
        val trimmedName = deviceName.take(48)
        val nameBytes = trimmedName.toByteArray(StandardCharsets.UTF_8)
        val publicKey = keyManager.getPublicIdentityKey()

        require(idBytes.size in 1..128) { "deviceId không hợp lệ" }
        require(nameBytes.size in 1..128) { "deviceName không hợp lệ" }
        require(publicKey.size == ED25519_PUB_LEN) { "Khóa Ed25519 phải dài 32 bytes" }

        val platformCode = platformToByte(platform)
        val unsignedLen = 1 + 1 + 8 + 1 + idBytes.size + 1 + nameBytes.size + ED25519_PUB_LEN

        val unsignedBuffer = ByteBuffer.allocate(unsignedLen).order(ByteOrder.BIG_ENDIAN).apply {
            put(BINARY_VERSION)
            put(platformCode)
            putLong(nowEpochSec)
            put(idBytes.size.toByte())
            put(idBytes)
            put(nameBytes.size.toByte())
            put(nameBytes)
            put(publicKey)
        }
        val unsignedBytes = unsignedBuffer.array()
        val signature = keyManager.signWithDeviceIdentity(unsignedBytes)

        val fullPayload = ByteArray(unsignedLen + ED25519_SIG_LEN).apply {
            System.arraycopy(unsignedBytes, 0, this, 0, unsignedLen)
            System.arraycopy(signature, 0, this, unsignedLen, ED25519_SIG_LEN)
        }

        val encoded = Base64.encodeToString(
            fullPayload,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        return URI_PREFIX + encoded
    }

    fun decodeAndVerify(
        rawQrText: String,
        localDeviceId: String,
        keyManager: DeviceIdentityKeyManager,
        nowEpochSec: Long = System.currentTimeMillis() / 1000L
    ): QrDecodeResult {
        if (!rawQrText.startsWith(URI_PREFIX)) {
            return QrDecodeResult.Invalid("Mã QR không thuộc hệ thống OmniControl")
        }
        val b64Part = rawQrText.removePrefix(URI_PREFIX)
        val rawBytes = try {
            Base64.decode(b64Part, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        } catch (_: IllegalArgumentException) {
            return QrDecodeResult.Invalid("Dữ liệu Base64 trong mã QR bị hỏng")
        }
        val minSize = 1 + 1 + 8 + 1 + 1 + 1 + 1 + ED25519_PUB_LEN + ED25519_SIG_LEN
        if (rawBytes.size < minSize) {
            return QrDecodeResult.Invalid("Gói dữ liệu QR quá ngắn")
        }
        return try {
            val buf = ByteBuffer.wrap(rawBytes).order(ByteOrder.BIG_ENDIAN)
            val version = buf.get()
            if (version != BINARY_VERSION) {
                return QrDecodeResult.Invalid("Phiên bản giao thức QR không được hỗ trợ ($version)")
            }
            val platform = byteToPlatform(buf.get())
            val issuedAtSec = buf.long
            val idLen = buf.get().toInt() and 0xFF
            if (idLen == 0 || buf.remaining() < idLen + 1 + ED25519_PUB_LEN + ED25519_SIG_LEN) {
                return QrDecodeResult.Invalid("Cấu trúc định danh thiết bị không hợp lệ")
            }
            val idBytes = ByteArray(idLen).also { buf.get(it) }
            val deviceId = String(idBytes, StandardCharsets.UTF_8)
            if (deviceId == localDeviceId) {
                return QrDecodeResult.SelfScanIgnored
            }
            val nameLen = buf.get().toInt() and 0xFF
            if (nameLen == 0 || buf.remaining() != nameLen + ED25519_PUB_LEN + ED25519_SIG_LEN) {
                return QrDecodeResult.Invalid("Cấu trúc tên thiết bị không hợp lệ")
            }
            val nameBytes = ByteArray(nameLen).also { buf.get(it) }
            val deviceName = String(nameBytes, StandardCharsets.UTF_8)
            val publicKey = ByteArray(ED25519_PUB_LEN).also { buf.get(it) }
            val signature = ByteArray(ED25519_SIG_LEN).also { buf.get(it) }

            val signedPortion = rawBytes.copyOfRange(0, rawBytes.size - ED25519_SIG_LEN)
            Ed25519Verify(publicKey).verify(signature, signedPortion)

            val ageSec = nowEpochSec - issuedAtSec
            if (ageSec > MAX_QR_AGE_SEC || ageSec < -MAX_CLOCK_SKEW_SEC) {
                return QrDecodeResult.Expired
            }

            QrDecodeResult.Valid(
                VerifiedQrPairingPayload(
                    deviceId = deviceId,
                    deviceName = deviceName,
                    platform = platform,
                    ed25519PublicKey = publicKey,
                    keyFingerprint = keyManager.computeFingerprint(publicKey),
                    issuedAtEpochSec = issuedAtSec
                )
            )
        } catch (_: GeneralSecurityException) {
            QrDecodeResult.Invalid("Chữ ký số Ed25519 trên mã QR không hợp lệ")
        } catch (e: Exception) {
            QrDecodeResult.Invalid("Lỗi phân tích mã QR: ${e.message}")
        }
    }

    private fun platformToByte(platform: String): Byte = when (platform.lowercase()) {
        "android" -> 1
        "windows" -> 2
        "macos" -> 3
        "linux" -> 4
        else -> 0
    }

    private fun byteToPlatform(code: Byte): String = when (code.toInt()) {
        1 -> "android"
        2 -> "windows"
        3 -> "macos"
        4 -> "linux"
        else -> "unknown"
    }
}
