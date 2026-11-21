package com.traderank.app

/*
 * ===================== TradeRank: NativeAds.kt (WebView <-> Rewarded Ads bridge) =====================
 * App name:      TradeRank        (res/values/strings.xml:  <string name="app_name">TradeRank</string>)
 * Package name:  com.traderank.app   (build.gradle:  applicationId "com.traderank.app")
 *   !! Play Console me package name ek baar upload ke baad KABHI badal nahi sakta, aur poori Play Store me unique hona chahiye.
 *      Agar "com.traderank.app" pehle se kisi ka hua to com.traderank.game ya com.traderank.trading try karo.
 *      (Naya app banate waqt Play Console khud bata deta hai ki naam available hai ya nahi.)
 *
 * HTML app yahan se ad maangta hai:   NativeAds.showRewarded(requestId, "unity" | "admob", paramsJson)
 * Yeh class ad dikhati hai aur result wapas bhejti hai:  window.__adResult(requestId, status)
 *     status = "rewarded" (ad poora dekha) | "closed" (beech mein band kiya) | "failed" (ad nahi aaya / error)
 * Kaun sa network chalega, uska ID, daily limit, reward: sab Admin Panel se aata hai (HTML app bhejta hai).
 *
 * ---------------- 1) build.gradle (app) ----------------
 *   dependencies {
 *       implementation 'com.unity3d.ads-mediation:mediation-sdk:9.8.0'          // Unity LevelPlay (docs par jo latest ho wo lo)
 *       implementation 'com.google.android.gms:play-services-ads:<LATEST>'       // AdMob (AdMob docs se latest version)
 *       implementation 'com.google.android.gms:play-services-appset:16.0.0'            // LevelPlay docs ke hisaab se
 *       implementation 'com.google.android.gms:play-services-ads-identifier:18.1.0'
 *       implementation 'com.google.android.gms:play-services-basement:18.1.0'
 *   }
 *   repositories (settings.gradle me dependencyResolutionManagement): mavenCentral() aur google()
 *
 * ---------------- 2) AndroidManifest.xml ----------------
 *   <uses-permission android:name="android.permission.INTERNET" />
 *   <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
 *   <uses-permission android:name="com.google.android.gms.permission.AD_ID" />
 *   <application ...>
 *       <!-- AdMob App ID ZAROORI hai (dono SDK ek app me hon to bhi). Test ke liye Google ka sample ID: -->
 *       <meta-data android:name="com.google.android.gms.ads.APPLICATION_ID"
 *                  android:value="ca-app-pub-3940256099942544~3347511713"/>
 *   </application>
 *
 * ---------------- 3) MainActivity me WebView setup ----------------
 *   val web = findViewById<WebView>(R.id.web)
 *   web.settings.javaScriptEnabled = true
 *   web.settings.domStorageEnabled = true          // localStorage ke liye ZAROORI (offline data, login session)
 *   web.addJavascriptInterface(NativeAds(this, web), "NativeAds")
 *   web.webViewClient = TradeRankWebClient()          // internet na ho to "Internet nahi hai" screen (neeche class hai)
 *   web.loadUrl("https://TUMHARA-HOSTED-APP-URL/")   // sirf apna hi page load karo (bridge ka security)
 *   // Behtar: HTML ko https par host karo (Firebase Hosting). file:///android_asset se Firebase login me dikkat aa sakti hai.
 *
 * ---------------- 4) Play Console ke liye yaad rakho ----------------
 *   - Privacy Policy URL + Data safety form (ads + login/email)
 *   - EU/UK users ke liye Google UMP consent (AdMob docs: "User Messaging Platform")
 *   - LevelPlay dashboard me AdMob ko mediation network ke roop me bhi jod sakte ho (optional)
 *
 * HTML se aur do calls aate hain (admin panel ke IDs ke saath):
 *     NativeAds.showInterstitial(requestId, "unity"|"admob", paramsJson)  -> window.__adResult(id, "shown"|"failed")
 *     NativeAds.showBanner("unity"|"admob", paramsJson) / NativeAds.hideBanner()   (banner screen ke top par)
 *   Leaderboard par interstitial har 12 minute mein ek baar dikhane ka hisaab HTML khud rakhta hai (admin panel mein gap badal sakte ho).
 *
 * NOTE: Unity LevelPlay ke import paths mere paas docs se verify nahi hue (banner/interstitial class paths bhi). Agar koi import laal ho,
 *       Android Studio me Alt+Enter dabao aur auto-import karo. (Class names docs wale hi hain.)
 */

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
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
import com.unity3d.mediation.LevelPlay
import com.unity3d.mediation.LevelPlayAdError
import com.unity3d.mediation.LevelPlayAdInfo
import com.unity3d.mediation.LevelPlayConfiguration
import com.unity3d.mediation.LevelPlayInitError
import com.unity3d.mediation.LevelPlayInitListener
import com.unity3d.mediation.LevelPlayInitRequest
import com.unity3d.mediation.banner.LevelPlayAdSize
import com.unity3d.mediation.banner.LevelPlayBannerAdView
import com.unity3d.mediation.interstitial.LevelPlayInterstitialAd
import com.unity3d.mediation.interstitial.LevelPlayInterstitialAdListener
import com.unity3d.mediation.rewarded.LevelPlayReward
import com.unity3d.mediation.rewarded.LevelPlayRewardedAd
import com.unity3d.mediation.rewarded.LevelPlayRewardedAdListener
import org.json.JSONObject

