package com.traderank.app

import android.app.Activity
import android.os.Handler
import android.view.View
import com.unity3d.mediation.LevelPlay
import com.unity3d.mediation.LevelPlayAdError
import com.unity3d.mediation.LevelPlayAdInfo
import com.unity3d.mediation.LevelPlayConfiguration
import com.unity3d.mediation.LevelPlayInitError
import com.unity3d.mediation.LevelPlayInitListener
import com.unity3d.mediation.LevelPlayInitRequest
import com.unity3d.mediation.LevelPlayAdSize
import com.unity3d.mediation.banner.LevelPlayBannerAdView
import com.unity3d.mediation.interstitial.LevelPlayInterstitialAd
import com.unity3d.mediation.interstitial.LevelPlayInterstitialAdListener
import com.unity3d.mediation.rewarded.LevelPlayReward
import com.unity3d.mediation.rewarded.LevelPlayRewardedAd
import com.unity3d.mediation.rewarded.LevelPlayRewardedAdListener
import org.json.JSONObject

object UnityProvider {
    private val impl = LevelPlayImpl()
    fun get(): UnityAds? = impl
}

private class LevelPlayImpl : UnityAds {
    private var ready = false
    private var key: String? = null

    private fun withInit(activity: Activity, appKey: String, ok: () -> Unit, fail: () -> Unit) {
        if (ready && key == appKey) { ok(); return }
        val request = LevelPlayInitRequest.Builder(appKey).build()
        LevelPlay.init(activity.applicationContext, request, object : LevelPlayInitListener {
            override fun onInitFailed(error: LevelPlayInitError) { fail() }
            override fun onInitSuccess(configuration: LevelPlayConfiguration) { ready = true; key = appKey; ok() }
        })
    }

    override fun rewarded(activity: Activity, main: Handler, p: JSONObject, done: (String) -> Unit) {
        val appKey = p.optString("appKey"); val unit = p.optString("adUnitId")
        if (appKey.isBlank() || unit.isBlank()) { done("failed"); return }
        withInit(activity, appKey, {
            var rewarded = false
            val ad = LevelPlayRewardedAd(unit)
            ad.setListener(object : LevelPlayRewardedAdListener {
                override fun onAdLoaded(adInfo: LevelPlayAdInfo) { if (ad.isAdReady()) ad.showAd(activity) else done("failed") }
                override fun onAdLoadFailed(error: LevelPlayAdError) { done("failed") }
                override fun onAdDisplayed(adInfo: LevelPlayAdInfo) {}
                override fun onAdRewarded(reward: LevelPlayReward, adInfo: LevelPlayAdInfo) { rewarded = true }
                override fun onAdDisplayFailed(error: LevelPlayAdError, adInfo: LevelPlayAdInfo) { done("failed") }
                override fun onAdClicked(adInfo: LevelPlayAdInfo) {}
                override fun onAdInfoChanged(adInfo: LevelPlayAdInfo) {}
                override fun onAdClosed(adInfo: LevelPlayAdInfo) {
                    main.postDelayed({ done(if (rewarded) "rewarded" else "closed") }, 800)
                }
            })
            ad.loadAd()
        }, { done("failed") })
    }

    override fun interstitial(activity: Activity, p: JSONObject, done: (String) -> Unit) {
        val appKey = p.optString("appKey"); val unit = p.optString("interstitialAdUnitId")
        if (appKey.isBlank() || unit.isBlank()) { done("failed"); return }
        withInit(activity, appKey, {
            val ad = LevelPlayInterstitialAd(unit)
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
        }, { done("failed") })
    }

    override fun banner(activity: Activity, p: JSONObject): View? {
        val appKey = p.optString("appKey"); val unit = p.optString("bannerAdUnitId")
        if (appKey.isBlank() || unit.isBlank()) return null
        withInit(activity, appKey, {}, {})
        val cfg = LevelPlayBannerAdView.Config.Builder().setAdSize(LevelPlayAdSize.BANNER).build()
        return LevelPlayBannerAdView(activity, unit, cfg).apply { loadAd() }
    }
}
