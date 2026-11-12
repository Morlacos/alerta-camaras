package la.alertacamaras;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;

public class MainActivity extends Activity {
    private WebView web;
    private GeolocationPermissions.Callback pendingCb;
    private String pendingOrigin;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        web = new WebView(this);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setGeolocationEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback cb) {
                if (hasLocation()) {
                    cb.invoke(origin, true, false);
                } else {
                    pendingCb = cb;
                    pendingOrigin = origin;
                    askPermissions();
                }
            }
        });
        web.addJavascriptInterface(new Bridge(), "Android");
        web.loadDataWithBaseURL("https://morlacos.github.io/alerta-camaras/", readAsset("index.html"), "text/html", "utf-8", null);
        askPermissions();
    }

    private String readAsset(String name) {
        try {
            InputStream in = getAssets().open(name);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) > 0) bo.write(buf, 0, r);
            in.close();
            return bo.toString("UTF-8");
        } catch (Exception e) {
            return "<h1>Error cargando la app</h1>";
        }
    }

    private boolean hasLocation() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void askPermissions() {
        ArrayList<String> need = new ArrayList<>();
        if (!hasLocation()) {
            need.add(Manifest.permission.ACCESS_FINE_LOCATION);
            need.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), 7);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (pendingCb != null) {
            pendingCb.invoke(pendingOrigin, hasLocation(), false);
            pendingCb = null;
        }
    }

    @Override
    public void onBackPressed() {
        moveTaskToBack(true);
    }

    public class Bridge {
        @JavascriptInterface
        public boolean start(int radius, boolean voice) {
            if (!hasLocation()) {
                runOnUiThread(MainActivity.this::askPermissions);
                return false;
            }
            Intent i = new Intent(MainActivity.this, CameraService.class);
            i.putExtra("r", radius);
            i.putExtra("v", voice);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
            return true;
        }

        @JavascriptInterface
        public void stop() {
            stopService(new Intent(MainActivity.this, CameraService.class));
        }

        @JavascriptInterface
        public boolean isRunning() {
            return CameraService.running;
        }
    }
}
