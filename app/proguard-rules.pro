# WebView JS bridges must keep their annotated methods (called by name from JavaScript)
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.mobile.esports.MainActivity$AdsBridge { *; }
-keep class com.mobile.esports.MainActivity$AdminBridge { *; }
-keep class com.mobile.esports.MainActivity$DeviceBridge { *; }
-keepattributes JavascriptInterface
# strip debug logging in release
-assumenosideeffects class android.util.Log { public static *** d(...); public static *** v(...); public static *** i(...); }
