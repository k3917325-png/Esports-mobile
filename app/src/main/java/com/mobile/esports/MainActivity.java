package com.mobile.esports;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
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
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.tasks.Task;

import java.io.File;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Thin native wrapper around the GameNest Rewards single-file HTML app.
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

    private AdHub hub;
    private FrameLayout bannerHolder;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Rooted phones are where coin-editing / hooking tools run: refuse to start there.
        if (looksRooted()) {
            new AlertDialog.Builder(this)
                    .setTitle("Security")
                    .setMessage("Yeh app rooted / modified phone par nahi chalta.")
                    .setCancelable(false)
                    .setPositiveButton(android.R.string.ok, (d, w) -> finishAndRemoveTask())
                    .show();
            return;
        }

        webView = new WebView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.addView(webView, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        bannerHolder = new FrameLayout(this);
        bannerHolder.setVisibility(android.view.View.GONE);
        root.addView(bannerHolder, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.setBackgroundColor(android.graphics.Color.parseColor("#140a2e"));
        // targetSdk 35+ forces edge-to-edge: keep the page clear of the status bar / navigation bar / cutout
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            androidx.core.graphics.Insets b = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() | androidx.core.view.WindowInsetsCompat.Type.displayCutout() | androidx.core.view.WindowInsetsCompat.Type.ime());
            v.setPadding(b.left, b.top, b.right, b.bottom);
            return androidx.core.view.WindowInsetsCompat.CONSUMED;
        });
        setContentView(root);
        hub = new AdHub(this, bannerHolder, (id, ok) -> jsAdResult(id, ok));

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
        webView.addJavascriptInterface(new DeviceBridge(), "AndroidDevice");
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
            runOnUiThread(() -> hub.showRewarded(safeId));
        }
        @JavascriptInterface public boolean hasBanner() { return true; }
        @JavascriptInterface public void configure(final String json) { runOnUiThread(() -> hub.configure(json)); }
        @JavascriptInterface public void setBanner(final boolean on) { runOnUiThread(() -> hub.setBannerWanted(on)); }
    }

    // ===================== Device binding + root check =====================
    /** JS-facing bridge: window.AndroidDevice.id() -> stable per-device, per-app hash (never the raw ANDROID_ID). */
    private class DeviceBridge {
        /** Google sign-in: window.AndroidDevice.googleSignIn(webClientId) -> later window.__googleResult(idToken, error) */
        @JavascriptInterface
        public void googleSignIn(final String webClientId) {
            runOnUiThread(() -> {
                try {
                    if (webClientId == null || !webClientId.matches("^[0-9]+-[a-z0-9]+\\.apps\\.googleusercontent\\.com$")) { googleDone(null, "Google Client ID galat/missing"); return; }
                    GoogleSignInOptions gso = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                            .requestIdToken(webClientId).requestEmail().build();
                    com.google.android.gms.auth.api.signin.GoogleSignInClient c = GoogleSignIn.getClient(MainActivity.this, gso);
                    c.signOut().addOnCompleteListener(t -> startActivityForResult(c.getSignInIntent(), RC_GOOGLE)); // signOut first = account chooser every time
                } catch (Exception e) { googleDone(null, "Google sign-in start nahi hua"); }
            });
        }

        @JavascriptInterface
        public String id() {
            try {
                String a = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
                if (a == null) a = "";
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                byte[] h = md.digest((a + ":" + getPackageName()).getBytes("UTF-8"));
                StringBuilder sb = new StringBuilder();
                for (byte b : h) sb.append(String.format("%02x", b));
                return sb.substring(0, 48);
            } catch (Exception e) {
                return "";
            }
        }
    }

    private static final int RC_GOOGLE = 9001;

    private void googleDone(final String idToken, final String err) {
        final String t = idToken == null ? "null" : "'" + idToken.replace("'", "") + "'";
        final String e = err == null ? "''" : "'" + err.replace("'", " ") + "'";
        webView.post(() -> webView.evaluateJavascript("window.__googleResult(" + t + "," + e + ")", null));
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != RC_GOOGLE) return;
        try {
            Task<GoogleSignInAccount> task = GoogleSignIn.getSignedInAccountFromIntent(data);
            GoogleSignInAccount acc = task.getResult(ApiException.class);
            String tok = acc.getIdToken();
            if (tok == null) googleDone(null, "Google ne token nahi diya (Web Client ID / SHA-1 check karo)");
            else googleDone(tok, null);
        } catch (ApiException ex) {
            googleDone(null, "Google sign-in fail (code " + ex.getStatusCode() + ") - SHA-1 Firebase mein add hai?");
        } catch (Exception ex) {
            googleDone(null, "Google sign-in cancel");
        }
    }

    /** Basic root indicators. Cheap to bypass for a determined attacker - it only stops casual tools. */
    private boolean looksRooted() {
        String tags = Build.TAGS;
        if (tags != null && tags.contains("test-keys")) return true;
        String[] paths = {"/system/app/Superuser.apk", "/sbin/su", "/system/bin/su", "/system/xbin/su", "/data/local/xbin/su",
                "/data/local/bin/su", "/system/sd/xbin/su", "/system/bin/failsafe/su", "/data/local/su", "/su/bin/su",
                "/system/xbin/daemonsu", "/data/adb/magisk", "/sbin/.magisk"};
        for (String p : paths) {
            try { if (new File(p).exists()) return true; } catch (Exception ignored) {}
        }
        return false;
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

    private void initConsentThenAds() { initAds(); }

    private void initAds() { if (hub != null) hub.consentGranted(); }

    @Override protected void onPause() { if (hub != null) hub.pause(); super.onPause(); }
    @Override protected void onResume() { super.onResume(); if (hub != null) hub.resume(); }
    @Override protected void onDestroy() { if (hub != null) hub.destroy(); super.onDestroy(); }

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
        if (keyCode == KeyEvent.KEYCODE_BACK && webView != null && webView.canGoBack()) {
            webView.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
