# Aura ProGuard Rules

# Keep Room entities
-keep class com.silicongames.aura.data.** { *; }

# Keep OkHttp
-dontwarn okhttp3.**
-keep class okhttp3.** { *; }

# Keep JSON parsing
-keep class org.json.** { *; }

# Keep WorkManager
-keep class androidx.work.** { *; }
