package cl.sopa1d.app;

import android.app.Activity;
import android.app.UiModeManager;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Bundle;
import android.os.Message;
import android.util.Log;
import android.view.View;
import android.widget.FrameLayout;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.widget.Toast;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.WebViewAssetLoader;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Carga index.html desde los assets y le da a la web un puente hacia ExoPlayer.
 */
public class MainActivity extends Activity {

    private static final String TAG = "Sopa1D";

    /** Dominios de publicidad que inyectan los embeds de películas. */
    private static final Set<String> AD_HOSTS = new HashSet<>(Arrays.asList(
            "doubleclick.net", "googlesyndication.com", "googleadservices.com",
            "adservice.google.com", "google-analytics.com", "googletagservices.com",
            "popads.net", "popcash.net", "popmyads.com", "propellerads.com",
            "propellerclick.com", "exoclick.com", "exosrv.com", "realsrv.com",
            "juicyads.com", "juicyads.rocks", "trafficjunky.net", "trafficfactory.biz",
            "adsterra.com", "hilltopads.net", "onclickalgo.com", "onclickmax.com",
            "clickadu.com", "bidgear.com", "adnxs.com", "adsrvr.org",
            "mgid.com", "outbrain.com", "taboola.com", "revcontent.com",
            "smartadserver.com", "criteo.com", "pubmatic.com", "rubiconproject.com",
            "zedo.com", "adcash.com", "bidvertiser.com", "hotjar.com",
            "trackwilltrk.com", "poweradnetwork.com", "stpd.cloud", "tsyndicate.com",
            // Redes que usan MultiEmbed / VidSrc para popunders y redirecciones
            "pemsrv.com", "magsrv.com", "realsrv.com", "a-ads.com", "adskeeper.com",
            "adskeeper.co.uk", "acscdn.com", "aclib.net", "admaven.com", "ad-maven.com",
            "wpadmngr.com", "hpyrdr.com", "highperformanceformat.com",
            "highperformancedisplayformat.com", "profitablegatecpm.com",
            "effectiveratecpm.com", "toprevenuegate.com", "dtscout.com", "dtscdn.com",
            "jads.co", "rtmark.net", "notix.io", "onesignal.com", "pushwhy.com",
            "richads.com", "monetag.com", "galaksion.com", "clickaine.com",
            "histats.com", "whos.amung.us", "mc.yandex.ru", "yandex.ru",
            "cloudflareinsights.com", "lijit.com", "33across.com", "amazon-adsystem.com",
            "ads.pubmatic.com", "imasdk.googleapis.com", "vidoomy.com", "adform.net",
            "sharethrough.com", "unrulymedia.com", "teads.tv", "springserve.com",
            "displayvertising.com", "dolohen.com", "go.ad2up.com", "ad2up.com",
            "ptp22.com", "inklinkor.com", "tagdeliver.com", "trk.bz",
            "shorte.st", "ouo.io", "linkvertise.com", "adf.ly"
    ));

    /** Fragmentos que delatan un dominio publicitario aunque no esté en la lista. */
    private static final String[] AD_PATTERNS = {
            "popunder", "popads", "/ads/", "adserver", "ad-delivery",
            "banner-ad", "/advert", "clickunder", "trafficstars",
            "cpmgate", "revenuegate", "/pop.js", "/popup.js", "vast.xml", "/vast?",
            "prebid", "adsbygoogle"
    };

    /**
     * Modo ligero para TV. index.html usa 13 backdrop-filter (desenfoque
     * recalculado en cada frame) y varias animaciones infinitas: en un móvil
     * no se nota, pero la GPU de un TV box de gama baja se arrastra.
     */
    private static final String TV_LITE_JS =
            "(function(){"
          + "  if(document.getElementById('tvLiteStyle'))return;"
          + "  var s=document.createElement('style');s.id='tvLiteStyle';"
          + "  s.textContent='*,*::before,*::after{"
          + "    backdrop-filter:none!important;-webkit-backdrop-filter:none!important;"
          + "    animation-iteration-count:1!important;"
          + "    transition-duration:0s!important;"
          + "  }"
          + "  .ambilight-glow{display:none!important;}';"
          + "  document.head.appendChild(s);"
          + "})();";

