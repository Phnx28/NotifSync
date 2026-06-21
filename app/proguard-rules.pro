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
# Prevent R8 from stripping fields used by Gson via reflection
-keepclassmembers,allowobfuscation class * {
  @com.google.gson.annotations.SerializedName <fields>;
}

# === Java-WebSocket ===
-keep class org.java_websocket.** { *; }
-dontwarn org.java_websocket.**

# === OkHttp ===
# OkHttp ships its own rules, but explicitly keeping for safety
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# === Room ===
# Room's annotation processor handles most keeps, but DAO interfaces
# must survive for runtime reflection.
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Dao interface *
