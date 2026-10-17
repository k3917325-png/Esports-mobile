package com.mobile.esports;

import android.Manifest;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Base64;
import android.view.KeyEvent;
import android.webkit.ConsoleMessage;
import android.webkit.DownloadListener;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.JavascriptInterface;
import android.webkit.JsResult;
import android.webkit.WebResourceRequest;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.gms.ads.AdError;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.FullScreenContentCallback;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.MobileAds;
import com.google.android.gms.ads.rewarded.RewardedAd;
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback;
import com.google.android.ump.ConsentInformation;
import com.google.android.ump.ConsentRequestParameters;
import com.google.android.ump.UserMessagingPlatform;

import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Thin native wrapper around the Esports Reward single-file HTML app.
 * The app's own UI, game logic, hidden admin view, localStorage-based persistence
 * etc. all run inside this WebView, loaded from assets/index.html.
 *
 * Admin gate: the page asks for window.AndroidAdmin.authenticate() (after the secret 10-tap gesture);
 * this class shows the system fingerprint prompt and reports back through window.__adminAuth(ok, message).
 * The fingerprint only unlocks the admin SCREEN; every admin action is still authorised by the Apps Script server (PIN/token).
 */
public class MainActivity extends AppCompatActivity {

    private WebView webView;
    private static final int REQ_NOTIF_PERMISSION = 1001;