/**
 * TradeRank internet ke bina nahi chalta. Page hosted URL se aata hai, isliye net na hone par WebView ka
 * default error page aa jata hai. Uski jagah yeh simple "Internet nahi hai" screen dikhata hai,
 * aur "Dobara try karo" dabane par wahi URL phir se load hota hai.
 * (Net aane par agar app khul gaya to andar ka HTML bhi khud net check karta rehta hai.)
 */
class TradeRankWebClient : WebViewClient() {
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

class NativeAds(private val activity: Activity, private val web: WebView) {

    private val main = Handler(Looper.getMainLooper())
    private var busy = false

    private var admobReady = false
    private var levelPlayReady = false
    private var levelPlayKey: String? = null

    /** JS se yahi function call hota hai (background thread par), isliye main thread par bhejte hain. */
    @JavascriptInterface
    fun showRewarded(reqId: String, network: String, paramsJson: String) {
        main.post {
            if (busy) { sendResult(reqId, "failed"); return@post }
            busy = true
            val p = try { JSONObject(paramsJson) } catch (e: Exception) { JSONObject() }
            try {
                if (network == "admob") showAdMob(reqId, p.optString("rewardedUnitId"))
                else showLevelPlay(reqId, p.optString("appKey"), p.optString("adUnitId"))
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

    // ---------------------------- Unity LevelPlay ----------------------------
    private fun showLevelPlay(reqId: String, appKey: String, adUnitId: String) {
        val done = resultOnce(reqId)
        if (appKey.isBlank() || adUnitId.isBlank()) { done("failed"); return }

        if (levelPlayReady && levelPlayKey == appKey) {
            loadAndShowLevelPlay(adUnitId, done)
            return
        }
        val request = LevelPlayInitRequest.Builder(appKey).build()
        LevelPlay.init(activity.applicationContext, request, object : LevelPlayInitListener {
            override fun onInitFailed(error: LevelPlayInitError) { done("failed") }
            override fun onInitSuccess(configuration: LevelPlayConfiguration) {
                levelPlayReady = true
                levelPlayKey = appKey
                loadAndShowLevelPlay(adUnitId, done)
            }
        })
    }

    private fun loadAndShowLevelPlay(adUnitId: String, done: (String) -> Unit) {
        var rewarded = false
        val ad = LevelPlayRewardedAd(adUnitId)
        ad.setListener(object : LevelPlayRewardedAdListener {
            override fun onAdLoaded(adInfo: LevelPlayAdInfo) {
                if (ad.isAdReady()) ad.showAd(activity) else done("failed")
            }
            override fun onAdLoadFailed(error: LevelPlayAdError) { done("failed") }
            override fun onAdDisplayed(adInfo: LevelPlayAdInfo) {}
            override fun onAdRewarded(reward: LevelPlayReward, adInfo: LevelPlayAdInfo) { rewarded = true }
            override fun onAdDisplayFailed(error: LevelPlayAdError, adInfo: LevelPlayAdInfo) { done("failed") }
            override fun onAdClicked(adInfo: LevelPlayAdInfo) {}
            override fun onAdInfoChanged(adInfo: LevelPlayAdInfo) {}
            override fun onAdClosed(adInfo: LevelPlayAdInfo) {
                // Docs: onAdRewarded aur onAdClosed async hain (order pakka nahi), isliye thoda ruk kar result bhejo
                main.postDelayed({ done(if (rewarded) "rewarded" else "closed") }, 800)
            }
        })
        ad.loadAd()
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
                else showLevelPlayInterstitial(reqId, p.optString("appKey"), p.optString("interstitialAdUnitId"))
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

    private fun showLevelPlayInterstitial(reqId: String, appKey: String, adUnitId: String) {
        val done = resultOnce(reqId)
        if (appKey.isBlank() || adUnitId.isBlank()) { done("failed"); return }
        val go = {
            val ad = LevelPlayInterstitialAd(adUnitId)
            ad.setListener(object : LevelPlayInterstitialAdListener {
                override fun onAdLoaded(adInfo: LevelPlayAdInfo) { if (ad.isAdReady()) ad.showAd(activity) else done("failed") }
                override fun onAdLoadFailed(error: LevelPlayAdError) { done("failed") }
                override fun onAdDisplayed(adInfo: LevelPlayAdInfo) {}
                override fun onAdDisplayFailed(error: LevelPlayAdError, adInfo: LevelPlayAdInfo) { done("failed") }
                override fun onAdClicked(adInfo: LevelPlayAdInfo) {}
                override fun onAdInfoChanged(adInfo: LevelPlayAdInfo) {}
                override fun onAdClosed(adInfo: LevelPlayAdInfo) { done("shown") }
            })
            ad.loadAd()
        }
        if (levelPlayReady && levelPlayKey == appKey) { go(); return }
        val request = LevelPlayInitRequest.Builder(appKey).build()
        LevelPlay.init(activity.applicationContext, request, object : LevelPlayInitListener {
            override fun onInitFailed(error: LevelPlayInitError) { done("failed") }
            override fun onInitSuccess(configuration: LevelPlayConfiguration) {
                levelPlayReady = true; levelPlayKey = appKey; go()
            }
        })
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
                    val key = p.optString("appKey"); val unit = p.optString("bannerAdUnitId")
                    if (key.isBlank() || unit.isBlank()) null else {
                        // LevelPlay init pehle ho chuki honi chahiye. Na hui ho to yahan karo.
                        if (!(levelPlayReady && levelPlayKey == key)) {
                            val request = LevelPlayInitRequest.Builder(key).build()
                            LevelPlay.init(activity.applicationContext, request, object : LevelPlayInitListener {
                                override fun onInitFailed(error: LevelPlayInitError) {}
                                override fun onInitSuccess(configuration: LevelPlayConfiguration) { levelPlayReady = true; levelPlayKey = key }
                            })
                        }
                        LevelPlayBannerAdView(activity, unit).apply { setAdSize(LevelPlayAdSize.BANNER); loadAd() }
                    }
                }
                if (v != null) {
                    activity.addContentView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
                    bannerView = v
                }
            } catch (t: Throwable) { /* banner na aaye to app chalta rahe */ }
        }
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
