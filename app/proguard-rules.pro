# Keep WebView JS interface methods (none used currently, kept for safety)
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
