package com.mobile.esports;

import android.app.Activity;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

import com.startapp.sdk.ads.banner.Banner;
import com.startapp.sdk.ads.banner.BannerListener;
import com.startapp.sdk.adsbase.Ad;
import com.startapp.sdk.adsbase.StartAppAd;
import com.startapp.sdk.adsbase.StartAppSDK;
import com.startapp.sdk.adsbase.VideoListener;
import com.startapp.sdk.adsbase.adlisteners.AdDisplayListener;
import com.startapp.sdk.adsbase.adlisteners.AdEventListener;
import com.unity3d.ads.IUnityAdsInitializationListener;
import com.unity3d.ads.IUnityAdsLoadListener;
import com.unity3d.ads.IUnityAdsShowListener;
import com.unity3d.ads.UnityAds;
import com.unity3d.ads.UnityAdsShowOptions;
import com.unity3d.services.banners.BannerErrorInfo;
import com.unity3d.services.banners.BannerView;
import com.unity3d.services.banners.UnityBannerSize;

/**
 * Two ad networks only:
 *   1. Unity Ads  - PRIMARY  (rewarded + banner)
 *   2. Start.io   - FALLBACK (rewarded + banner), used the moment Unity has no ad / fails.
 * Admin panel can switch each network ON/OFF. All calls must happen on the UI thread.
 */
class AdHub {
    interface Result { void done(String id, boolean ok); }

    private final Activity act;
    private final FrameLayout holder;
    private final Result cb;

    // ---- admin config (pushed from HTML) ----
    private boolean configured = false, on = true, unityOn = true, startOn = true, consent = false;
    private String unityGame = "", unityBanner = "", unityRewarded = "", startId = "";
    private boolean bannerWanted = false;
    private long lastRetry = 0;

    // ---- Unity state ----
    private boolean unityInitStarted = false, unityReady = false, unityLoading = false, unityLoaded = false;
    // ---- Start.io state ----
    private boolean startInit = false, startLoading = false, startLoaded = false;
    private StartAppAd startAd;
    private boolean startEarned = false;

    // ---- banner ----
    private View bannerView; private String bannerNet = "";
    private boolean unityBannerFailed = false;

    AdHub(Activity act, FrameLayout holder, Result cb) { this.act = act; this.holder = holder; this.cb = cb; }

    // ================= config =================
    void consentGranted() { consent = true; apply(); }

    void configure(String json) {
        try {
            org.json.JSONObject o = new org.json.JSONObject(json);
            on = o.optBoolean("on", true);
            unityOn = o.optBoolean("unityOn", true);
            startOn = o.optBoolean("startOn", true);
            unityGame = o.optString("unityGame", "").replaceAll("[^0-9]", "");
            unityBanner = plc(o.optString("unityBanner", ""));
            unityRewarded = plc(o.optString("unityRewarded", ""));
            startId = plc(o.optString("startId", ""));
            configured = true;
        } catch (Exception e) { return; }
        apply();
    }

    void setBannerWanted(boolean w) { bannerWanted = w; updateBannerVisibility(); }

    private static String plc(String s) { return s.replaceAll("[^A-Za-z0-9_\\-]", ""); }

    private String unityGameId() { return unityGame.isEmpty() ? act.getString(R.string.unity_game_id) : unityGame; }
    private String unityRewardedPlc() { return unityRewarded.isEmpty() ? "Rewarded_Android" : unityRewarded; }
    private String unityBannerPlc() { return unityBanner.isEmpty() ? "Banner_Android" : unityBanner; }
    private String startAppId() { return startId.isEmpty() ? act.getString(R.string.startio_app_id) : startId; }

    private boolean unityUsable() { return configured && on && unityOn; }
    private boolean startUsable() { return configured && on && startOn && consent && !startAppId().isEmpty(); }

    /** Loads both networks up front so the fallback is ready the moment Unity has no ad. */
    private void apply() {
        if (unityUsable()) initUnity();
        if (startUsable()) initStart();
        refreshBanner();
    }

    private void retryAll() {
        long now = System.currentTimeMillis();
        if (now - lastRetry < 15000) return;
        lastRetry = now;
        unityBannerFailed = false;
        if (unityUsable()) { if (unityReady) loadUnity(); else initUnity(); }
        if (startUsable()) { if (startInit) loadStart(); else initStart(); }
    }

    // ================= rewarded =================
    void showRewarded(final String id) {
        if (unityUsable() && unityReady && unityLoaded) { showUnity(id); return; }   // 1) Unity
        if (startUsable() && startLoaded && startAd != null) { showStart(id); return; } // 2) Start.io
        retryAll();
        cb.done(id, false);
    }

    // ---------- Unity ----------
    private void initUnity() {
        if (unityInitStarted) { if (unityReady) loadUnity(); return; }
        unityInitStarted = true;
        UnityAds.initialize(act.getApplicationContext(), unityGameId(), act.getResources().getBoolean(R.bool.unity_test_mode), new IUnityAdsInitializationListener() {
            @Override public void onInitializationComplete() { act.runOnUiThread(() -> { unityReady = true; loadUnity(); refreshBanner(); }); }
            @Override public void onInitializationFailed(UnityAds.UnityAdsInitializationError error, String message) { act.runOnUiThread(() -> { unityInitStarted = false; unityBannerFailed = true; refreshBanner(); }); }
        });
    }

