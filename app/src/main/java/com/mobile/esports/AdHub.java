package com.mobile.esports;

import android.app.Activity;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

import com.applovin.mediation.MaxAd;
import com.applovin.mediation.MaxError;
import com.applovin.mediation.MaxReward;
import com.applovin.mediation.MaxRewardedAdListener;
import com.applovin.mediation.ads.MaxAdView;
import com.applovin.mediation.ads.MaxRewardedAd;
import com.applovin.sdk.AppLovinSdk;
import com.applovin.sdk.AppLovinSdkInitializationConfiguration;
import com.google.android.gms.ads.AdError;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdSize;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.FullScreenContentCallback;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.MobileAds;
import com.google.android.gms.ads.rewarded.RewardedAd;
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback;
import com.unity3d.ads.IUnityAdsInitializationListener;
import com.unity3d.ads.IUnityAdsLoadListener;
import com.unity3d.ads.IUnityAdsShowListener;
import com.unity3d.ads.UnityAds;
import com.unity3d.ads.UnityAdsShowOptions;
import com.unity3d.services.banners.BannerView;
import com.unity3d.services.banners.UnityBannerSize;

import java.util.ArrayList;
import java.util.List;

/**
 * Ad waterfall: Unity / AdMob / AppLovin MAX, controlled from the admin panel.
 * network = "unity" | "admob" | "applovin"  -> only that network
 * network = "mediation"                      -> try networks in admin "order"; first one that has a rewarded ad ready is shown.
 * All calls must happen on the UI thread.
 */
class AdHub {
    interface Result { void done(String id, boolean ok); }

    private final Activity act;
    private final FrameLayout holder;
    private final Result cb;

    // ---- admin config (pushed from HTML) ----
    private boolean configured = false, on = true, unityOn = true, consent = false;
    private String network = "mediation", order = "unity,admob,applovin";
    private String admobBanner = "", admobRewarded = "";
    private String unityGame = "", unityBanner = "", unityRewarded = "";
    private String alKey = "", alBanner = "", alRewarded = "";
    private boolean bannerWanted = false;
    private boolean unityFailed = false, admobFailed = false, alFailed = false;
    private long lastRetry = 0;

    // ---- per network state ----
    private boolean admobInit = false, admobLoading = false; private RewardedAd admobAd;
    private boolean unityInitStarted = false, unityReady = false, unityLoading = false, unityLoaded = false;
    private boolean alInitStarted = false, alReady = false; private MaxRewardedAd alAd; private String alAdId = "";

    // ---- banner ----
    private View bannerView; private String bannerNet = "";

    AdHub(Activity act, FrameLayout holder, Result cb) { this.act = act; this.holder = holder; this.cb = cb; }

    // ================= config =================
    void consentGranted() { consent = true; apply(); }

    void configure(String json) {
        try {
            org.json.JSONObject o = new org.json.JSONObject(json);
            network = o.optString("network", "mediation");
            order = o.optString("order", "unity,admob,applovin");
            on = o.optBoolean("on", true);
            unityOn = o.optBoolean("unityOn", true);
            admobBanner = adId(o.optString("admobBanner", ""));
            admobRewarded = adId(o.optString("admobRewarded", ""));
            unityGame = o.optString("unityGame", "").replaceAll("[^0-9]", "");
            unityBanner = plc(o.optString("unityBanner", ""));
            unityRewarded = plc(o.optString("unityRewarded", ""));
            alKey = o.optString("alKey", "").replaceAll("[^A-Za-z0-9_\\-]", "");
            alBanner = plc(o.optString("alBanner", ""));
            alRewarded = plc(o.optString("alRewarded", ""));
            configured = true;
        } catch (Exception e) { return; }
        apply();
    }

    void setBannerWanted(boolean w) { bannerWanted = w; updateBannerVisibility(); }

    private static String adId(String s) { return s.matches("^ca-app-pub-[0-9]{16}/[0-9]{10}$") ? s : ""; }
    private static String plc(String s) { return s.replaceAll("[^A-Za-z0-9_\\-]", ""); }

    private String admobBannerId() { return admobBanner.isEmpty() ? act.getString(R.string.admob_banner_id) : admobBanner; }
    private String admobRewardedId() { return admobRewarded.isEmpty() ? act.getString(R.string.admob_rewarded_id) : admobRewarded; }
    private String unityGameId() { return unityGame.isEmpty() ? act.getString(R.string.unity_game_id) : unityGame; }
    private String unityRewardedPlc() { return unityRewarded.isEmpty() ? "Rewarded_Android" : unityRewarded; }
    private String unityBannerPlc() { return unityBanner.isEmpty() ? "Banner_Android" : unityBanner; }

    private boolean usable(String n) {
        if (!configured || !on) return false;
        switch (n) {
            case "unity": return unityOn;
            case "admob": return consent;
            case "applovin": return !alKey.isEmpty() && !alRewarded.isEmpty();
            default: return false;
        }
    }