    // ---- AdMob rewarded ads ----
    private RewardedAd rewardedAd;
    private boolean loadingAd = false;
    private final AtomicBoolean adsInitialized = new AtomicBoolean(false);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        setContentView(webView);

        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);          // required: app persists state via localStorage
        ws.setDatabaseEnabled(true);
        ws.setAllowFileAccess(true);
        ws.setLoadWithOverviewMode(true);
        ws.setUseWideViewPort(true);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setCacheMode(WebSettings.LOAD_DEFAULT);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String u = request.getUrl().toString();
                if (u.startsWith("file:///android_asset/")) return false; // our own app
                // anything external (hosted Privacy Policy etc.) opens in the browser, never inside the WebView
                try { startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW, request.getUrl())); } catch (Exception ignored) {}
                return true;
            }
        });
        webView.addJavascriptInterface(new AdsBridge(), "AndroidAds");
        webView.addJavascriptInterface(new AdminBridge(), "AndroidAdmin");
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage cm) {
                return true; // swallow JS console noise in logcat
            }

            // WebView swallows JS alert()/confirm() unless the app shows them itself.
            @Override
            public boolean onJsAlert(WebView view, String url, String message, final JsResult result) {
                new AlertDialog.Builder(MainActivity.this).setMessage(message)
                        .setPositiveButton(android.R.string.ok, (d, w) -> result.confirm())
                        .setOnCancelListener(d -> result.cancel()).show();
                return true;
            }

            @Override
            public boolean onJsConfirm(WebView view, String url, String message, final JsResult result) {
                new AlertDialog.Builder(MainActivity.this).setMessage(message)
                        .setPositiveButton(android.R.string.ok, (d, w) -> result.confirm())
                        .setNegativeButton(android.R.string.cancel, (d, w) -> result.cancel())
                        .setOnCancelListener(d -> result.cancel()).show();
                return true;
            }

            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> request.grant(request.getResources()));
            }
        });

        // CSV exports / data: URLs the app generates (TDS reports etc.) -> save to Downloads
        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                         String mimeType, long contentLength) {
                try {
                    if (url.startsWith("data:")) {
                        saveDataUrlToDownloads(url, contentDisposition);
                    } else {
                        android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(url));
                        startActivity(intent);
                    }
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Download failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            }
        });

        requestNotificationPermissionIfNeeded();
        initConsentThenAds();

        webView.loadUrl("file:///android_asset/index.html");
    }

    // ===================== AdMob =====================
    /** JS-facing bridge: window.AndroidAds.showRewarded(id) -> later window.__adResult(id, earned) */
    private class AdsBridge {
        @JavascriptInterface
        public void showRewarded(final String id) {
            final String safeId = id == null ? "0" : id.replaceAll("[^0-9]", "");
            runOnUiThread(() -> showRewardedInternal(safeId));
        }
    }

    // ===================== Admin fingerprint gate =====================
    /** JS-facing bridge: window.AndroidAdmin.authenticate() -> later window.__adminAuth(ok, message) */
    private class AdminBridge {
        @JavascriptInterface
        public void authenticate() {
            runOnUiThread(() -> authenticateAdmin());
        }
    }

    private void authenticateAdmin() {
        final int authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG; // fingerprint (Class 3)
        int can = BiometricManager.from(this).canAuthenticate(authenticators);
        if (can != BiometricManager.BIOMETRIC_SUCCESS) {
            String msg;
            if (can == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED) msg = "Is phone mein fingerprint set nahi hai (Settings mein add karo)";
            else if (can == BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE) msg = "Is phone mein fingerprint sensor nahi hai";
            else msg = "Fingerprint abhi available nahi hai";
            jsAdminResult(false, msg);
            return;
        }
        Executor executor = ContextCompat.getMainExecutor(this);
        BiometricPrompt prompt = new BiometricPrompt(this, executor, new BiometricPrompt.AuthenticationCallback() {
            @Override
            public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                jsAdminResult(true, "");
            }

            @Override
            public void onAuthenticationError(int errorCode, CharSequence errString) {
                // cancelled, locked out, or no match after retries -> abort the login
                jsAdminResult(false, "Fingerprint verify nahi hua — admin band");
            }
            // onAuthenticationFailed(): a single wrong finger; the system prompt stays open for another try.
        });
        BiometricPrompt.PromptInfo info = new BiometricPrompt.PromptInfo.Builder()
                .setTitle("Admin unlock")
                .setSubtitle("Apni fingerprint verify karo")
                .setNegativeButtonText("Cancel")
                .setAllowedAuthenticators(authenticators)
                .build();
        prompt.authenticate(info);
    }

    private void jsAdminResult(final boolean ok, String msg) {
        final String safe = msg == null ? "" : msg.replace("\\", " ").replace("'", " ").replace("\n", " ");
        webView.post(() -> webView.evaluateJavascript("window.__adminAuth(" + ok + ",'" + safe + "')", null));
    }

    /** EU/UK users must see Google's consent form (UMP) before any ad request. */
    private void initConsentThenAds() {
        final ConsentInformation ci = UserMessagingPlatform.getConsentInformation(this);
        ConsentRequestParameters params = new ConsentRequestParameters.Builder().build();
        ci.requestConsentInfoUpdate(this, params,
                () -> UserMessagingPlatform.loadAndShowConsentFormIfRequired(this, formError -> {
                    if (ci.canRequestAds()) initAds();
                }),
                formError -> { if (ci.canRequestAds()) initAds(); });
        if (ci.canRequestAds()) initAds(); // consent already gathered in an earlier session
    }

    private void initAds() {
        if (!adsInitialized.compareAndSet(false, true)) return;
        MobileAds.initialize(this, status -> runOnUiThread(this::loadRewarded));
    }

    private void loadRewarded() {
        if (loadingAd || rewardedAd != null) return;
        loadingAd = true;
        RewardedAd.load(this, getString(R.string.admob_rewarded_id), new AdRequest.Builder().build(),
                new RewardedAdLoadCallback() {
                    @Override public void onAdLoaded(RewardedAd ad) { rewardedAd = ad; loadingAd = false; }
                    @Override public void onAdFailedToLoad(LoadAdError error) { rewardedAd = null; loadingAd = false; }
                });
    }

    private void showRewardedInternal(final String id) {
        if (rewardedAd == null) { loadRewarded(); jsAdResult(id, false); return; }
        final RewardedAd ad = rewardedAd;
        rewardedAd = null;
        final boolean[] earned = {false};
        ad.setFullScreenContentCallback(new FullScreenContentCallback() {
            @Override public void onAdDismissedFullScreenContent() { jsAdResult(id, earned[0]); loadRewarded(); }
            @Override public void onAdFailedToShowFullScreenContent(AdError error) { jsAdResult(id, false); loadRewarded(); }
        });
        ad.show(this, rewardItem -> earned[0] = true);
    }

    private void jsAdResult(final String id, final boolean ok) {
        webView.post(() -> webView.evaluateJavascript("window.__adResult('" + id + "'," + ok + ")", null));
    }

    private void saveDataUrlToDownloads(String dataUrl, String contentDisposition) {
        try {
            int comma = dataUrl.indexOf(',');
            String meta = dataUrl.substring(5, comma); // e.g. text/csv;base64
            String data = dataUrl.substring(comma + 1);
            boolean isBase64 = meta.contains("base64");
            byte[] bytes = isBase64 ? Base64.decode(data, Base64.DEFAULT) : Uri.decode(data).getBytes();

            String fileName = "export_" + System.currentTimeMillis() + ".csv";
            if (contentDisposition != null && contentDisposition.contains("filename=")) {
                fileName = contentDisposition.split("filename=")[1].replace("\"", "").trim();
            }

            File dir = getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS);
            if (dir != null && !dir.exists()) dir.mkdirs();
            File out = new File(dir, fileName);
            try (FileOutputStream fos = new FileOutputStream(out)) {
                fos.write(bytes);
            }
            Toast.makeText(this, "Saved: " + out.getAbsolutePath(), Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Could not save file", Toast.LENGTH_SHORT).show();
        }
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF_PERMISSION);
            }
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView.canGoBack()) {
            webView.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
