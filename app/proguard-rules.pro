# AMKT Injector — ProGuard / R8 Rules
# Note: Release builds can set isMinifyEnabled = true in app/build.gradle.kts
# when packaging the final signed APK with Android NDK enabled.

# Keep JNI entry point for libamkt_crypto.so
-keep class com.example.config.NativeKeyBridge {
    native <methods>;
}

# Keep Moshi JSON config, server list & membership DTOs and KSP-generated adapters
-keep @com.squareup.moshi.JsonClass class * { *; }
-keep class com.example.config.**JsonAdapter { *; }
-keep class com.example.data.**JsonAdapter { *; }
-keep class com.example.membership.**JsonAdapter { *; }

# Keep Room database implementations
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }

# Keep JSch (com.github.mwiede:jsch) reflection-instantiated ciphers, KEX, and MAC classes
-keep class com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**

# Keep hev-socks5-tunnel JNI bindings (libhev-socks5-tunnel.so)
-keep class org.amnezia.awg.hevtunnel.** { *; }

# Strip verbose debug logs in minified release builds
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
