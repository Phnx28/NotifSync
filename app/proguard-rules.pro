# === NotifSync model/entity classes (for Gson reflection + Room) ===
-keep class com.phnx28.notifsync.data.model.** { *; }
-keep class com.phnx28.notifsync.data.local.** { *; }

# === Gson ===
# Gson uses reflection to serialize/deserialize. R8 will strip field names
# and remove unused constructors, breaking JSON parsing silently.
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }
-keep class * extends com.google.gson.TypeAdapter
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer
# Prevent R8 from stripping fields used by Gson via reflection.
-keepclassmembers,allowobfuscation class * {
  @com.google.gson.annotations.SerializedName <fields>;
}

# === Java-WebSocket ===
# Keep public API surface; the library ships its own consumer rules but
# we keep the framing internals explicitly because we use the handshake
# hook for auth.
-keep class org.java_websocket.** { *; }
-dontwarn org.java_websocket.**

# === OkHttp / Okio ===
# v0.2.1 — removed the broad `-keep class okhttp3.** { *; }` rule
# (AUDIT.md L-12). OkHttp ships its own consumer ProGuard rules; let R8
# minify the parts we don't use.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# === Room ===
# Room's annotation processor handles most keeps, but DAO interfaces
# must survive for runtime reflection.
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Dao interface *

# === EncryptedSharedPreferences (androidx.security) ===
-keep class androidx.security.crypto.** { *; }
-keep class com.google.crypto.tink.** { *; }
