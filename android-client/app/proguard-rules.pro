# Giữ nguyên các lớp JNI của WebRTC
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# Giữ nguyên SQLCipher & Room Database
-keep class net.zetetic.** { *; }
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *

# Giữ nguyên Google Tink (Ed25519 / X25519 / AES-GCM) & ML Kit
-keep class com.google.crypto.tink.** { *; }
-keep class com.google.mlkit.vision.barcode.** { *; }
