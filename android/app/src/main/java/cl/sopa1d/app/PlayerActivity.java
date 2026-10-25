package cl.sopa1d.app;

import android.app.Activity;
import android.content.pm.ApplicationInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ImageView;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Tracks;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.exoplayer.util.EventLogger;
import androidx.media3.exoplayer.hls.DefaultHlsExtractorFactory;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Reproductor de TV en vivo.
 *
 * Tres cosas que resuelve respecto de la versión web:
 *  - Decodifica MP2/AC-3 nativamente (sin ffmpeg ni servidor).
 *  - Buffer grande + reconexión automática, que es lo que el motor DVR del
 *    servidor hacía con ffmpeg para que la señal no se cortara.
 *  - Zapping con el mando, sin volver al menú.
 */
@OptIn(markerClass = UnstableApi.class)
public class PlayerActivity extends Activity {

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(SystemBars.tvScale(base));
    }

    private static final String TAG = "Sopa1D";

    public static final String EXTRA_INDEX = "index";

    // Estas señales publican ventanas en vivo muy cortas: TNT mantiene solo
    // 4 segmentos de ~5 s = 20 s en total. Pedir más buffer que la ventana
    // entera es imposible de satisfacer, y quedarse muy atrás del borde hace
    // que el servidor borre el segmento antes de terminar de reproducirlo.
    // Por eso todo va holgadamente por debajo de esos 20 s.
    private static final int MIN_BUFFER_MS = 8000;
    private static final int MAX_BUFFER_MS = 18000;
    private static final int BUFFER_FOR_PLAYBACK_MS = 2500;
    private static final int BUFFER_AFTER_REBUFFER_MS = 4000;

    /**
     * Retardo inicial respecto del directo, antes de conocer la ventana del
     * canal. Luego se ajusta una vez con liveOffsetFor() (ver onTimelineChanged).
     */
    private static final long LIVE_TARGET_OFFSET_MS = 8000;

    /**
     * Las ventanas varían mucho entre señales: TNT Chile 1 publica 4 segmentos
     * de 5 s (20 s) con PROGRAM-DATE-TIME y la lista llega con 3-9 s de
     * atraso; la Señal 2 publica 4 de 3 s (12 s) sin fecha. Un retardo fijo no
     * sirve para ambas: 8 s dejaba a Chile 1 pegado al último segmento conocido
     * (corte de ~1 s cada 5 s) y 14 s mandaba a la Señal 2 al inicio de la
     * ventana, donde los segmentos expiran antes de terminar de bajarlos.
     * Se apunta a la mitad de la ventana, sin pasar de 11 s antes del final.
     */
    private static long liveOffsetFor(long windowMs, boolean hasProgramDateTime) {
        long fromEnd = Math.min(windowMs / 2, 11000);
        // Con PROGRAM-DATE-TIME ExoPlayer mide contra el reloj, no contra el
        // final de la lista: se suma el atraso típico con que esta se publica.
        return hasProgramDateTime ? fromEnd + 3000 : fromEnd;
    }

    /** El retardo ya se ajustó a la ventana del canal actual. */
    private boolean liveOffsetTuned;

    private static final int MAX_RETRIES = 30;
    private static final long RETRY_DELAY_MS = 2000;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private ExoPlayer player;
    private PlayerView playerView;
    private TextView banner;
    private View osd;
    private ImageView osdLogo;
    private TextView osdNumber, osdName, osdInfo, osdClock, osdNow, osdNext;
    private ProgressBar osdProgress;
    private View schedulePanel;
    private TextView scheduleTitle;
    private ListView scheduleList;
    private TextView sendToTv;
    private boolean isTv;
    private final Runnable hideSendToTv = () -> sendToTv.setVisibility(View.GONE);
    private TextView status;
    private LinearLayout panel;
    private ListView list;

    private int index;
    private int retries;

    /** Tiempo máximo sin primer fotograma antes de pasar a decodificar por software. */
    private static final long FIRST_FRAME_TIMEOUT_MS = 12000;

    /** Canales cuyo vídeo no pudo el hardware en esta sesión. */
    private static final Set<String> needsSoftwareVideo = new HashSet<>();
    private boolean playerSoftwareVideo;
    private boolean firstFrameShown;

    /** Modos de encuadre: ajustar, recortar para llenar, y estirar. */
    private static final int[] RESIZE_MODES = {
            AspectRatioFrameLayout.RESIZE_MODE_FIT,
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
            AspectRatioFrameLayout.RESIZE_MODE_FILL
    };
    private static final String[] RESIZE_NAMES = {
            "Ajustar a pantalla", "Recortar (llenar)", "Estirar"
    };
    private int resizeIndex = 0;

    private final Runnable hideBanner = new Runnable() {
        @Override public void run() { banner.setVisibility(View.GONE); }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_player);

        playerView = findViewById(R.id.playerView);
        banner = findViewById(R.id.channelBanner);
        osd = findViewById(R.id.channelOsd);
        osdLogo = findViewById(R.id.osdLogo);
        osdNumber = findViewById(R.id.osdNumber);
        osdName = findViewById(R.id.osdName);
        osdInfo = findViewById(R.id.osdInfo);
        osdClock = findViewById(R.id.osdClock);
        osdNow = findViewById(R.id.osdNow);
        osdNext = findViewById(R.id.osdNext);
        osdProgress = findViewById(R.id.osdProgress);
        schedulePanel = findViewById(R.id.schedulePanel);
        scheduleTitle = findViewById(R.id.scheduleTitle);
        scheduleList = findViewById(R.id.scheduleList);
        Epg.init(this);
        Epg.addListener(onEpgUpdate);
        ChannelHealth.load(this);

        // Celular: botón para pasar el canal a la TV (aparece al tocar la pantalla)
        sendToTv = findViewById(R.id.liveSendToTv);
        android.app.UiModeManager uim = (android.app.UiModeManager) getSystemService(UI_MODE_SERVICE);
        isTv = uim != null && uim.getCurrentModeType() == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION;
        sendToTv.setOnClickListener(v -> {
            if (player != null) player.pause();
            TvLink.sendWithPicker(this, TvLink.liveCommand(ChannelStore.get(), index), this::finish);
        });
        // En vivo no hay nada que adelantar ni pausar con sentido: los
        // controles de ExoPlayer solo tapaban el rótulo del canal. En TV el
        // mando ya cambia de canal (▲ ▼) y abre la lista (OK); en el celular
        // se siguen mostrando al tocar la pantalla.
        playerView.setControllerAutoShow(false);
        status = findViewById(R.id.statusText);
        panel = findViewById(R.id.channelPanel);
        list = findViewById(R.id.channelList);

        index = getIntent().getIntExtra(EXTRA_INDEX, 0);
        hideSystemUi();
        setupPanel();
        setupMini();

        // En móvil no hay mando: doble toque cambia el encuadre y deslizar
        // cambia de canal (↑ ↓), abre la lista (←) o la programación (→).
        final GestureDetector gestures = new GestureDetector(this,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onDoubleTap(MotionEvent e) {
                        cycleResize();
                        return true;
                    }

                    @Override
                    public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                        if (e1 == null || panelOpen() || scheduleOpen()) return false;
                        float dx = e2.getX() - e1.getX(), dy = e2.getY() - e1.getY();
                        float min = 60 * getResources().getDisplayMetrics().density;
                        if (Math.abs(dy) > Math.abs(dx) && Math.abs(dy) > min) {
                            switchBy(dy < 0 ? 1 : -1); // como pasar de canal hacia arriba
                            return true;
                        }
                        if (Math.abs(dx) > min) {
                            if (dx < 0) togglePanel(true); else toggleSchedule(true);
                            return true;
                        }
                        return false;
                    }
                });

        if (!isTv) setupTouchControls();
        playerView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                gestures.onTouchEvent(event);
                if (event.getAction() == MotionEvent.ACTION_UP) {
                    Channel cur = ChannelStore.at(index);
                    if (cur != null && !panelOpen() && !scheduleOpen()) {
                        showOsd(cur); // rótulo, "Enviar a la TV" y ▲ ☰ 📅 ▼
                        showTouchControls();
                    }
                }
                // Se consume todo el gesto: si no, solo llega el primer toque y
                // no se detecta el deslizamiento. Los controles de ExoPlayer
                // (pausa, adelantar) no aplican a un canal en vivo.
                return true;
            }
        });
    }

    // ---------------------------------------------------------------- panel

    // --------------------------------------------------------- celular

    private View touchControls;
    private final Runnable hideTouchControls = () -> {
        if (touchControls != null) touchControls.setVisibility(View.GONE);
    };

    /** ▲ ☰ 📅 ▼ a la derecha: aparecen al tocar la pantalla y se ocultan solos. */
    private void setupTouchControls() {
        touchControls = findViewById(R.id.liveTouchControls);
        findViewById(R.id.touchUp).setOnClickListener(v -> { switchBy(1); showTouchControls(); });
        findViewById(R.id.touchDown).setOnClickListener(v -> { switchBy(-1); showTouchControls(); });
        findViewById(R.id.touchBack).setOnClickListener(v -> { previousOrSwap(); showTouchControls(); });
        findViewById(R.id.touchBack).setOnLongClickListener(v -> { toggleSplit(); showTouchControls(); return true; });
        findViewById(R.id.touchList).setOnClickListener(v -> togglePanel(true));
        findViewById(R.id.touchGuide).setOnClickListener(v -> toggleSchedule(true));
        findViewById(R.id.touchFav).setOnClickListener(v -> { toggleFavorite(ChannelStore.at(index)); showTouchControls(); });

        // Con miles de canales, en el celular se busca escribiendo
        EditText search = findViewById(R.id.channelSearch);
        search.setVisibility(View.VISIBLE);
        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable s) { filterChannels(s.toString()); }
        });
    }

    private void toggleFavorite(Channel ch) {
        if (ch == null) return;
        boolean fav = Favorites.toggle(this, ch.name, ch.url, ch.logo, ch.category, ch.country);
        Toast.makeText(this, fav ? "★ " + ch.name + " agregado a Mis canales" : "Quitado de Mis canales",
                Toast.LENGTH_SHORT).show();
        Channel cur = ChannelStore.at(index);
        if (cur == ch) showOsd(ch);
    }

    private void showTouchControls() {
        if (touchControls == null || panelOpen() || scheduleOpen() || Pip.active(this)) return;
        touchControls.setVisibility(View.VISIBLE);
        ui.removeCallbacks(hideTouchControls);
        ui.postDelayed(hideTouchControls, 5000);
    }

    /** Número de cada canal en la lista completa (la búsqueda muestra un subconjunto). */
    private final java.util.IdentityHashMap<Channel, Integer> channelNumber = new java.util.IdentityHashMap<>();
    private ArrayAdapter<Channel> channelAdapter;

    private void filterChannels(String query) {
        String q = Epg.norm(query);
        List<Channel> all = ChannelStore.get();
        List<Channel> shown = new ArrayList<>();
        for (Channel ch : all) {
            if (q.isEmpty() || Epg.norm(ch.name).contains(q)) shown.add(ch);
            if (shown.size() >= 400 && !q.isEmpty()) break;
        }
        channelAdapter.clear();
        // Sin texto, se vuelve a la categoría elegida (no a los miles de canales)
        channelAdapter.addAll(q.isEmpty() ? categoryChannels() : shown);
        list.setSelection(0);
    }

    // ------------------------------------------------------- categorías

    private static final String CAT_ALL = "Todos";
    private static final String CAT_FAVS = "★ Mis canales";
    private TextView categoryView;
    private final List<String> categories = new ArrayList<>();
    private int categoryIndex;

    /** Todos, Mis canales (si hay) y las categorías de la lista, de más a menos canales. */
    private void buildCategories() {
        java.util.Map<String, Integer> count = new java.util.HashMap<>();
        for (Channel ch : ChannelStore.get()) {
            if (ch.category == null || ch.category.isEmpty()) continue;
            Integer n = count.get(ch.category);
            count.put(ch.category, n == null ? 1 : n + 1);
        }
        List<String> sorted = new ArrayList<>(count.keySet());
        sorted.sort((a, b) -> count.get(b) - count.get(a));

        String previous = categories.isEmpty()
                ? getSharedPreferences("player", MODE_PRIVATE).getString("category", CAT_ALL)
                : categories.get(categoryIndex);
        categories.clear();
        categories.add(CAT_ALL);
        categories.add(CAT_FAVS);
        categories.addAll(sorted);
        categoryIndex = Math.max(0, categories.indexOf(previous));
    }

    private String currentCategory() {
        return categories.isEmpty() ? CAT_ALL : categories.get(categoryIndex);
    }

    /** Canales de la categoría elegida, en el orden de la lista completa. */
    private List<Channel> categoryChannels() {
        String cat = currentCategory();
        List<Channel> all = ChannelStore.get();
        if (CAT_ALL.equals(cat)) return new ArrayList<>(all);
        List<Channel> out = new ArrayList<>();
        java.util.Set<String> favs = CAT_FAVS.equals(cat) ? Favorites.urls(this) : null;
        for (Channel ch : all) {
            boolean in = favs != null ? favs.contains(ch.url) : cat.equals(ch.category);
            if (in) out.add(ch);
        }
        return out;
    }

    /** OK o ◀ ▶ sobre la categoría: pasar a la siguiente/anterior y filtrar la lista. */
    private void cycleCategory(int delta) {
        if (categories.isEmpty()) return;
        int n = categories.size();
        // "Mis canales" vacío se salta: mostraría una lista sin nada
        for (int i = 0; i < n; i++) {
            categoryIndex = ((categoryIndex + delta) % n + n) % n;
            if (!CAT_FAVS.equals(currentCategory()) || !categoryChannels().isEmpty()) break;
        }
        getSharedPreferences("player", MODE_PRIVATE).edit().putString("category", currentCategory()).apply();
        applyCategory();
    }

    private void applyCategory() {
        List<Channel> shown = categoryChannels();
        channelAdapter.clear();
        channelAdapter.addAll(shown);
        list.setSelection(0);
        categoryView.setText("📂  " + currentCategory() + "  ·  " + shown.size()
                + (isTv ? "   ◀ OK ▶" : "   (toca para cambiar)"));
    }

    private void setupPanel() {
        List<Channel> all = ChannelStore.get();
        channelNumber.clear();
        for (int i = 0; i < all.size(); i++) channelNumber.put(all.get(i), i);
        // El texto se arma al mostrar cada fila: así "▶ programa" está al día
        // y no se consulta la guía de miles de canales de golpe.
        ArrayAdapter<Channel> adapter = new ArrayAdapter<Channel>(this, R.layout.item_channel_epg, R.id.channelName,
                new ArrayList<>(all)) {
            @Override
            public View getView(int position, View convertView, android.view.ViewGroup parent) {
                TextView v = (TextView) super.getView(position, convertView, parent);
                Channel ch = getItem(position);
                Integer number = channelNumber.get(ch);
                android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
                // Nombre en una sola línea: si no, los largos tapaban el programa
                String name = ch.name.length() > 30 ? ch.name.substring(0, 29) + "…" : ch.name;
                boolean fav = Favorites.contains(getContext(), ch.url);
                sb.append(String.format(Locale.ROOT, "%3d %s %s", (number == null ? position : number) + 1,
                        fav ? "★" : "  ", name));
                Epg.Program now = Epg.nowNext(ch.name, ch.country)[0];
                if (now != null) {
                    int st = sb.length();
                    sb.append("\n        ▶ ").append(now.title);
                    sb.setSpan(new android.text.style.ForegroundColorSpan(0xB3FFFFFF), st, sb.length(), 0);
                    sb.setSpan(new android.text.style.RelativeSizeSpan(0.85f), st, sb.length(), 0);
                }
                v.setText(sb);
                return v;
            }
        };
        channelAdapter = adapter;
        list.setAdapter(adapter);

        // Selector de categoría arriba de la lista: OK y ◀ ▶ la cambian,
        // ▼ baja a los canales (búsqueda de foco normal).
        categoryView = findViewById(R.id.channelCategory);
        categoryView.setOnClickListener(v -> cycleCategory(1));
        categoryView.setOnKeyListener((v, keyCode, e) -> {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) { cycleCategory(1); return true; }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) { cycleCategory(-1); return true; }
            return false;
        });
        buildCategories();
        applyCategory();
        // Mantener OK (TV) o presionado (celular) sobre un canal: favorito
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            toggleFavorite(channelAdapter.getItem(position));
            channelAdapter.notifyDataSetChanged();
            return true;
        });
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                Integer number = channelNumber.get(channelAdapter.getItem(position));
                index = number != null ? number : position;
                togglePanel(false);
                openChannel();
            }
        });
    }

    private void togglePanel(boolean show) {
        panel.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            hideTouchControls.run();
            Integer pos = null;
            Channel cur = ChannelStore.at(index);
            for (int i = 0; channelAdapter != null && i < channelAdapter.getCount(); i++) {
                if (channelAdapter.getItem(i) == cur) { pos = i; break; }
            }
            list.setSelection(pos != null ? pos : 0);
            // En la TV el foco empieza en la categoría: OK la cambia y ▼ baja
            // a los canales (ya posicionados en el actual, si está en ella).
            if (isTv) categoryView.requestFocus(); else list.requestFocus();
        } else {
            // Al cerrar: búsqueda limpia y teclado fuera, para la próxima vez
            EditText search = findViewById(R.id.channelSearch);
            if (search.getText().length() > 0) search.setText("");
            ((android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                    .hideSoftInputFromWindow(search.getWindowToken(), 0);
            playerView.requestFocus();
        }
    }

    private boolean panelOpen() {
        return panel.getVisibility() == View.VISIBLE;
    }

    // --------------------------------------------------------------- player

    private void openChannel() {
        Channel ch = ChannelStore.at(index);
        if (ch == null) {
            showStatus("Sin canales");
            return;
        }

        rememberChannel(ch);
        showOsd(ch);
        showStatus(getString(R.string.connecting));

        activeUrl = ch.url;
        playActive();
    }

    /** Reproduce la señal del canal actual. */
    private void playActive() {
        retries = 0;
        firstFrameShown = false;
        liveOffsetTuned = false;

        // Cada señal usa el decodificador que ya se sabe que le funciona.
        boolean wantSoftware = needsSoftwareVideo.contains(activeUrl) || forceSoftwareForTesting();
        if (player != null && playerSoftwareVideo != wantSoftware) {
            playerView.setPlayer(null);
            player.release();
            player = null;
        }
        if (player == null) initPlayer(wantSoftware);

        player.setMediaSource(buildSource(activeUrl, LIVE_TARGET_OFFSET_MS));
        player.prepare();
        player.setPlayWhenReady(true);

        // Hay chips que no dan error: se quedan colgados sin pintar nada.
        ui.removeCallbacks(firstFrameWatchdog);
        if (!wantSoftware) ui.postDelayed(firstFrameWatchdog, FIRST_FRAME_TIMEOUT_MS);
    }

    private final Runnable firstFrameWatchdog = new Runnable() {
        @Override
        public void run() {
            if (!firstFrameShown && !playerSoftwareVideo) {
                switchToSoftwareVideo("sin imagen tras " + FIRST_FRAME_TIMEOUT_MS / 1000 + " s");
            }
        }
    };

    /**
     * El decodificador por hardware no pudo con este canal: se recuerda y se
     * reabre con FFmpeg. Más pesado para la CPU, pero tolera los errores de
     * slice que traen algunas señales IPTV.
     */
    private void switchToSoftwareVideo(String reason) {
        Channel ch = ChannelStore.at(index);
        if (ch == null) return;
        Log.w(TAG, "Vídeo por software para " + ch.name + ": " + reason);
        needsSoftwareVideo.add(activeUrl);
        showStatus(getString(R.string.software_fallback));
        playActive();
    }

    /**
     * Solo en debug: `adb shell setprop debug.sopa1d.swvideo 1` fuerza el
     * vídeo por software. El decodificador H.264 del emulador se cuelga con
     * estas señales y tumba el emulador entero, lo que impide probar el resto.
     */
    private boolean forceSoftwareForTesting() {
        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0) return false;
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            return "1".equals(sp.getMethod("get", String.class).invoke(null, "debug.sopa1d.swvideo"));
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isDecoderError(PlaybackException e) {
        return e.errorCode >= PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
                && e.errorCode <= PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED;
    }

    private void initPlayer(boolean softwareVideo) {
        playerSoftwareVideo = softwareVideo;
        // Buffer amplio: la fuente IPTV entrega a ráfagas y se queda corta a ratos.
        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                        MIN_BUFFER_MS,
                        MAX_BUFFER_MS,
                        BUFFER_FOR_PLAYBACK_MS,
                        BUFFER_AFTER_REBUFFER_MS)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build();

        player = new ExoPlayer.Builder(this, new MixedRenderersFactory(this, softwareVideo))
                .setLoadControl(loadControl)
                // Señales con varias calidades: arrancar prudente y bajar rápido
                .setBandwidthMeter(Quality.bandwidthMeter(this))
                .setTrackSelector(Quality.trackSelector(this))
                .build();

        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                Log.i(TAG, "Estado: " + new String[]{"?", "IDLE", "BUFFERING", "READY", "ENDED"}[state]);
                if (state == Player.STATE_READY) {
                    retries = 0;
                    hideStatus();
                } else if (state == Player.STATE_BUFFERING) {
                    showStatus(getString(R.string.connecting));
                }
            }

            @Override
            public void onTimelineChanged(Timeline timeline, int reason) {
                if (!liveOffsetTuned) tuneLiveOffset(timeline);
            }

            @Override
            public void onRenderedFirstFrame() {
                firstFrameShown = true;
                ui.removeCallbacks(firstFrameWatchdog);
                Log.i(TAG, "Primer fotograma mostrado");
            }

            @Override
            public void onTracksChanged(Tracks tracks) {
                for (Tracks.Group g : tracks.getGroups()) {
                    for (int i = 0; i < g.length; i++) {
                        Format f = g.getTrackFormat(i);
                        Log.i(TAG, "Pista " + f.sampleMimeType
                                + " soportada=" + g.isTrackSupported(i)
                                + " elegida=" + g.isTrackSelected(i));
                    }
                }
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                if (isDecoderError(error) && !playerSoftwareVideo) {
                    Log.w(TAG, "Fallo del decodificador por hardware", error);
                    switchToSoftwareVideo(error.getErrorCodeName());
                    return;
                }
                recoverFrom(error);
            }
        });

        // Deja constancia de qué decodificador se usa: el de vídeo debe ser
        // de hardware (c2./OMX.), el de audio MP2 el de FFmpeg.
        player.addAnalyticsListener(new AnalyticsListener() {
            @Override
            public void onVideoDecoderInitialized(EventTime t, String name, long a, long b) {
                Log.i(TAG, "Decodificador de vídeo: " + name);
            }

            @Override
            public void onAudioDecoderInitialized(EventTime t, String name, long a, long b) {
                Log.i(TAG, "Decodificador de audio: " + name);
            }
        });

        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            // Registro detallado (descargas, búferes, estados) solo en debug.
            player.addAnalyticsListener(new EventLogger("Sopa1DExo"));
        }

        playerView.setPlayer(player);
        playerView.requestFocus();
    }

    /**
     * Con la primera playlist ya se sabe cuánto dura la ventana del canal. Si
     * el retardo ideal difiere del inicial, se reabre la fuente una sola vez
     * con el correcto: HlsMediaSource no admite cambiarlo en caliente y
     * replaceMediaItem() perdería el User-Agent y las banderas del extractor.
     */
    private void tuneLiveOffset(Timeline timeline) {
        if (timeline.isEmpty() || player == null) return;
        Timeline.Window w = timeline.getWindow(player.getCurrentMediaItemIndex(), new Timeline.Window());
        if (!w.isLive() || w.getDurationMs() == C.TIME_UNSET) return;
        liveOffsetTuned = true;

        long target = liveOffsetFor(w.getDurationMs(), w.windowStartTimeMs != C.TIME_UNSET);
        Channel ch = ChannelStore.at(index);
        Log.i(TAG, "Ventana en vivo " + w.getDurationMs() + " ms"
                + (w.windowStartTimeMs != C.TIME_UNSET ? " con fecha" : " sin fecha")
                + " -> retardo " + target + " ms");
        if (ch == null || Math.abs(target - LIVE_TARGET_OFFSET_MS) < 1500) return;

        player.setMediaSource(buildSource(activeUrl, target));
        player.prepare();
    }

    private MediaSource buildSource(String url, long targetOffsetMs) {
        // Varios orígenes IPTV rechazan peticiones sin User-Agent de navegador.
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent("Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 "
                        + "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36")
                .setConnectTimeoutMs(20000)
                .setReadTimeoutMs(20000)
                .setAllowCrossProtocolRedirects(true);

        MediaItem item = new MediaItem.Builder()
                .setUri(url)
                .setLiveConfiguration(new MediaItem.LiveConfiguration.Builder()
                        .setTargetOffsetMs(targetOffsetMs)
                        // Ajuste fino de velocidad para absorber deriva sin saltos audibles
                        .setMinPlaybackSpeed(0.96f)
                        .setMaxPlaybackSpeed(1.04f)
                        .build())
                .build();

        // Sin preparación "chunkless": con ella ExoPlayer arma las pistas solo
        // a partir del atributo CODECS de la playlist, y estas señales declaran
        // únicamente el vídeo (TNT: CODECS="avc1.640029") aunque el .ts trae
        // audio MP2. Resultado: el audio ni siquiera aparecía como pista.
        // FLAG_ALLOW_NON_IDR_KEYFRAMES: las señales de TV broadcast (TNT
        // incluida) no envían fotogramas IDR, solo fotogramas I con SPS/PPS.
        // Por defecto ExoPlayer descarta todo hasta ver un IDR, así que nunca
        // llegaba nada al decodificador y se quedaba "cargando" para siempre.
        // Medido en TNT Chile 1: 0 IDR en 12 s, 26 puntos de arranque I.
        return new HlsMediaSource.Factory(new TolerantDataSource.Factory(http))
                .setExtractorFactory(new DefaultHlsExtractorFactory(
                        DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES, true))
                .setAllowChunklessPreparation(false)
                .createMediaSource(item);
    }

    /**
     * Reconexión automática. Es el equivalente a lo que hacía ffmpeg en el
     * servidor con -reconnect: la fuente se cae a ratos y hay que insistir
     * en vez de rendirse al primer fallo.
     */
    private void recoverFrom(PlaybackException error) {
        if (player == null) return;

        // Sin esto el fallo real queda enterrado tras los reintentos.
        Log.w(TAG, "Error de reproducción: " + error.getErrorCodeName()
                + " (" + error.errorCode + ")", error);

        if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW
                || error.errorCode == PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE) {
            // Nos quedamos atrás del directo (o el servidor ya borró el
            // segmento que se estaba bajando): volver al borde sin esperar.
            player.seekToDefaultPosition();
            player.prepare();
            return;
        }

        if (retries >= MAX_RETRIES) {
            showStatus("No se pudo reproducir: " + error.getErrorCodeName());
            return;
        }

        retries++;
        showStatus(getString(R.string.reconnecting) + " (" + retries + ")");
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (player == null) return;
                player.seekToDefaultPosition();
                player.prepare();
                player.setPlayWhenReady(true);
            }
        }, RETRY_DELAY_MS);
    }

    /** Señal que se está reproduciendo (la URL del canal actual). */
    private String activeUrl;

    /** Cicla el encuadre: útil cuando la señal deja bandas negras en TV. */
    private void cycleResize() {
        resizeIndex = (resizeIndex + 1) % RESIZE_MODES.length;
        playerView.setResizeMode(RESIZE_MODES[resizeIndex]);
        showBanner(RESIZE_NAMES[resizeIndex]);
    }

    /**
     * Canal anterior (⇄): se guarda por URL para que siga sirviendo aunque la
     * lista cambie (favoritos, búsqueda, otra sección). Estático: se recuerda
     * también al salir del reproductor y volver a entrar.
     */
    private static String currentUrl, previousUrl;

    private void rememberChannel(Channel ch) {
        if (ch.url.equals(currentUrl)) return;
        previousUrl = currentUrl;
        currentUrl = ch.url;
    }

    /** Posición del canal con esa URL en la lista actual, o -1. */
    private static int indexOfUrl(String url) {
        List<Channel> all = ChannelStore.get();
        for (int i = 0; url != null && i < all.size(); i++) {
            if (all.get(i).url.equals(url)) return i;
        }
        return -1;
    }

    /** Salta al último canal visto; repetirlo vuelve al otro (dos partidos). */
    private void switchToPrevious() {
        int i = indexOfUrl(previousUrl);
        if (i < 0) {
            showBanner("Aún no hay canal anterior");
            return;
        }
        index = i;
        openChannel();
    }

    /** ▶ / ⇄: con dos canales en pantalla los intercambia; si no, va al anterior. */
    private void previousOrSwap() {
        if (miniUrl != null) swapMini();
        else switchToPrevious();
    }

    // ------------------------------------------------------ pantalla dividida

    /**
     * Segundo canal en un recuadro, sin sonido, para seguir dos partidos a la
     * vez. Arranca con el canal anterior; ▶ (o tocarlo, en el celular)
     * intercambia el grande con el chico. Pide la calidad más baja: se ve
     * chico y así gasta menos datos y decodificador.
     */
    private View miniFrame;
    private PlayerView miniView;
    private TextView miniLabel;
    private ExoPlayer miniPlayer;
    private boolean miniSoftwareVideo;
    private String miniUrl;                 // null = un solo canal

    private void setupMini() {
        miniFrame = findViewById(R.id.miniFrame);
        miniView = findViewById(R.id.miniView);
        miniLabel = findViewById(R.id.miniLabel);
        miniFrame.setOnClickListener(v -> swapMini());
        // Un tercio del ancho de pantalla, también al girar el celular
        View root = findViewById(android.R.id.content);
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol) v.post(() -> sizeMini(r - l));
        });
    }

    private void sizeMini(int screenWidth) {
        android.widget.FrameLayout.LayoutParams lp =
                (android.widget.FrameLayout.LayoutParams) miniFrame.getLayoutParams();
        lp.width = Math.round(screenWidth * 0.32f);
        lp.height = lp.width * 9 / 16;
        // En el celular arriba están los botones táctiles: el recuadro va debajo
        float density = getResources().getDisplayMetrics().density;
        lp.topMargin = Math.round((isTv ? 32 : 96) * density);
        miniFrame.setLayoutParams(lp);
    }

    /** Mantener ▶ (o ⇄): abre o cierra el segundo canal. */
    private void toggleSplit() {
        if (miniUrl != null) {
            closeMini();
            showBanner("Un solo canal");
            return;
        }
        if (indexOfUrl(previousUrl) < 0) {
            showBanner("Primero mira otro canal: el recuadro muestra el anterior");
            return;
        }
        openMini(previousUrl);
        showBanner(isTv ? "▶ intercambia   ·   mantén ▶ o Atrás para cerrar"
                : "Toca el recuadro para intercambiar   ·   mantén ⇄ para cerrar");
    }

    private void openMini(String url) {
        int i = indexOfUrl(url);
        if (i < 0) return;
        miniUrl = url;
        boolean software = miniSoftwareVideo || forceSoftwareForTesting() || needsSoftwareVideo.contains(url);
        if (miniPlayer != null && software != miniSoftwareVideo) releaseMini();
        if (miniPlayer == null) {
            miniSoftwareVideo = software;
            androidx.media3.exoplayer.trackselection.DefaultTrackSelector sel =
                    new androidx.media3.exoplayer.trackselection.DefaultTrackSelector(this);
            sel.setParameters(sel.buildUponParameters()
                    .setMaxVideoSize(854, 480)
                    .setForceLowestBitrate(true)
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true));
            miniPlayer = new ExoPlayer.Builder(this, new MixedRenderersFactory(this, software))
                    .setTrackSelector(sel)
                    .build();
            miniPlayer.setVolume(0f);
            miniPlayer.addListener(new Player.Listener() {
                @Override
                public void onPlayerError(PlaybackException e) {
                    Log.w(TAG, "Recuadro: " + e.getErrorCodeName());
                    if (miniUrl == null) return;
                    if (isDecoderError(e)) {
                        // Equipos con un solo decodificador por hardware: el
                        // chico pasa a FFmpeg; si tampoco puede, se cierra.
                        if (miniSoftwareVideo) {
                            closeMini();
                            showBanner("Este equipo no puede mostrar dos canales a la vez");
                        } else {
                            miniSoftwareVideo = true;
                            openMini(miniUrl);
                        }
                        return;
                    }
                    ui.postDelayed(() -> { if (miniUrl != null) openMini(miniUrl); }, 3000);
                }
            });
            miniView.setPlayer(miniPlayer);
        }
        miniPlayer.setMediaSource(buildSource(url, LIVE_TARGET_OFFSET_MS));
        miniPlayer.prepare();
        miniPlayer.setPlayWhenReady(true);
        miniLabel.setText(ChannelStore.at(i).name);
        if (miniFrame.getLayoutParams().width <= 0 || miniFrame.getWidth() == 0) {
            sizeMini(findViewById(android.R.id.content).getWidth());
        }
        miniFrame.setVisibility(View.VISIBLE);
    }

    /** El grande pasa al recuadro y el del recuadro a pantalla completa. */
    private void swapMini() {
        if (miniUrl == null || currentUrl == null) return;
        int i = indexOfUrl(miniUrl);
        if (i < 0) {
            closeMini();
            return;
        }
        String big = currentUrl;
        index = i;
        openChannel();
        openMini(big);
    }

    private void closeMini() {
        miniUrl = null;
        miniSoftwareVideo = false;
        releaseMini();
        if (miniFrame != null) miniFrame.setVisibility(View.GONE);
    }

    private void releaseMini() {
        if (miniPlayer == null) return;
        miniView.setPlayer(null);
        miniPlayer.release();
        miniPlayer = null;
    }

    private void switchBy(int delta) {
        if (ChannelStore.size() == 0) return;
        // Con una categoría elegida, ▲ ▼ zapean solo dentro de ella
        if (!CAT_ALL.equals(currentCategory())) {
            List<Channel> cat = categoryChannels();
            Channel cur = ChannelStore.at(index);
            int pos = cat.indexOf(cur);
            if (!cat.isEmpty()) {
                // Si el canal actual no es de la categoría (se eligió otra
                // mientras se veía este), ▲ entra por el primero y ▼ por el último.
                Channel next = pos < 0
                        ? cat.get(delta > 0 ? 0 : cat.size() - 1)
                        : cat.get(((pos + delta) % cat.size() + cat.size()) % cat.size());
                List<Channel> all = ChannelStore.get();
                for (int i = 0; i < all.size(); i++) {
                    if (all.get(i) == next) { index = i; openChannel(); return; }
                }
            }
        }
        index = index + delta;
        int n = ChannelStore.size();
        index = ((index % n) + n) % n;
        openChannel();
    }

    // ------------------------------------------------------------------ ui

    /**
     * Rótulo de canal estilo TV: número grande, logo, nombre, categoría y
     * hora, en una franja abajo que se oculta sola.
     */
    private void showOsd(Channel ch) {
        osdNumber.setText(String.valueOf(index + 1));
        osdName.setText((Favorites.contains(this, ch.url) ? "★ " : "") + ch.name);
        View favBtn = findViewById(R.id.touchFav);
        if (favBtn != null) ((TextView) favBtn).setText(Favorites.contains(this, ch.url) ? "★  Favorito" : "☆  Favorito");
        String cat = ch.category == null || ch.category.isEmpty() ? "" : ch.category + "   ·   ";
        osdInfo.setText(cat + (isTv
                ? "▲ ▼ canal   ·   OK lista   ·   ◀ programación   ·   ▶ anterior (mantén: dos canales)   ·   0-9 ir al número"
                : "Desliza ↑ ↓ para cambiar de canal   ·   ← lista   ·   → guía   ·   mantén ⇄: dos canales"));
        fillOsdGuide(ch);
        osdClock.setText(new java.text.SimpleDateFormat("HH:mm", Locale.ROOT).format(new java.util.Date()));
        if (ch.logo != null && !ch.logo.isEmpty()) {
            osdLogo.setVisibility(View.VISIBLE);
            com.bumptech.glide.Glide.with(this).load(ch.logo)
                    .listener(new com.bumptech.glide.request.RequestListener<android.graphics.drawable.Drawable>() {
                        @Override
                        public boolean onLoadFailed(com.bumptech.glide.load.engine.GlideException e, Object model,
                                                    com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable> target,
                                                    boolean isFirstResource) {
                            osdLogo.setVisibility(View.GONE); // logo roto: mejor nada que un recuadro vacío
                            return true;
                        }

                        @Override
                        public boolean onResourceReady(android.graphics.drawable.Drawable resource, Object model,
                                                       com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable> target,
                                                       com.bumptech.glide.load.DataSource dataSource, boolean isFirstResource) {
                            return false;
                        }
                    })
                    .into(osdLogo);
        } else {
            osdLogo.setVisibility(View.GONE);
        }
        if (!isTv) {
            sendToTv.setVisibility(View.VISIBLE);
            ui.removeCallbacks(hideSendToTv);
            ui.postDelayed(hideSendToTv, 5000);
        }
        osd.setVisibility(View.VISIBLE);
        osd.setAlpha(0f);
        osd.animate().alpha(1f).setDuration(200).start();
        ui.removeCallbacks(hideOsd);
        ui.postDelayed(hideOsd, 5000);
    }

    /** "AHORA 21:00 – 23:00  Programa" con su avance y "DESPUÉS 23:00  Programa". */
    private void fillOsdGuide(Channel ch) {
        Epg.Program[] nn = Epg.nowNext(ch.name, ch.country);
        long nowMs = System.currentTimeMillis();
        if (nn[0] != null) {
            osdNow.setText(guideLine("AHORA  ", 0xFFE50914, nn[0].hours() + "   ", nn[0].title));
            osdNow.setVisibility(View.VISIBLE);
            osdProgress.setProgress(Math.round(nn[0].progress(nowMs) * 1000));
            osdProgress.setVisibility(View.VISIBLE);
        } else {
            osdNow.setVisibility(View.GONE);
            osdProgress.setVisibility(View.GONE);
        }
        if (nn[1] != null) {
            String at = new java.text.SimpleDateFormat("HH:mm", Locale.ROOT).format(nn[1].start) + "   ";
            osdNext.setText(guideLine("DESPUÉS  ", 0xFFB3B3B3, at, nn[1].title));
            osdNext.setVisibility(View.VISIBLE);
        } else {
            osdNext.setVisibility(View.GONE);
        }
    }

    private static CharSequence guideLine(String label, int color, String hours, String title) {
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder(label);
        sb.setSpan(new android.text.style.ForegroundColorSpan(color), 0, label.length(), 0);
        sb.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, label.length(), 0);
        sb.append(hours);
        int st = sb.length();
        sb.append(title);
        sb.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), st, sb.length(), 0);
        return sb;
    }

    // ------------------------------------------------------- programación

    private void toggleSchedule(boolean show) {
        if (show) hideTouchControls.run();
        if (!show) {
            schedulePanel.setVisibility(View.GONE);
            playerView.requestFocus();
            return;
        }
        Channel ch = ChannelStore.at(index);
        if (ch == null) return;
        List<Epg.Program> progs = Epg.upcoming(ch.name, ch.country, 40);
        if (progs.isEmpty()) {
            showBanner("Sin programación disponible para este canal");
            return;
        }
        scheduleTitle.setText(ch.name);
        final long nowMs = System.currentTimeMillis();
        java.text.SimpleDateFormat day = new java.text.SimpleDateFormat("EEE d", new Locale("es"));
        String today = day.format(nowMs);
        List<CharSequence> rows = new ArrayList<>();
        for (Epg.Program p : progs) {
            boolean live = p.start <= nowMs && nowMs < p.stop;
            String d = day.format(p.start);
            String hours = (d.equals(today) ? "" : d + "  ") + p.hours() + "   ";
            android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
            if (live) {
                sb.append("● AHORA  ");
                // Amarillo: la fila seleccionada se resalta en rojo y el rojo no se leía
                sb.setSpan(new android.text.style.ForegroundColorSpan(0xFFF5C518), 0, sb.length(), 0);
                sb.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, sb.length(), 0);
            }
            int st = sb.length();
            sb.append(hours);
            sb.setSpan(new android.text.style.ForegroundColorSpan(0xB3FFFFFF), st, sb.length(), 0);
            st = sb.length();
            sb.append(p.title);
            sb.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), st, sb.length(), 0);
            if (live && !p.desc.isEmpty()) {
                st = sb.length();
                sb.append("\n").append(p.desc);
                sb.setSpan(new android.text.style.ForegroundColorSpan(0xB3FFFFFF), st, sb.length(), 0);
                sb.setSpan(new android.text.style.RelativeSizeSpan(0.85f), st, sb.length(), 0);
            }
            rows.add(sb);
        }
        scheduleList.setAdapter(new ArrayAdapter<>(this, R.layout.item_schedule, R.id.scheduleText, rows));
        schedulePanel.setVisibility(View.VISIBLE);
        scheduleList.requestFocus();
        scheduleList.setSelection(0);
    }

    private boolean scheduleOpen() {
        return schedulePanel.getVisibility() == View.VISIBLE;
    }

    /** Llegó programación nueva: se actualiza lo que esté a la vista. */
    private final Runnable onEpgUpdate = () -> {
        Channel cur = ChannelStore.at(index);
        if (cur != null && osd.getVisibility() == View.VISIBLE && PlayerActivity.this.typedNumber.length() == 0) fillOsdGuide(cur);
        if (list.getAdapter() instanceof ArrayAdapter) ((ArrayAdapter<?>) list.getAdapter()).notifyDataSetChanged();
    };

    private final Runnable hideOsd = new Runnable() {
        @Override public void run() {
            osd.animate().alpha(0f).setDuration(300).withEndAction(() -> osd.setVisibility(View.GONE)).start();
        }
    };

    /** Número tecleado con el control: se salta al canal tras una pausa. */
    private final StringBuilder typedNumber = new StringBuilder();
    private final Runnable jumpToTyped = new Runnable() {
        @Override public void run() {
            if (typedNumber.length() == 0) return;
            int n = Integer.parseInt(typedNumber.toString());
            typedNumber.setLength(0);
            if (n >= 1 && n <= ChannelStore.size()) {
                index = n - 1;
                openChannel();
            } else {
                showBanner("No existe el canal " + n);
            }
        }
    };

    private void typeDigit(int d) {
        if (typedNumber.length() >= 4) typedNumber.setLength(0);
        typedNumber.append(d);
        osdNumber.setText(typedNumber + "_");
        osdName.setText("Ir al canal…");
        osdInfo.setText("");
        osdNow.setVisibility(View.GONE);
        osdNext.setVisibility(View.GONE);
        osdProgress.setVisibility(View.GONE);
        osdLogo.setVisibility(View.GONE);
        osd.setVisibility(View.VISIBLE);
        osd.setAlpha(1f);
        ui.removeCallbacks(hideOsd);
        ui.removeCallbacks(jumpToTyped);
        ui.postDelayed(jumpToTyped, 1500);
    }

    private void showBanner(String text) {
        banner.setText(text);
        banner.setVisibility(View.VISIBLE);
        ui.removeCallbacks(hideBanner);
        ui.postDelayed(hideBanner, 3500);
    }

    private void showStatus(String text) {
        status.setText(text);
        status.setVisibility(View.VISIBLE);
    }

    private void hideStatus() {
        status.setVisibility(View.GONE);
    }

    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    // ---------------------------------------------------------------- mando

    /** ▶ apretado aquí (no en la programación ni la lista) y si ya se trató como "mantener". */
    private boolean rightDown, rightHeld;

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // ▶: tocar = canal anterior (o intercambiar); mantener = dos canales.
        // Se decide al soltar o, si se mantiene, al primer auto-repetir.
        if (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT && !scheduleOpen() && !panelOpen()) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0) {
                    rightDown = true;
                    rightHeld = false;
                } else if (rightDown && !rightHeld) {
                    rightHeld = true;
                    toggleSplit();
                }
                return true;
            }
            if (event.getAction() == KeyEvent.ACTION_UP && rightDown) {
                rightDown = false;
                if (!rightHeld) previousOrSwap();
                return true;
            }
        }
        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            return super.dispatchKeyEvent(event);
        }

        int code = event.getKeyCode();

        if (scheduleOpen()) {
            if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_DPAD_LEFT
                    || code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_GUIDE
                    || code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
                toggleSchedule(false);
                return true;
            }
            return super.dispatchKeyEvent(event); // ▲ ▼ recorren la programación
        }

        if (panelOpen()) {
            if (code == KeyEvent.KEYCODE_BACK) {
                togglePanel(false);
                return true;
            }
            return super.dispatchKeyEvent(event); // la lista maneja el resto
        }

        if (code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9) {
            typeDigit(code - KeyEvent.KEYCODE_0);
            return true;
        }
        if (code >= KeyEvent.KEYCODE_NUMPAD_0 && code <= KeyEvent.KEYCODE_NUMPAD_9) {
            typeDigit(code - KeyEvent.KEYCODE_NUMPAD_0);
            return true;
        }
        if (code == KeyEvent.KEYCODE_INFO) {
            Channel cur = ChannelStore.at(index);
            if (cur != null) showOsd(cur);
            return true;
        }

        switch (code) {
            case KeyEvent.KEYCODE_CHANNEL_UP:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_PAGE_UP:
                switchBy(1);
                return true;

            case KeyEvent.KEYCODE_CHANNEL_DOWN:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_PAGE_DOWN:
                switchBy(-1);
                return true;

            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_MENU:
                togglePanel(true);
                return true;

            // Volver al último canal visto (alternar entre dos partidos)
            case KeyEvent.KEYCODE_LAST_CHANNEL:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                previousOrSwap();
                return true;

            // Programación del canal
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_GUIDE:
                toggleSchedule(true);
                return true;

            // Agrandar / encuadrar la imagen (en el celular: doble toque)
            case KeyEvent.KEYCODE_TV_ZOOM_MODE:
            case KeyEvent.KEYCODE_PROG_YELLOW:
            case KeyEvent.KEYCODE_ZOOM_IN:
            case KeyEvent.KEYCODE_BUTTON_L1:
                cycleResize();
                return true;
        }

        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onBackPressed() {
        if (scheduleOpen()) {
            toggleSchedule(false);
            return;
        }
        if (panelOpen()) {
            togglePanel(false);
            return;
        }
        if (miniUrl != null) {
            closeMini();
            return;
        }
        super.onBackPressed();
    }

    // ------------------------------------------------------------ lifecycle

    /** singleTop: si ya está abierto, solo cambia de canal en vez de abrir otro. */
    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // Puede llegar con otra lista de canales (ChannelStore ya se cambió):
        // el panel y su numeración se rehacen, o el buscador elegiría mal.
        setupPanel();
        int nuevo = intent.getIntExtra(EXTRA_INDEX, index);
        if (nuevo != index || player == null) {
            index = nuevo;
            openChannel();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        openChannel();
        if (miniUrl != null) openMini(miniUrl);
    }

    @Override
    protected void onDestroy() {
        Epg.removeListener(onEpgUpdate);
        super.onDestroy();
    }

    /** Inicio mientras se ve un canal: sigue en un cuadrito flotante (celular). */
    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        Pip.enter(this, player != null && player.isPlaying());
    }

    @Override
    public void onPictureInPictureModeChanged(boolean inPip, android.content.res.Configuration cfg) {
        super.onPictureInPictureModeChanged(inPip, cfg);
        if (inPip) {
            // En el cuadrito solo el vídeo
            ui.removeCallbacks(hideOsd);
            osd.setVisibility(View.GONE);
            panel.setVisibility(View.GONE);
            schedulePanel.setVisibility(View.GONE);
            if (touchControls != null) touchControls.setVisibility(View.GONE);
            sendToTv.setVisibility(View.GONE);
            banner.setVisibility(View.GONE);
            // En el cuadrito flotante no cabe un segundo canal
            miniFrame.setVisibility(View.GONE);
            if (miniPlayer != null) miniPlayer.pause();
        } else if (miniUrl != null) {
            miniFrame.setVisibility(View.VISIBLE);
            if (miniPlayer != null) miniPlayer.play();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        ui.removeCallbacksAndMessages(null);
        if (player != null) {
            player.release();
            player = null;
        }
        releaseMini(); // miniUrl se conserva: al volver se reabre
    }
}
