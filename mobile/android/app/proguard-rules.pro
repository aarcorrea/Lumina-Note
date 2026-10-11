# Keep JavaScriptInterface methods for WebView bridge
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

-keep class com.luminanote.mobile.bridge.** { *; }
-keepattributes JavascriptInterface
-keepclassmembers class com.luminanote.mobile.MainActivity {
    public *;
}
