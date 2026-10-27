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
import com.unity3d.mediation.*;
import com.unity3d.mediation.banner.*;
import com.unity3d.mediation.rewarded.*;

/**
 * Two ad sources, strict priority:
 *   1. Unity LevelPlay (mediation; Unity Ads bidding placements are set up INSIDE the LevelPlay dashboard) - rewarded + banner
 *   2. Start.io - fallback, used the moment LevelPlay has no ad / fails.
 * Admin panel can switch each ON/OFF. All calls must happen on the UI thread.
 */
class AdHub {
    interface Result { void done(String id, boolean ok); }

    private final Activity act;
    private final FrameLayout holder;
    private final Result cb;

    // ---- admin config (pushed from HTML) ----
    private boolean configured = false, on = true, lpOn = true, startOn = true, debug = false, consent = false;
    private String lpKey = "", lpRewardedId = "", lpBannerId = "", startId = "";
    private boolean bannerWanted = false;
    private long lastRetry = 0;

    // ---- debug (admin toggle "Ad Debug Messages") ----
    private String lSt = "-", sSt = "-";
    private void dbg(String m) { if (debug) try { android.widget.Toast.makeText(act, m, android.widget.Toast.LENGTH_LONG).show(); } catch (Throwable ignored) {} }
    private static String cut(String x) { x = x == null ? "" : x.replaceAll("\\s+", " "); return x.length() > 160 ? x.substring(0, 160) : x; }
    private void dbgDialog(String m) {
        if (!debug) return;
        try { new android.app.AlertDialog.Builder(act).setTitle("Ad debug").setMessage(m).setPositiveButton("OK", null).show(); } catch (Throwable ignored) {}
    }
    private String stat() { return "LevelPlay: " + lSt + "\nStart.io: " + sSt; }

    // ---- LevelPlay state ----
    private boolean lpInitStarted = false, lpReady = false, lpBannerFailed = false, lpEarned = false;
    private LevelPlayRewardedAd lpAd; private String lpAdUnit = ""; private String lpPendingId;
    // ---- Start.io state ----
    private boolean startInit = false, startLoading = false, startLoaded = false;
    private StartAppAd startAd;
    private boolean startEarned = false;

    // ---- banner ----
    private View bannerView; private String bannerNet = "";
    private boolean startBannerFailed = false;

    AdHub(Activity act, FrameLayout holder, Result cb) { this.act = act; this.holder = holder; this.cb = cb; }

    // ================= config =================
    void consentGranted() { consent = true; apply(); }

    void configure(String json) {
        try {
            org.json.JSONObject o = new org.json.JSONObject(json);
            on = o.optBoolean("on", true);
            lpOn = o.optBoolean("unityOn", true);
            startOn = o.optBoolean("startOn", true);
            lpKey = plc(o.optString("lpKey", ""));
            lpRewardedId = plc(o.optString("lpRewarded", ""));
            lpBannerId = plc(o.optString("lpBanner", ""));
            startId = plc(o.optString("startId", ""));
            debug = o.optBoolean("adDebug", false);
            configured = true;
        } catch (Exception e) { return; }
        apply();
    }

    void setBannerWanted(boolean w) { bannerWanted = w; updateBannerVisibility(); }

    private static String plc(String s) { return s.replaceAll("[^A-Za-z0-9_\\-]", ""); }

    private String appKey() { return lpKey.isEmpty() ? act.getString(R.string.levelplay_app_key) : lpKey; }
    private String rewardedUnit() { return lpRewardedId.isEmpty() ? act.getString(R.string.levelplay_rewarded_id) : lpRewardedId; }
    private String bannerUnit() { return lpBannerId.isEmpty() ? act.getString(R.string.levelplay_banner_id) : lpBannerId; }
    private String startAppId() { return startId.isEmpty() ? act.getString(R.string.startio_app_id) : startId; }

    private boolean lpUsable() { return configured && on && lpOn && !appKey().isEmpty() && !rewardedUnit().isEmpty(); }
    private boolean startUsable() { return configured && on && startOn && consent && !startAppId().isEmpty(); }

