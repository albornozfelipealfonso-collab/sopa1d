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
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.WebViewAssetLoader;

import java.io.ByteArrayInputStream;
import java.util.Arrays;
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
            "trackwilltrk.com", "poweradnetwork.com", "stpd.cloud", "tsyndicate.com"
    ));

    /** Fragmentos que delatan un dominio publicitario aunque no esté en la lista. */
    private static final String[] AD_PATTERNS = {
            "popunder", "popads", "/ads/", "adserver", "ad-delivery",
            "banner-ad", "/advert", "clickunder", "trafficstars"
    };

    /**
     * Desactiva los vectores de popup más comunes de estos embeds. El sandbox
     * del navegador no servía porque los proveedores se niegan a cargar dentro
     * de un iframe sandboxed; esto actúa desde dentro de la propia página.
     */
    private static final String ANTI_POPUP_JS =
            "(function(){"
          + "  window.open=function(){return null;};"
          + "  try{Object.defineProperty(window,'open',{value:function(){return null;},writable:false});}catch(e){}"
          + "  document.addEventListener('click',function(e){"
          + "    var a=e.target&&e.target.closest?e.target.closest('a[target=\"_blank\"]'):null;"
          + "    if(a){e.preventDefault();e.stopPropagation();}"
          + "  },true);"
          + "})();";

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
                if (!request.isForMainFrame()) return false;

                // Solo se restringe la navegación principal, para que ningún
                // anuncio se lleve la app fuera de index.html.
                return !"appassets.androidplatform.net".equals(request.getUrl().getHost());
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                view.evaluateJavascript(ANTI_POPUP_JS, null);
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
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
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
    }
}
