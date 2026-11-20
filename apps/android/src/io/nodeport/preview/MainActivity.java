package io.nodeport.preview;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Displays the same bundled dashboard as the Windows preview; no native JS bridge. */
public final class MainActivity extends Activity {
    private WebView view;

    @Override public void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        FrameLayout root = new FrameLayout(this);
        root.setFitsSystemWindows(true);
        view = new WebView(this);
        root.addView(view);
        setContentView(root);
        WebSettings settings = view.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccess(false);
        settings.setBlockNetworkLoads(true);
        view.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return true; // Never navigate to external content.
            }
        });
        try (InputStream in = getAssets().open("workspace.html")) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            for (int n; (n = in.read(chunk)) != -1;) bytes.write(chunk, 0, n);
            // A stable, non-routable origin allows localStorage without file:// privileges.
            view.loadDataWithBaseURL("https://nodeport.invalid/", new String(bytes.toByteArray(),
                StandardCharsets.UTF_8), "text/html", "UTF-8", null);
        } catch (IOException e) {
            throw new IllegalStateException("Bundled workspace missing", e);
        }
    }
}