    private WebView webView;
    private FrameLayout root;
    private FrameLayout fullscreenContainer;
    private View customView;
    private boolean webFullscreen;
    private WebChromeClient chromeClient;
    private WebChromeClient.CustomViewCallback customViewCallback;
    private String adShieldJs;
    private boolean shieldInAllFrames;
    private long lastBackPress;

    private void hideSystemUi(boolean hide) {
        getWindow().getDecorView().setSystemUiVisibility(hide
                ? (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
                : View.SYSTEM_UI_FLAG_VISIBLE);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final WebViewAssetLoader assetLoader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        root = new FrameLayout(this);
        // Solo en builds de depuración: permite inspeccionar la web con
        // chrome://inspect y probar la app de punta a punta desde el PC.
        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true);
        }

        webView = new WebView(this);
        root.addView(webView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        // Contenedor donde el WebView deposita el vídeo al pedir pantalla completa
        fullscreenContainer = new FrameLayout(this);
        fullscreenContainer.setBackgroundColor(0xFF000000);
        fullscreenContainer.setVisibility(View.GONE);
        root.addView(fullscreenContainer, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        setContentView(root);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);          // index.html usa localStorage
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportMultipleWindows(false);    // sin ventanas nuevas = sin popups
        s.setJavaScriptCanOpenWindowsAutomatically(false);

        webView.addJavascriptInterface(new NativeBridge(), "Sopa1D");

        // El escudo tiene que correr dentro de los iframes de los embeds, que
        // es donde están los popups. onPageFinished solo alcanza a index.html.
        adShieldJs = readRaw(R.raw.ad_shield);
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(
                    webView, adShieldJs, Collections.singleton("*"));
            shieldInAllFrames = true;
        } else {
            Log.w(TAG, "WebView sin DOCUMENT_START_SCRIPT: el escudo solo cubre index.html");
        }

        chromeClient = new WebChromeClient() {
            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog,
                                          boolean isUserGesture, Message resultMsg) {
                // Cualquier intento de abrir ventana nueva (popunder) muere aquí.
                return false;
            }

