package com.traderank.app

import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Internet na ho to WebView ka default error page aata hai. Uski jagah "Internet nahi hai" screen dikhate hain.
 */
class TradeRankWebClient(private val activity: android.app.Activity) : WebViewClient() {
    private val appHost = "k3917325-png.github.io"
    // App ke apne page ke alawa koi link (Instagram, Telegram...) bahar ke app mein khulta hai
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val u = request.url
        val host = u.host ?: ""
        if (u.scheme == "about" || u.scheme == "data" || u.scheme == "blob") return false
        val ours = host == appHost || host.endsWith(".firebaseapp.com") || host.endsWith(".googleapis.com") || host.endsWith(".google.com") || host.endsWith(".gstatic.com")
        if (ours) return false
        return try { activity.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, u)); true } catch (_: Exception) { false }
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        if (!request.isForMainFrame) return
        val url = request.url.toString().replace("'", "").replace("\"", "").replace("<", "").replace(">", "")
        val html = """
            <html><head><meta name="viewport" content="width=device-width, initial-scale=1"></head>
            <body style="margin:0;background:#000;color:#fff;font-family:sans-serif;display:flex;align-items:center;
                         justify-content:center;height:100vh;text-align:center">
              <div style="padding:24px;max-width:340px">
                <div style="font-size:56px">📡</div>
                <h2>Internet nahi hai</h2>
                <p style="color:#aaa">TradeRank chalane ke liye internet zaroori hai. WiFi ya mobile data on karke dobara try karo.</p>
                <button onclick="location.href='$url'"
                  style="width:100%;padding:14px;border:0;border-radius:12px;background:#2962ff;color:#fff;font-size:16px;font-weight:700">
                  🔄 Dobara try karo</button>
              </div>
            </body></html>
        """.trimIndent()
        view.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
    }
}
