package com.traderank.app

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import org.json.JSONObject

/** HTML app <-> ads bridge. window.NativeAds.* (showRewarded, showInterstitial, showBanner, hideBanner, openUrl, isVpnActive) */
class NativeAds(private val activity: Activity, private val web: WebView) {

    private val main = Handler(Looper.getMainLooper())
    private var busy = false

    private var admobReady = false

    /** JS se yahi function call hota hai (background thread par), isliye main thread par bhejte hain. */
    @JavascriptInterface
    fun showRewarded(reqId: String, network: String, paramsJson: String) {
        main.post {
            if (busy) { sendResult(reqId, "failed"); return@post }
            busy = true
            val p = try { JSONObject(paramsJson) } catch (e: Exception) { JSONObject() }
            try {
                if (network == "admob") showAdMob(reqId, p.optString("rewardedUnitId"))
                else showUnity(reqId, p) { r -> finish(reqId, r) }
            } catch (t: Throwable) {
                finish(reqId, "failed")
            }
        }
    }

    // Har request sirf ek baar result bhejti hai
    private fun resultOnce(reqId: String): (String) -> Unit {
        var done = false
        return { status ->
            if (!done) { done = true; finish(reqId, status) }
        }
    }

    private fun finish(reqId: String, status: String) {
        busy = false
        sendResult(reqId, status)
    }

    private fun sendResult(reqId: String, status: String) {
        val safeId = reqId.filter { it.isLetterOrDigit() }
        web.post { web.evaluateJavascript("window.__adResult('$safeId','$status')", null) }
    }

    // ------------------------------- AdMob -------------------------------
    private fun showAdMob(reqId: String, unitId: String) {
        val done = resultOnce(reqId)
        if (unitId.isBlank()) { done("failed"); return }
        if (!admobReady) { MobileAds.initialize(activity) {}; admobReady = true }

        RewardedAd.load(activity, unitId, AdRequest.Builder().build(), object : RewardedAdLoadCallback() {
            override fun onAdFailedToLoad(error: LoadAdError) { done("failed") }

            override fun onAdLoaded(ad: RewardedAd) {
                var earned = false
                ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                    override fun onAdDismissedFullScreenContent() { done(if (earned) "rewarded" else "closed") }
                    override fun onAdFailedToShowFullScreenContent(error: AdError) { done("failed") }
                }
                ad.show(activity) { earned = true }   // user ne ad poora dekha
            }
        })
    }

    // ------------------------------- Interstitial -------------------------------
    @JavascriptInterface
    fun showInterstitial(reqId: String, network: String, paramsJson: String) {
        main.post {
            if (busy) { sendResult(reqId, "failed"); return@post }
            busy = true
            val p = try { JSONObject(paramsJson) } catch (e: Exception) { JSONObject() }
            try {
                if (network == "admob") showAdMobInterstitial(reqId, p.optString("interstitialUnitId"))
                else showUnityInterstitial(reqId, p) { r -> finish(reqId, r) }
            } catch (t: Throwable) { finish(reqId, "failed") }
        }
    }

    private fun showAdMobInterstitial(reqId: String, unitId: String) {
        val done = resultOnce(reqId)
        if (unitId.isBlank()) { done("failed"); return }
        if (!admobReady) { MobileAds.initialize(activity) {}; admobReady = true }
        InterstitialAd.load(activity, unitId, AdRequest.Builder().build(), object : InterstitialAdLoadCallback() {
            override fun onAdFailedToLoad(error: LoadAdError) { done("failed") }
            override fun onAdLoaded(ad: InterstitialAd) {
                ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                    override fun onAdDismissedFullScreenContent() { done("shown") }
                    override fun onAdFailedToShowFullScreenContent(error: AdError) { done("failed") }
                }
                ad.show(activity)
            }
        })
    }


    private fun statusBarHeight(): Int {
        val id = activity.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) activity.resources.getDimensionPixelSize(id) else 0
    }

    // Unity LevelPlay (sirf "full" build mein hota hai; "lite" mein hamesha "failed")
    private fun showUnity(reqId: String, p: JSONObject, cb: (String) -> Unit) {
        val u = UnityProvider.get()
        if (u == null) { cb("failed"); return }
        val done = resultOnce(reqId)
        u.rewarded(activity, main, p) { r -> done(r) }
    }

    private fun showUnityInterstitial(reqId: String, p: JSONObject, cb: (String) -> Unit) {
        val u = UnityProvider.get()
        if (u == null) { cb("failed"); return }
        val done = resultOnce(reqId)
        u.interstitial(activity, p) { r -> done(r) }
    }

    // ------------------------------- Banner (screen ke top par) -------------------------------
    private var bannerView: View? = null

    @JavascriptInterface
    fun showBanner(network: String, paramsJson: String) {
        main.post {
            if (bannerView != null) return@post
            val p = try { JSONObject(paramsJson) } catch (e: Exception) { JSONObject() }
            try {
                val v: View? = if (network == "admob") {
                    val unit = p.optString("bannerUnitId")
                    if (unit.isBlank()) null else {
                        if (!admobReady) { MobileAds.initialize(activity) {}; admobReady = true }
                        AdView(activity).apply { setAdSize(AdSize.BANNER); adUnitId = unit; loadAd(AdRequest.Builder().build()) }
                    }
                } else {
                    UnityProvider.get()?.banner(activity, p)
                }
                if (v != null) {
                    val host = (activity as? MainActivity)?.bannerHost
                    if (host != null) {
                        // Status bar ke thik neeche, app ke upar (header ko nahi dhakta)
                        host.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))
                    } else {
                        activity.addContentView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP).apply { topMargin = statusBarHeight() })
                    }
                    bannerView = v
                }
            } catch (t: Throwable) { /* banner na aaye to app chalta rahe */ }
        }
    }

    // VPN chalu hai ya nahi (app mein seedha pakda jata hai). Manifest mein ACCESS_NETWORK_STATE permission chahiye.
    @JavascriptInterface
    fun isVpnActive(): Boolean {
        return try {
            val cm = activity.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val nc = cm.getNetworkCapabilities(cm.activeNetwork)
            nc != null && nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)
        } catch (_: Exception) { false }
    }

    // Support links (Instagram / Telegram / WhatsApp...) phone ke app mein kholne ke liye
    @JavascriptInterface
    fun openUrl(url: String) {
        main.post {
            try {
                val u = android.net.Uri.parse(url)
                if (u.scheme == "https" || u.scheme == "http" || u.scheme == "mailto") {
                    activity.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, u))
                }
            } catch (_: Exception) {}
        }
    }

    @JavascriptInterface
    fun hideBanner() {
        main.post {
            val v = bannerView ?: return@post
            (v.parent as? ViewGroup)?.removeView(v)
            bannerView = null
        }
    }
}
