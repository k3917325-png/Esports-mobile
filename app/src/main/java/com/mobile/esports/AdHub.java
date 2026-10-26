package com.mobile.esports;

import android.app.Activity;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

import com.google.android.gms.ads.AdError;
import com.google.android.gms.ads.AdListener;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdSize;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.FullScreenContentCallback;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.MobileAds;
import com.google.android.gms.ads.rewarded.RewardedAd;
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback;
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
 * Three ad networks, strict priority:
 *   1. Unity Ads   - PRIMARY        (rewarded + banner)
 *   2. Start.io    - FIRST BACKUP   (used the moment Unity has no ad / fails)
 *   3. Google AdMob- FINAL BACKUP   (used only if Unity AND Start.io both have nothing)
 * Admin panel can switch each network ON/OFF. All calls must happen on the UI thread.
 */
class AdHub {
    interface Result { void done(String id, boolean ok); }

    private final Activity act;
    private final FrameLayout holder;
    private final Result cb;

    // ---- admin config (pushed from HTML) ----
    private boolean configured = false, on = true, unityOn = true, startOn = true, admobOn = true, consent = false;
    private String unityGame = "", unityBanner = "", unityRewarded = "", startId = "", admobBanner = "", admobRewarded = "";
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
    private boolean unityBannerFailed = false, startBannerFailed = false;
    // ---- AdMob state ----
    private boolean admobInit = false, admobLoading = false, admobBannerFailed = false; private RewardedAd admobAd;

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
            admobOn = o.optBoolean("admobOn", true);
            admobBanner = adId(o.optString("admobBanner", ""));
            admobRewarded = adId(o.optString("admobRewarded", ""));
            configured = true;
        } catch (Exception e) { return; }
        apply();
    }

    void setBannerWanted(boolean w) { bannerWanted = w; updateBannerVisibility(); }

    // Google's sample/TEST ad units (ca-app-pub-3940256099942544) are ignored: the build's real IDs are used instead.
    private static String adId(String s) { return s.matches("^ca-app-pub-[0-9]{16}/[0-9]{10}$") && !s.startsWith("ca-app-pub-3940256099942544") ? s : ""; }
    private String admobBannerId() { return admobBanner.isEmpty() ? act.getString(R.string.admob_banner_id) : admobBanner; }
    private String admobRewardedId() { return admobRewarded.isEmpty() ? act.getString(R.string.admob_rewarded_id) : admobRewarded; }
    private static String plc(String s) { return s.replaceAll("[^A-Za-z0-9_\\-]", ""); }

    private String unityGameId() { return unityGame.isEmpty() ? act.getString(R.string.unity_game_id) : unityGame; }
    private String unityRewardedPlc() { return unityRewarded.isEmpty() ? "Rewarded_Android" : unityRewarded; }
    private String unityBannerPlc() { return unityBanner.isEmpty() ? "Banner_Android" : unityBanner; }
    private String startAppId() { return startId.isEmpty() ? act.getString(R.string.startio_app_id) : startId; }

    private boolean unityUsable() { return configured && on && unityOn; }
    private boolean admobUsable() { return configured && on && admobOn && consent; }
    private boolean startUsable() { return configured && on && startOn && consent && !startAppId().isEmpty(); }

    /** Loads both networks up front so the fallback is ready the moment Unity has no ad. */
    private void apply() {
        if (unityUsable()) initUnity();
        if (startUsable()) initStart();
        if (admobUsable()) initAdmob();
        refreshBanner();
    }

    private void retryAll() {
        long now = System.currentTimeMillis();
        if (now - lastRetry < 15000) return;
        lastRetry = now;
        unityBannerFailed = false; startBannerFailed = false; admobBannerFailed = false;
        if (admobUsable()) { if (admobInit) loadAdmob(); else initAdmob(); }
        if (unityUsable()) { if (unityReady) loadUnity(); else initUnity(); }
        if (startUsable()) { if (startInit) loadStart(); else initStart(); }
    }

    // ================= rewarded =================
    void showRewarded(final String id) {
        if (unityUsable() && unityReady && unityLoaded) { showUnity(id); return; }   // 1) Unity
        if (startUsable() && startLoaded && startAd != null) { showStart(id); return; } // 2) Start.io
        if (admobUsable() && admobAd != null) { showAdmob(id); return; } // 3) AdMob
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
                    if (startUsable() && startLoaded && startAd != null) showStart(id);
                    else if (admobUsable() && admobAd != null) showAdmob(id);
                    else cb.done(id, false);
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

    // ---------- Google AdMob (final backup) ----------
    private void initAdmob() {
        if (!admobInit) {
            admobInit = true;
            try { MobileAds.initialize(act, status -> act.runOnUiThread(() -> { loadAdmob(); refreshBanner(); })); }
            catch (Throwable t) { admobInit = false; }
        } else loadAdmob();
    }

    private void loadAdmob() {
        if (admobLoading || admobAd != null) return;
        admobLoading = true;
        try {
            RewardedAd.load(act, admobRewardedId(), new AdRequest.Builder().build(), new RewardedAdLoadCallback() {
                @Override public void onAdLoaded(RewardedAd ad) { admobAd = ad; admobLoading = false; }
                @Override public void onAdFailedToLoad(LoadAdError error) { admobAd = null; admobLoading = false; }
            });
        } catch (Throwable t) { admobLoading = false; }
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

    // ================= banner =================
    /** Unity banner first; if it fails (or Unity is OFF) the Start.io banner is used. */
    private String bannerNetwork() {
        if (unityUsable() && unityReady && !unityBannerFailed) return "unity";
        if (startUsable() && startInit && !startBannerFailed) return "startio";
        if (admobUsable() && admobInit && !admobBannerFailed) return "admob";
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
                    @Override public void onBannerShown(BannerView v) {}
                    @Override public void onBannerClick(BannerView v) {}
                    @Override public void onBannerFailedToLoad(BannerView v, BannerErrorInfo e) { act.runOnUiThread(() -> { unityBannerFailed = true; refreshBanner(); }); }
                    @Override public void onBannerLeftApplication(BannerView v) {}
                });
                holder.addView(b, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL));
                b.load(); bannerView = b;
            } else if ("admob".equals(want)) {
                android.util.DisplayMetrics dm = act.getResources().getDisplayMetrics();
                AdView b = new AdView(act);
                b.setAdUnitId(admobBannerId());
                b.setAdSize(AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(act, (int) (dm.widthPixels / dm.density)));
                b.setAdListener(new AdListener() { @Override public void onAdFailedToLoad(LoadAdError e) { act.runOnUiThread(() -> { admobBannerFailed = true; removeBanner(); updateBannerVisibility(); }); } });
                holder.addView(b); b.loadAd(new AdRequest.Builder().build()); bannerView = b;
            } else {
                Banner b = new Banner(act, new BannerListener() {
                    @Override public void onReceiveAd(View v) {}
                    @Override public void onFailedToReceiveAd(View v) { act.runOnUiThread(() -> { startBannerFailed = true; refreshBanner(); }); }
                    @Override public void onImpression(View v) {}
                    @Override public void onClick(View v) {}
                });
                holder.addView(b, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL));
                bannerView = b;
            }
        } catch (Throwable e) { bannerView = null; if ("unity".equals(want)) unityBannerFailed = true; else if ("startio".equals(want)) startBannerFailed = true; }
        updateBannerVisibility();
    }

    private void removeBanner() {
        try { if (bannerView instanceof BannerView) ((BannerView) bannerView).destroy(); else if (bannerView instanceof AdView) ((AdView) bannerView).destroy(); } catch (Exception ignored) {}
        holder.removeAllViews(); bannerView = null; bannerNet = "";
    }

    private void updateBannerVisibility() {
        holder.setVisibility(bannerWanted && bannerView != null ? View.VISIBLE : View.GONE);
    }

    void pause() { if (bannerView instanceof AdView) ((AdView) bannerView).pause(); }
    void resume() { if (bannerView instanceof AdView) ((AdView) bannerView).resume(); }
    void destroy() { removeBanner(); }
}
