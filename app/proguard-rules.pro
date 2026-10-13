# Media3 / ExoPlayer keeps a few types reachable only by reflection.
-dontwarn androidx.media3.**
-keep class androidx.media3.exoplayer.** { *; }

# OkHttp ships optional platform integrations that R8 flags but never loads on Android.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Our VpnService and MediaSessionService are instantiated by the framework by name.
-keep class com.oakcraft.lumen.blocker.LumenVpnService { *; }
-keep class com.oakcraft.lumen.player.PlaybackService { *; }

# Strip logging from release builds.
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
}
