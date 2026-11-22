package com.traderank.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.LinearLayout

class MainActivity : Activity() {

    private lateinit var web: WebView
    /** Banner ad yahan lagta hai (WebView ke upar alag jagah), isliye app ke header ko dhakta nahi. */
    lateinit var bannerHost: FrameLayout
    private var fileCb: ValueCallback<Array<Uri>>? = null

    companion object {
        // Hosted TradeRank page (GitHub Pages). Update hone par app dobara build karne ki zaroorat nahi.
        const val APP_URL = "https://k3917325-png.github.io/Esports-mobile/traderank-app/"
        private const val REQ_FILE = 4101
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.parseColor("#05030F"))
        bannerHost = FrameLayout(this)
        root.addView(bannerHost, LinearLayout.LayoutParams(-1, -2))
        web = WebView(this)
        root.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        // Android 15 edge-to-edge: status bar / navigation bar ke neeche content na jaye
        root.setOnApplyWindowInsetsListener { v, ins ->
            @Suppress("DEPRECATION")
            v.setPadding(ins.systemWindowInsetLeft, ins.systemWindowInsetTop, ins.systemWindowInsetRight, ins.systemWindowInsetBottom)
            ins
        }

        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true          // localStorage (login session, offline data)
            allowFileAccess = false
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = true
            setSupportZoom(false)
        }
        web.overScrollMode = View.OVER_SCROLL_NEVER
        web.addJavascriptInterface(NativeAds(this, web), "NativeAds")
        web.webViewClient = TradeRankWebClient(this)
        web.webChromeClient = object : WebChromeClient() {
            // Profile photo upload (gallery) ke liye
            override fun onShowFileChooser(w: WebView, cb: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                fileCb?.onReceiveValue(null)
                fileCb = cb
                return try {
                    startActivityForResult(params.createIntent(), REQ_FILE); true
                } catch (e: Exception) { fileCb = null; false }
            }
        }
        if (savedInstanceState != null) web.restoreState(savedInstanceState) else web.loadUrl(APP_URL)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_FILE) {
            val r = if (resultCode == RESULT_OK) WebChromeClient.FileChooserParams.parseResult(resultCode, data) else null
            fileCb?.onReceiveValue(r); fileCb = null
        } else super.onActivityResult(requestCode, resultCode, data)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // App ke andar apna back (chart se home etc.) JS handle karta hai
        web.evaluateJavascript("(window.__nativeBack?window.__nativeBack():false)") { r ->
            if (r != "true") {
                if (web.canGoBack()) web.goBack() else @Suppress("DEPRECATION") super.onBackPressed()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); web.saveState(outState) }
    override fun onResume() { super.onResume(); web.onResume() }
    override fun onPause() { web.onPause(); super.onPause() }
    override fun onDestroy() { web.destroy(); super.onDestroy() }
}