    /** Loads both up front so the fallback is ready the moment LevelPlay has no ad. */
    private void apply() {
        if (lpUsable()) initLevelPlay();
        if (startUsable()) initStart();
        refreshBanner();
    }

    private void retryAll() {
        long now = System.currentTimeMillis();
        if (now - lastRetry < 15000) return;
        lastRetry = now;
        lpBannerFailed = false; startBannerFailed = false;
        if (lpUsable()) { if (lpReady) loadLp(); else initLevelPlay(); }
        if (startUsable()) { if (startInit) loadStart(); else initStart(); }
    }

    // ================= rewarded =================
    void showRewarded(final String id) {
        if (lpUsable() && lpReady && lpAd != null && lpAd.isAdReady()) { dbg("Ad: LEVELPLAY"); showLp(id); return; }                       // 1) LevelPlay
        if (startUsable() && startLoaded && startAd != null) { dbg("Ad: START.IO (LevelPlay: " + lSt + ")"); showStart(id); return; }      // 2) Start.io
        dbgDialog("NO AD\n\n" + stat() + "\n\nLevelPlay key set=" + (!appKey().isEmpty()) + "\nrewarded unit=" + rewardedUnit() + "\nbanner unit=" + bannerUnit() + "\nStart.io id=" + startAppId() + (consent ? "" : "\n(consent pending)"));
        retryAll();
        cb.done(id, false);
    }

    // ---------- LevelPlay ----------
    private void initLevelPlay() {
        if (lpInitStarted) { if (lpReady) loadLp(); return; }
        lpInitStarted = true;
        try {
            LevelPlayInitRequest req = new LevelPlayInitRequest.Builder(appKey()).build();
            LevelPlay.init(act.getApplicationContext(), req, new LevelPlayInitListener() {
                @Override public void onInitFailed(LevelPlayInitError error) { act.runOnUiThread(() -> { lSt = "init FAIL " + cut(String.valueOf(error)); lpInitStarted = false; lpBannerFailed = true; refreshBanner(); }); }
                @Override public void onInitSuccess(LevelPlayConfiguration configuration) { act.runOnUiThread(() -> { lSt = "init ok"; lpReady = true; loadLp(); refreshBanner(); }); }
            });
        } catch (Throwable t) { lpInitStarted = false; lSt = "init EXC " + cut(String.valueOf(t)); }
    }

    private void loadLp() {
        if (!lpReady) return;
        try {
            if (lpAd == null || !rewardedUnit().equals(lpAdUnit)) {
                lpAdUnit = rewardedUnit();
                lpAd = new LevelPlayRewardedAd(lpAdUnit);
                lpAd.setListener(new LevelPlayRewardedAdListener() {
                    @Override public void onAdLoaded(LevelPlayAdInfo info) { lSt = "ad ready"; }
                    @Override public void onAdLoadFailed(LevelPlayAdError error) { lSt = "load FAIL " + cut(String.valueOf(error)); }
                    @Override public void onAdDisplayed(LevelPlayAdInfo info) {}
                    @Override public void onAdRewarded(LevelPlayReward reward, LevelPlayAdInfo info) { lpEarned = true; }
                    @Override public void onAdDisplayFailed(LevelPlayAdError error, LevelPlayAdInfo info) { act.runOnUiThread(() -> { lSt = "show FAIL " + cut(String.valueOf(error)); lpResult(false); loadLp(); }); }
                    @Override public void onAdClicked(LevelPlayAdInfo info) {}
                    @Override public void onAdClosed(LevelPlayAdInfo info) { act.runOnUiThread(() -> { lpResult(lpEarned); lpEarned = false; loadLp(); }); }
                    @Override public void onAdInfoChanged(LevelPlayAdInfo info) {}
                });
            }
            if (!lpAd.isAdReady()) lpAd.loadAd();
        } catch (Throwable t) { lSt = "load EXC " + cut(String.valueOf(t)); }
    }