            // Sin estos dos callbacks el WebView ignora las peticiones de
            // pantalla completa del HTML5: el botón del reproductor no hace nada.
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                Log.i(TAG, "onShowCustomView: el embed pidió pantalla completa nativa");
                if (customView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                customView = view;
                customViewCallback = callback;

                fullscreenContainer.addView(view, new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT));
                fullscreenContainer.setVisibility(View.VISIBLE);
                webView.setVisibility(View.GONE);

                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
                hideSystemUi(true);
            }

            @Override
            public void onHideCustomView() {
                if (customView == null) return;

                fullscreenContainer.removeView(customView);
                fullscreenContainer.setVisibility(View.GONE);
                webView.setVisibility(View.VISIBLE);
                customView = null;

                if (customViewCallback != null) {
                    customViewCallback.onCustomViewHidden();
                    customViewCallback = null;
                }

                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
                hideSystemUi(false);
            }
        };
        webView.setWebChromeClient(chromeClient);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri url = request.getUrl();

                if (isAd(url)) {
                    return new WebResourceResponse(
                            "text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
                }

                // Sirve los assets locales bajo un origen https real, para que
                // fetch() y localStorage funcionen como en el servidor web.
                return assetLoader.shouldInterceptRequest(url);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                // Los iframes (los embeds de películas) deben poder navegar
                // libremente: bloquearlos impedía que el reproductor cargara.
                // Excepto hacia intent://, market:// y similares, que solo usan
                // los anuncios para saltar a Play Store o a otra app.
                if (!request.isForMainFrame()) {
                    String scheme = request.getUrl().getScheme();
                    return !"http".equals(scheme) && !"https".equals(scheme)
                            && !"about".equals(scheme) && !"data".equals(scheme)
                            && !"blob".equals(scheme);
                }

                // Solo se restringe la navegación principal, para que ningún
                // anuncio se lleve la app fuera de index.html.
                return !"appassets.androidplatform.net".equals(request.getUrl().getHost());
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (!shieldInAllFrames) view.evaluateJavascript(adShieldJs, null);
                if (isTv()) view.evaluateJavascript(TV_LITE_JS, null);
            }
        });

        webView.loadUrl("https://appassets.androidplatform.net/assets/index.html");
    }

    private boolean isTv() {
        UiModeManager ui = (UiModeManager) getSystemService(UI_MODE_SERVICE);
        return (ui != null && ui.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION)
                || getPackageManager().hasSystemFeature(PackageManager.FEATURE_LEANBACK);
    }

    private boolean isAd(Uri url) {
        String host = url.getHost();
        if (host == null) return false;
        host = host.toLowerCase();

        for (String bad : AD_HOSTS) {
            if (host.equals(bad) || host.endsWith("." + bad)) return true;
        }

        String full = url.toString().toLowerCase();
        for (String p : AD_PATTERNS) {
            if (full.contains(p)) return true;
        }
        return false;
    }

    @Override
    public void onBackPressed() {
        if (customView != null) {
            // En pantalla completa, Atrás sale de ella en vez de navegar.
            // Se usa la referencia guardada porque getWebChromeClient() es API 26+.
            chromeClient.onHideCustomView();
            return;
        }
        if (webFullscreen) {
            // Pantalla completa propia (CSS): se sale desde la web.
            webView.evaluateJavascript("toggleEmbedFullscreen();", null);
            return;
        }
        // Atrás cierra primero lo que esté abierto en la web (reproductor,
        // ajustes, buscador...). Solo si no había nada, sale de la app.
        webView.evaluateJavascript(
                "(window.sopaHandleBack && window.sopaHandleBack()) ? '1' : '0'",
                new ValueCallback<String>() {
                    @Override
                    public void onReceiveValue(String handled) {
                        // No se usa webView.goBack(): index.html hace pushState
                        // al abrir y al cerrar cada título, y volver atrás en ese
                        // historial reabría la película que se acababa de cerrar.
                        if (!"\"1\"".equals(handled)) confirmExit();
                    }
                });
    }

    /** En TV es fácil salir sin querer apretando Atrás de más: se pide doble pulsación. */
    private void confirmExit() {
        long now = System.currentTimeMillis();
        if (now - lastBackPress < 2000) {
            finish();
        } else {
            lastBackPress = now;
            Toast.makeText(this, "Pulsa Atrás otra vez para salir", Toast.LENGTH_SHORT).show();
        }
    }

    private String readRaw(int resId) {
        try (InputStream in = getResources().openRawResource(resId);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } catch (IOException e) {
            Log.e(TAG, "No se pudo leer el recurso " + resId, e);
            return "";
        }
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    /** Lo que la web puede llamar como window.Sopa1D.* */
    private class NativeBridge {

        /**
         * Entrega la lista visible de canales y abre uno. La lista viaja por
         * aquí (misma JVM) y no por el Intent, que no admite este tamaño.
         */
        @JavascriptInterface
        public void playLiveList(String channelsJson, final int startIndex) {
            ChannelStore.set(channelsJson);
            Log.i(TAG, "playLiveList -> " + ChannelStore.size() + " canales, índice " + startIndex);
            // El puente JS corre en su propio hilo, no en el de UI.
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Intent i = new Intent(MainActivity.this, PlayerActivity.class);
                    i.putExtra(PlayerActivity.EXTRA_INDEX, startIndex);
                    startActivity(i);
                }
            });
        }

        /**
         * Pantalla completa para los embeds de películas: la web expande el
         * contenedor por CSS y aquí se acompaña rotando a horizontal y
         * ocultando las barras del sistema.
         */
        @JavascriptInterface
        public void setFullscreen(final boolean on) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    setRequestedOrientation(on
                            ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                            : ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
                    hideSystemUi(on);
                    webFullscreen = on;
                }
            });
        }

        /** Permite a index.html detectar que corre dentro de la APK. */
        @JavascriptInterface
        public boolean isNative() {
            return true;
        }

        /** Activa en la web la navegación con flechas del control remoto. */
        @JavascriptInterface
        public boolean isTv() {
            return MainActivity.this.isTv();
        }
    }
}