    /** Networks in the order they should be tried. */
    private List<String> active() {
        List<String> out = new ArrayList<>();
        if ("unity".equals(network) || "admob".equals(network) || "applovin".equals(network)) {
            if (usable(network)) out.add(network);
            return out;
        }
        List<String> names = new ArrayList<>();
        for (String x : order.split(",")) { x = x.trim(); if (("unity".equals(x) || "admob".equals(x) || "applovin".equals(x)) && !names.contains(x)) names.add(x); }
        for (String x : new String[]{"unity", "admob", "applovin"}) if (!names.contains(x)) names.add(x);
        for (String x : names) if (usable(x)) out.add(x);
        return out;
    }

    private boolean failed(String n) { return "unity".equals(n) ? unityFailed : "admob".equals(n) ? admobFailed : alFailed; }

    /**
     * Only the first network in the waterfall is initialised/loaded up front. The next one (e.g. AdMob) is
     * started lazily, only after the one before it failed (no fill / init error) - keeps AdMob ad requests low.
     */
    private void apply() {
        List<String> list = active();
        for (String n : list) {
            if (!failed(n)) {
                if ("unity".equals(n)) initUnity();
                else if ("admob".equals(n)) initAdmob();
                else initApplovin();
            }
            if (!failed(n)) break;      // this network is alive (loading or loaded): do not touch the next one yet
        }
        refreshBanner();
    }

    private void retryAll() {
        long now = System.currentTimeMillis();
        if (now - lastRetry < 20000) return;
        lastRetry = now;
        unityFailed = false; admobFailed = false; alFailed = false;
        apply();
    }

    // ================= rewarded =================
    void showRewarded(final String id) {
        for (String n : active()) {
            if ("unity".equals(n) && unityReady && unityLoaded) { showUnity(id); return; }
            if ("admob".equals(n) && admobAd != null) { showAdmob(id); return; }
            if ("applovin".equals(n) && alAd != null && alAd.isReady()) { showApplovin(id); return; }
        }
        retryAll();         // nothing ready: retry loads (max once per 20s), tell the app
        cb.done(id, false);
    }

    // ---------- Unity ----------
    private void initUnity() {
        if (unityInitStarted) { if (unityReady) { loadUnity(); } return; }
        unityInitStarted = true;
        UnityAds.initialize(act.getApplicationContext(), unityGameId(), act.getResources().getBoolean(R.bool.unity_test_mode), new IUnityAdsInitializationListener() {
            @Override public void onInitializationComplete() { act.runOnUiThread(() -> { unityReady = true; loadUnity(); refreshBanner(); }); }
            @Override public void onInitializationFailed(UnityAds.UnityAdsInitializationError error, String message) { unityInitStarted = false; act.runOnUiThread(() -> { unityFailed = true; apply(); }); }
        });
    }

    private void loadUnity() {
        if (!unityReady || unityLoading || unityLoaded) return;
        unityLoading = true;
        UnityAds.load(unityRewardedPlc(), new IUnityAdsLoadListener() {
            @Override public void onUnityAdsAdLoaded(String placementId) { unityLoading = false; unityLoaded = true; unityFailed = false; }
            @Override public void onUnityAdsFailedToLoad(String placementId, UnityAds.UnityAdsLoadError error, String message) { unityLoading = false; unityLoaded = false; act.runOnUiThread(() -> { unityFailed = true; apply(); }); }
        });
    }

    private void showUnity(final String id) {
        unityLoaded = false;
        UnityAds.show(act, unityRewardedPlc(), new UnityAdsShowOptions(), new IUnityAdsShowListener() {
            @Override public void onUnityAdsShowFailure(String placementId, UnityAds.UnityAdsShowError error, String message) { cb.done(id, false); loadUnity(); }
            @Override public void onUnityAdsShowStart(String placementId) {}
            @Override public void onUnityAdsShowClick(String placementId) {}
            @Override public void onUnityAdsShowComplete(String placementId, UnityAds.UnityAdsShowCompletionState state) {
                cb.done(id, state == UnityAds.UnityAdsShowCompletionState.COMPLETED); loadUnity();
            }
        });
    }

    // ---------- AdMob ----------
    private void initAdmob() {
        if (!admobInit) {
            admobInit = true;
            MobileAds.initialize(act, status -> act.runOnUiThread(() -> { loadAdmob(); refreshBanner(); }));
        } else loadAdmob();
    }

    private void loadAdmob() {
        if (admobLoading || admobAd != null) return;
        admobLoading = true;
        RewardedAd.load(act, admobRewardedId(), new AdRequest.Builder().build(), new RewardedAdLoadCallback() {
            @Override public void onAdLoaded(RewardedAd ad) { admobAd = ad; admobLoading = false; admobFailed = false; }
            @Override public void onAdFailedToLoad(LoadAdError error) { admobAd = null; admobLoading = false; act.runOnUiThread(() -> { admobFailed = true; apply(); }); }
        });
    }

