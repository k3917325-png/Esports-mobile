package com.mobile.esports;

import android.app.Activity;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

import com.unity3d.mediation.*;
import com.unity3d.mediation.banner.*;
import com.unity3d.mediation.rewarded.*;

/**
 * Single ad source: Unity LevelPlay (mediation). Every other network (AdMob, AppLovin, Mintegral, Liftoff...) is
 * added INSIDE the LevelPlay dashboard + adapters, so no other SDK lives in this file.
 * Rewarded ad: only on the RP claim. Banner: pinned to the TOP of the screen (away from game buttons).
 * All calls must happen on the UI thread.
 */
class AdHub {
    interface Result { void done(String id, boolean ok); }

    private final Activity act;
    private final FrameLayout holder;
    private final Result cb;

    // ---- admin config (pushed from HTML) ----
    private boolean configured = false, on = true, lpOn = true, bannerOn = true, debug = false, consent = false;
    private String lpKey = "", lpRewardedId = "", lpBannerId = "";
    private boolean bannerWanted = false;
    private long lastRetry = 0;

    // ---- debug (admin toggle "Ad Debug Messages") ----
    private String lSt = "-";
    private void dbg(String m) { if (debug) try { android.widget.Toast.makeText(act, m, android.widget.Toast.LENGTH_LONG).show(); } catch (Throwable ignored) {} }
    private static String cut(String x) { x = x == null ? "" : x.replaceAll("\\s+", " "); return x.length() > 160 ? x.substring(0, 160) : x; }
    private void dbgDialog(String m) {
        if (!debug) return;
        try { new android.app.AlertDialog.Builder(act).setTitle("Ad debug").setMessage(m).setPositiveButton("OK", null).show(); } catch (Throwable ignored) {}
    }
    private String stat() { return "LevelPlay: " + lSt; }

    // ---- LevelPlay state ----
    private boolean lpInitStarted = false, lpReady = false, lpBannerFailed = false, lpEarned = false;
    private LevelPlayRewardedAd lpAd; private String lpAdUnit = ""; private String lpPendingId;

    // ---- banner ----
    private View bannerView; private String bannerNet = "";

    AdHub(Activity act, FrameLayout holder, Result cb) { this.act = act; this.holder = holder; this.cb = cb; }

    // ================= config =================
    void consentGranted() { consent = true; apply(); }

    void configure(String json) {
        try {
            org.json.JSONObject o = new org.json.JSONObject(json);
            on = o.optBoolean("on", true);
            lpOn = o.optBoolean("unityOn", true);
            bannerOn = o.optBoolean("bannerOn", true);
            lpKey = plc(o.optString("lpKey", ""));
            lpRewardedId = plc(o.optString("lpRewarded", ""));
            lpBannerId = plc(o.optString("lpBanner", ""));
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

    private boolean lpUsable() { return configured && on && lpOn && !appKey().isEmpty() && !rewardedUnit().isEmpty(); }

    /** Loads both up front so the fallback is ready the moment LevelPlay has no ad. */
    private void apply() {
        if (lpUsable()) initLevelPlay();
        refreshBanner();
    }

    private void retryAll() {
        long now = System.currentTimeMillis();
        if (now - lastRetry < 15000) return;
        lastRetry = now;
        lpBannerFailed = false;
        if (lpUsable()) { if (lpReady) loadLp(); else initLevelPlay(); }
    }

    // ================= rewarded =================
    void showRewarded(final String id) {
        if (lpUsable() && lpReady && lpAd != null && lpAd.isAdReady()) { dbg("Ad: LEVELPLAY"); showLp(id); return; }                       // 1) LevelPlay
        dbgDialog("NO AD\n\n" + stat() + "\n\nLevelPlay key set=" + (!appKey().isEmpty()) + "\nrewarded unit=" + rewardedUnit() + "\nbanner unit=" + bannerUnit());
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

    // ================= banner =================
    /** LevelPlay banner only (other networks are mediated inside LevelPlay). */
    private String bannerNetwork() {
        if (bannerOn && lpUsable() && lpReady && !bannerUnit().isEmpty() && !lpBannerFailed) return "levelplay";
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
            }
        } catch (Throwable e) { bannerView = null; lpBannerFailed = true; }
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
