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

# Unity LevelPlay + Unity Ads adapter
-keepattributes SourceFile,LineNumberTable
-keep class com.ironsource.adapters.** { *; }
-keep public class com.ironsource.**
-keepclassmembers class com.ironsource.** { public *; }
-dontwarn com.ironsource.**
-dontwarn com.ironsource.adapters.**
-keep class com.unity3d.mediation.** { *; }
-keep class com.unity3d.ads.** { *; }
-keep class com.unity3d.services.** { *; }
-dontwarn com.unity3d.services.**
-dontwarn com.iab.omid.**
-keep class com.iab.omid.** { *; }
-keep public interface com.ironsource.mediationsdk.sdk** { *; }
-keep class com.google.android.gms.appset.** { *; }
-keep class com.google.android.gms.tasks.** { *; }