    private void showAdmob(final String id) {
        final RewardedAd ad = admobAd; admobAd = null;
        final boolean[] earned = {false};
        ad.setFullScreenContentCallback(new FullScreenContentCallback() {
            @Override public void onAdDismissedFullScreenContent() { cb.done(id, earned[0]); loadAdmob(); }
            @Override public void onAdFailedToShowFullScreenContent(AdError error) { cb.done(id, false); loadAdmob(); }
        });
        ad.show(act, item -> earned[0] = true);
    }

    // ---------- AppLovin MAX ----------
    private void initApplovin() {
        if (alInitStarted) { if (alReady) loadApplovin(); return; }
        alInitStarted = true;
        try {
            AppLovinSdkInitializationConfiguration cfg = AppLovinSdkInitializationConfiguration.builder(alKey, act).setMediationProvider("max").build();
            AppLovinSdk.getInstance(act).initialize(cfg, c -> act.runOnUiThread(() -> { alReady = true; loadApplovin(); refreshBanner(); }));
        } catch (Exception e) { alInitStarted = false; }
    }

    private void loadApplovin() {
        if (!alReady || alRewarded.isEmpty()) return;
        if (alAd == null || !alRewarded.equals(alAdId)) {
            alAdId = alRewarded;
            alAd = MaxRewardedAd.getInstance(alRewarded, act);
        }
        if (alAd.isReady()) return;
        alAd.setListener(new MaxRewardedAdListener() {
            private String pending; private boolean earned;
            @Override public void onUserRewarded(MaxAd ad, MaxReward reward) { earned = true; }
            @Override public void onAdLoaded(MaxAd ad) { alFailed = false; }
            @Override public void onAdDisplayed(MaxAd ad) {}
            @Override public void onAdClicked(MaxAd ad) {}
            @Override public void onAdHidden(MaxAd ad) { alResult(earned); earned = false; alAd.loadAd(); }
            @Override public void onAdLoadFailed(String adUnitId, MaxError error) { act.runOnUiThread(() -> { alFailed = true; apply(); }); }
            @Override public void onAdDisplayFailed(MaxAd ad, MaxError error) { alResult(false); alAd.loadAd(); }
        });
        alAd.loadAd();
    }

    private String alPendingId;
    private void alResult(boolean ok) { String id = alPendingId; alPendingId = null; if (id != null) cb.done(id, ok); }

    private void showApplovin(final String id) { alPendingId = id; alAd.showAd(); }

    // ================= banner =================
    private String bannerNetwork() {
        for (String n : active()) {
            if ("unity".equals(n) && unityReady) return n;
            if ("admob".equals(n) && admobInit) return n;
            if ("applovin".equals(n) && alReady && !alBanner.isEmpty()) return n;
        }
        return "";
    }

    private void refreshBanner() {
        String want = bannerNetwork();
        if (want.equals(bannerNet) && (bannerView != null || want.isEmpty())) { updateBannerVisibility(); return; }
        removeBanner();
        bannerNet = want;
        if (want.isEmpty()) { updateBannerVisibility(); return; }
        try {
            if ("unity".equals(want)) {
                BannerView b = new BannerView(act, unityBannerPlc(), new UnityBannerSize(320, 50));
                holder.addView(b, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL));
                b.load(); bannerView = b;
            } else if ("admob".equals(want)) {
                DisplayMetrics dm = act.getResources().getDisplayMetrics();
                AdView b = new AdView(act);
                b.setAdUnitId(admobBannerId());
                b.setAdSize(AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(act, (int) (dm.widthPixels / dm.density)));
                holder.addView(b); b.loadAd(new AdRequest.Builder().build()); bannerView = b;
            } else {
                MaxAdView b = new MaxAdView(alBanner, act);
                int h = (int) (50 * act.getResources().getDisplayMetrics().density);
                holder.addView(b, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, h));
                b.loadAd(); bannerView = b;
            }
        } catch (Exception e) { bannerView = null; }
        updateBannerVisibility();
    }

    private void removeBanner() {
        try {
            if (bannerView instanceof BannerView) ((BannerView) bannerView).destroy();
            else if (bannerView instanceof AdView) ((AdView) bannerView).destroy();
            else if (bannerView instanceof MaxAdView) ((MaxAdView) bannerView).destroy();
        } catch (Exception ignored) {}
        holder.removeAllViews(); bannerView = null; bannerNet = "";
    }

    private void updateBannerVisibility() {
        holder.setVisibility(bannerWanted && bannerView != null ? View.VISIBLE : View.GONE);
    }

    void pause() { if (bannerView instanceof AdView) ((AdView) bannerView).pause(); }
    void resume() { if (bannerView instanceof AdView) ((AdView) bannerView).resume(); }
    void destroy() { removeBanner(); }
}