    private void loadUnity() {
        if (!unityReady || unityLoading || unityLoaded) return;
        unityLoading = true;
        UnityAds.load(unityRewardedPlc(), new IUnityAdsLoadListener() {
            @Override public void onUnityAdsAdLoaded(String placementId) { unityLoading = false; unityLoaded = true; }
            @Override public void onUnityAdsFailedToLoad(String placementId, UnityAds.UnityAdsLoadError error, String message) { unityLoading = false; unityLoaded = false; }
        });
    }

    private void showUnity(final String id) {
        unityLoaded = false;
        UnityAds.show(act, unityRewardedPlc(), new UnityAdsShowOptions(), new IUnityAdsShowListener() {
            @Override public void onUnityAdsShowFailure(String placementId, UnityAds.UnityAdsShowError error, String message) {
                act.runOnUiThread(() -> {
                    loadUnity();
                    // Unity could not show -> straight to Start.io
                    if (startUsable() && startLoaded && startAd != null) showStart(id); else cb.done(id, false);
                });
            }
            @Override public void onUnityAdsShowStart(String placementId) {}
            @Override public void onUnityAdsShowClick(String placementId) {}
            @Override public void onUnityAdsShowComplete(String placementId, UnityAds.UnityAdsShowCompletionState state) {
                act.runOnUiThread(() -> { cb.done(id, state == UnityAds.UnityAdsShowCompletionState.COMPLETED); loadUnity(); });
            }
        });
    }

    // ---------- Start.io ----------
    private void initStart() {
        if (!startInit) {
            startInit = true;
            try {
                StartAppSDK.init(act, startAppId(), false);      // false = no "return ads"
                StartAppAd.disableSplash();
                StartAppSDK.setTestAdsEnabled(false);
            } catch (Throwable t) { startInit = false; return; }
        }
        loadStart();
    }

    private void loadStart() {
        if (startLoading || startLoaded) return;
        startLoading = true;
        try {
            final StartAppAd ad = new StartAppAd(act);
            ad.setVideoListener(new VideoListener() {
                @Override public void onVideoCompleted() { startEarned = true; }
            });
            ad.loadAd(StartAppAd.AdMode.REWARDED_VIDEO, new AdEventListener() {
                @Override public void onReceiveAd(Ad a) { act.runOnUiThread(() -> { startAd = ad; startLoaded = true; startLoading = false; }); }
                @Override public void onFailedToReceiveAd(Ad a) { act.runOnUiThread(() -> { startAd = null; startLoaded = false; startLoading = false; }); }
            });
        } catch (Throwable t) { startLoading = false; }
    }

    private void showStart(final String id) {
        final StartAppAd ad = startAd; startAd = null; startLoaded = false; startEarned = false;
        final boolean[] fin = {false};
        try {
            ad.showAd(new AdDisplayListener() {
                @Override public void adHidden(Ad a) { if (fin[0]) return; fin[0] = true; act.runOnUiThread(() -> { cb.done(id, startEarned); startEarned = false; loadStart(); }); }
                @Override public void adDisplayed(Ad a) {}
                @Override public void adClicked(Ad a) {}
                @Override public void adNotDisplayed(Ad a) { if (fin[0]) return; fin[0] = true; act.runOnUiThread(() -> { cb.done(id, false); loadStart(); }); }
            });
        } catch (Throwable t) { cb.done(id, false); loadStart(); }
    }

    // ================= banner =================
    /** Unity banner first; if it fails (or Unity is OFF) the Start.io banner is used. */
    private String bannerNetwork() {
        if (unityUsable() && unityReady && !unityBannerFailed) return "unity";
        if (startUsable() && startInit) return "startio";
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
                b.setListener(new BannerView.IListener() {
                    @Override public void onBannerLoaded(BannerView v) {}
                    @Override public void onBannerClick(BannerView v) {}
                    @Override public void onBannerFailedToLoad(BannerView v, BannerErrorInfo e) { act.runOnUiThread(() -> { unityBannerFailed = true; refreshBanner(); }); }
                    @Override public void onBannerLeftApplication(BannerView v) {}
                });
                holder.addView(b, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL));
                b.load(); bannerView = b;
            } else {
                Banner b = new Banner(act, new BannerListener() {
                    @Override public void onReceiveAd(View v) {}
                    @Override public void onFailedToReceiveAd(View v) {}
                    @Override public void onImpression(View v) {}
                    @Override public void onClick(View v) {}
                });
                holder.addView(b, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL));
                bannerView = b;
            }
        } catch (Throwable e) { bannerView = null; if ("unity".equals(want)) { unityBannerFailed = true; } }
        updateBannerVisibility();
    }

    private void removeBanner() {
        try { if (bannerView instanceof BannerView) ((BannerView) bannerView).destroy(); } catch (Exception ignored) {}
        holder.removeAllViews(); bannerView = null; bannerNet = "";
    }

    private void updateBannerVisibility() {
        holder.setVisibility(bannerWanted && bannerView != null ? View.VISIBLE : View.GONE);
    }

    void pause() {}
    void resume() {}
    void destroy() { removeBanner(); }
}