    private void lpResult(boolean ok) { String id = lpPendingId; lpPendingId = null; if (id != null) cb.done(id, ok); }

    private void showLp(final String id) { lpPendingId = id; lpEarned = false; try { lpAd.showAd(act); } catch (Throwable t) { lpResult(false); } }

    // ---------- Start.io ----------
    private void initStart() {
        if (!startInit) {
            startInit = true;
            try {
                StartAppSDK.init(act, startAppId(), false);      // false = no "return ads"
                StartAppAd.disableSplash();
                StartAppSDK.setTestAdsEnabled(false);
                sSt = "init ok";
            } catch (Throwable t) { startInit = false; sSt = "init FAIL " + cut(String.valueOf(t)); return; }
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
                @Override public void onReceiveAd(Ad a) { act.runOnUiThread(() -> { sSt = "ad ready"; startAd = ad; startLoaded = true; startLoading = false; }); }
                @Override public void onFailedToReceiveAd(Ad a) { act.runOnUiThread(() -> { sSt = "load FAIL " + cut(a == null ? "" : a.getErrorMessage()); startAd = null; startLoaded = false; startLoading = false; }); }
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
    /** LevelPlay banner first; if it fails (or LevelPlay is OFF / not configured) the Start.io banner is used. */
    private String bannerNetwork() {
        if (lpUsable() && lpReady && !bannerUnit().isEmpty() && !lpBannerFailed) return "levelplay";
        if (startUsable() && startInit && !startBannerFailed) return "startio";
        return "";
    }

    private void refreshBanner() {
        String want = bannerNetwork();
        if (want.equals(bannerNet) && (bannerView != null || want.isEmpty())) { updateBannerVisibility(); return; }
        removeBanner();
        bannerNet = want;
        dbg("Banner: " + (want.isEmpty() ? "NONE" : want.toUpperCase()));
        if (want.isEmpty()) { updateBannerVisibility(); return; }
        try {
            if ("levelplay".equals(want)) {
                LevelPlayBannerAdView.Config cfg = new LevelPlayBannerAdView.Config.Builder().setAdSize(LevelPlayAdSize.BANNER).build();
                LevelPlayBannerAdView b = new LevelPlayBannerAdView(act, bannerUnit(), cfg);
                b.setBannerListener(new LevelPlayBannerAdViewListener() {
                    @Override public void onAdLoaded(LevelPlayAdInfo info) {}
                    @Override public void onAdLoadFailed(LevelPlayAdError error) { act.runOnUiThread(() -> { lSt = "banner FAIL " + cut(String.valueOf(error)); lpBannerFailed = true; refreshBanner(); }); }
                    @Override public void onAdDisplayed(LevelPlayAdInfo info) {}
                    @Override public void onAdDisplayFailed(LevelPlayAdInfo info, LevelPlayAdError error) {}
                    @Override public void onAdClicked(LevelPlayAdInfo info) {}
                    @Override public void onAdExpanded(LevelPlayAdInfo info) {}
                    @Override public void onAdCollapsed(LevelPlayAdInfo info) {}
                    @Override public void onAdLeftApplication(LevelPlayAdInfo info) {}
                });
                holder.addView(b, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL));
                b.loadAd(); bannerView = b;
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
        } catch (Throwable e) { bannerView = null; if ("levelplay".equals(want)) lpBannerFailed = true; else if ("startio".equals(want)) startBannerFailed = true; }
        updateBannerVisibility();
    }

    private void removeBanner() {
        try { if (bannerView instanceof LevelPlayBannerAdView) ((LevelPlayBannerAdView) bannerView).destroy(); } catch (Throwable ignored) {}
        holder.removeAllViews(); bannerView = null; bannerNet = "";
    }

    private void updateBannerVisibility() {
        holder.setVisibility(bannerWanted && bannerView != null ? View.VISIBLE : View.GONE);
    }

    void pause() {}
    void resume() {}
    void destroy() { removeBanner(); }
}
