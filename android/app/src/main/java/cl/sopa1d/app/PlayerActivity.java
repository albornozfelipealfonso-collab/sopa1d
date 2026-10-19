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
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

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

        // En móvil no hay mando: doble toque cambia el encuadre.
        final GestureDetector gestures = new GestureDetector(this,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onDoubleTap(MotionEvent e) {
                        cycleResize();
                        return true;
                    }
                });
        playerView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                gestures.onTouchEvent(event);
                return false; // PlayerView sigue recibiendo el toque
            }
        });
    }

    // ---------------------------------------------------------------- panel

    private void setupPanel() {
        List<Channel> all = ChannelStore.get();
        // El texto se arma al mostrar cada fila: así "▶ programa" está al día
        // y no se consulta la guía de miles de canales de golpe.
        ArrayAdapter<Channel> adapter = new ArrayAdapter<Channel>(this, R.layout.item_channel_epg, R.id.channelName, all) {
            @Override
            public View getView(int position, View convertView, android.view.ViewGroup parent) {
                TextView v = (TextView) super.getView(position, convertView, parent);
                Channel ch = getItem(position);
                android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
                // Nombre en una sola línea: si no, los largos tapaban el programa
                String name = ch.name.length() > 30 ? ch.name.substring(0, 29) + "…" : ch.name;
                sb.append(String.format(Locale.ROOT, "%3d   %s", position + 1, name));
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
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                index = position;
                togglePanel(false);
                openChannel();
            }
        });
    }

    private void togglePanel(boolean show) {
        panel.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            list.setSelection(index);
            list.requestFocus();
        } else {
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

        retries = 0;
        firstFrameShown = false;
        liveOffsetTuned = false;
        showOsd(ch);
        showStatus(getString(R.string.connecting));

        // Cada canal usa el decodificador que ya se sabe que le funciona.
        boolean wantSoftware = needsSoftwareVideo.contains(ch.url) || forceSoftwareForTesting();
        if (player != null && playerSoftwareVideo != wantSoftware) {
            playerView.setPlayer(null);
            player.release();
            player = null;
        }
        if (player == null) initPlayer(wantSoftware);

        player.setMediaSource(buildSource(ch.url, LIVE_TARGET_OFFSET_MS));
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
        needsSoftwareVideo.add(ch.url);
        showStatus(getString(R.string.software_fallback));
        openChannel();
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

        player.setMediaSource(buildSource(ch.url, target));
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

    /** Cicla el encuadre: útil cuando la señal deja bandas negras en TV. */
    private void cycleResize() {
        resizeIndex = (resizeIndex + 1) % RESIZE_MODES.length;
        playerView.setResizeMode(RESIZE_MODES[resizeIndex]);
        showBanner(RESIZE_NAMES[resizeIndex]);
    }

    private void switchBy(int delta) {
        if (ChannelStore.size() == 0) return;
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
        osdName.setText(ch.name);
        String cat = ch.category == null || ch.category.isEmpty() ? "" : ch.category + "   ·   ";
        osdInfo.setText(cat + "▲ ▼ canal   ·   OK lista   ·   ◀ programación   ·   0-9 ir al número");
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

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
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
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                togglePanel(true);
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
        super.onBackPressed();
    }

    // ------------------------------------------------------------ lifecycle

    /** singleTop: si ya está abierto, solo cambia de canal en vez de abrir otro. */
    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
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
    }

    @Override
    protected void onDestroy() {
        Epg.removeListener(onEpgUpdate);
        super.onDestroy();
    }

    @Override
    protected void onStop() {
        super.onStop();
        ui.removeCallbacksAndMessages(null);
        if (player != null) {
            player.release();
            player = null;
        }
    }
}
