# Keep WebView JavaScript interface
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep the main activity
-keep class com.silicongames.arcade.** { *; }
