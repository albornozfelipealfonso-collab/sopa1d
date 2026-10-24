package cl.sopa1d.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.OptIn;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.ui.PlayerView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Reproductor de películas y series sin publicidad: el vídeo llega directo
 * del CDN (StreamResolver) y nunca se carga el reproductor web del proveedor.
 *
 * Mando: OK pausa, ◀ ▶ retroceden/adelantan 10 s, ▲ o MENÚ abre idioma y
 * servidor, ATRÁS sale. Si un servidor falla se pasa solo al siguiente; al
 * terminar un episodio empieza el próximo.
 */
@OptIn(markerClass = UnstableApi.class)
public class VodPlayerActivity extends Activity {

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(SystemBars.tvScale(base));
    }

    private static final String TAG = "Sopa1D";

    static final String EXTRA_ITEM = "item";
    static final String EXTRA_SEASON = "season";
    static final String EXTRA_EPISODE = "episode";
    /** Episodios por temporada: índice = número de temporada. */
    static final String EXTRA_EPISODE_COUNTS = "episodeCounts";
    /** Minuto exacto donde empezar (lo manda el celular); -1 = donde quedó. */
    static final String EXTRA_POSITION = "position";

    private static final long SEEK_STEP_MS = 10000;
    /** Salto por defecto de "Saltar intro" (la intro típica dura 60-90 s). */
    private static final long INTRO_SKIP_MS = 85000;
    /** "Saltar intro" solo se ofrece en los primeros minutos del episodio. */
    private static final long INTRO_WINDOW_MS = 240000;
    /** "Siguiente episodio" aparece cuando quedan estos milisegundos. */
    private static final long NEXT_AT_REMAINING_MS = 30000;
    private static final int NEXT_COUNTDOWN_S = 10;
    /** Cuántos servidores se intentan resolver a la vez al empezar. */
    private static final int PARALLEL_RESOLVE = 5;

    static void start(Context c, CatalogItem item, int season, int episode, int[] episodeCounts) {
        Intent i = new Intent(c, VodPlayerActivity.class);
        i.putExtra(EXTRA_ITEM, item);
        i.putExtra(EXTRA_SEASON, season);
        i.putExtra(EXTRA_EPISODE, episode);
        i.putExtra(EXTRA_EPISODE_COUNTS, episodeCounts);
        c.startActivity(i);
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newFixedThreadPool(PARALLEL_RESOLVE);

    private PlayerView playerView;
    private ExoPlayer player;
    private TextView title, subtitle, status;
    /** Subtítulos de OpenSubtitles del capítulo openSubsKey (null = no hay). */
    private OpenSubs.Result openSubs;
    private String openSubsKey;
    private View statusBox, topBar, panel;
    private ListView serverList;

    private CatalogItem item;
    private int season, episode;
    private int[] episodeCounts;

    private List<StreamResolver.Server> servers = new ArrayList<>();
    /** Servidor que está sonando ahora (índice en servers). */
    private int current = -1;
    /** Para no reanudar dos veces ni guardar progreso de otro episodio. */
    private int loadGeneration;
    private boolean destroyed;

    private View skipIntro, nextCard;
    private TextView nextTitle, nextCountdown;
    /** Por episodio: ya se saltó la intro / ya se mostró y canceló el siguiente. */
    private boolean introDone, nextDismissed;
    /** El título no tenía servidores: OK / toque reintenta. */
    private boolean unavailable;
    private int countdown = -1;

    /** Cada segundo: decide si mostrar "Saltar intro" o "Siguiente episodio". */
    private final Runnable episodeTicker = new Runnable() {
        @Override
        public void run() {
            updateEpisodeOverlays();
            ui.postDelayed(this, 1000);
        }
    };

    private final Runnable saveProgress = new Runnable() {
        @Override
        public void run() {
            persistPosition();
            ui.postDelayed(this, 10000);
        }
    };

    private final Runnable hideTopBar = new Runnable() {
        @Override
        public void run() {
            if (!playerView.isControllerFullyVisible()) topBar.setVisibility(View.GONE);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_vod_player);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

        playerView = findViewById(R.id.vodPlayerView);
        title = findViewById(R.id.vodTitle);
        subtitle = findViewById(R.id.vodSubtitle);
        status = findViewById(R.id.vodStatus);
        statusBox = findViewById(R.id.vodStatusBox);
        topBar = findViewById(R.id.vodTopBar);
        panel = findViewById(R.id.vodPanel);
        serverList = findViewById(R.id.vodServerList);

        item = (CatalogItem) getIntent().getSerializableExtra(EXTRA_ITEM);
        season = getIntent().getIntExtra(EXTRA_SEASON, 1);
        episode = getIntent().getIntExtra(EXTRA_EPISODE, 1);
        episodeCounts = getIntent().getIntArrayExtra(EXTRA_EPISODE_COUNTS);
        if (item == null) {
            finish();
            return;
        }

        playerView.setControllerVisibilityListener((PlayerView.ControllerVisibilityListener) visibility -> {
            if (visibility == View.VISIBLE) {
                ui.removeCallbacks(hideTopBar);
                topBar.setVisibility(View.VISIBLE);
            } else {
                ui.postDelayed(hideTopBar, 300);
            }
        });
        serverList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (position >= panelRows.size()) return;
                Object row = panelRows.get(position);
                if (row instanceof Integer) {             // calidad
                    togglePanel(false);
                    chooseQuality((Integer) row);
                } else if (row instanceof Boolean) {      // subtítulos sí / no
                    togglePanel(false);
                    player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                            .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, !(Boolean) row)
                            .build());
                    refreshServerList();
                } else if (row instanceof StreamResolver.Server) {
                    togglePanel(false);
                    int s = servers.indexOf(row);
                    WatchHistory.setLanguage(VodPlayerActivity.this, servers.get(s).language);
                    playServer(s, player != null ? player.getCurrentPosition() : 0);
                }
                // los títulos de sección no hacen nada
            }
        });

        // En el celular: botón para seguir viendo en la TV (en la TV no aplica)
        View send = findViewById(R.id.vodSendToTv);
        View servers = findViewById(R.id.vodServers);
        if (isTelevision()) {
            send.setVisibility(View.GONE);
            servers.setVisibility(View.GONE);
        } else {
            send.setOnClickListener(v -> sendToTv());
            // En el celular no hay ▲ ni MENÚ: el panel se abre tocando aquí
            servers.setOnClickListener(v -> togglePanel(panel.getVisibility() != View.VISIBLE));
            // …y se cierra tocando el vídeo
            playerView.setOnTouchListener((v, e) -> {
                if (e.getAction() == android.view.MotionEvent.ACTION_DOWN && panel.getVisibility() == View.VISIBLE) {
                    togglePanel(false);
                    return true;
                }
                return false;
            });
        }

        skipIntro = findViewById(R.id.vodSkipIntro);
        nextCard = findViewById(R.id.vodNextCard);
        nextTitle = findViewById(R.id.vodNextTitle);
        nextCountdown = findViewById(R.id.vodNextCountdown);
        skipIntro.setOnClickListener(v -> doSkipIntro());
        statusBox.setOnClickListener(v -> { if (unavailable) loadEpisode(); });
        nextCard.setOnClickListener(v -> goNextNow());
        ui.post(episodeTicker);

        initPlayer();
        loadEpisode();
    }

    // ------------------------------------------- intro / siguiente episodio

    private boolean hasNextEpisode() {
        if (!"tv".equals(item.type) || episodeCounts == null) return false;
        int count = season < episodeCounts.length ? episodeCounts[season] : 0;
        return episode < count || (season + 1 < episodeCounts.length && episodeCounts[season + 1] > 0);
    }

    private String nextLabel() {
        int count = season < episodeCounts.length ? episodeCounts[season] : 0;
        return episode < count ? "T" + season + " E" + (episode + 1) : "T" + (season + 1) + " E1";
    }

    private android.content.SharedPreferences introPrefs() {
        return getSharedPreferences("sopa1d_intro", MODE_PRIVATE);
    }

    /** Dónde termina la intro de esta serie, aprendido la primera vez que se salta. */
    private long learnedIntroEnd() {
        return introPrefs().getLong("intro:" + item.id, -1);
    }

    private void doSkipIntro() {
        if (player == null) return;
        long pos = player.getCurrentPosition();
        long learned = learnedIntroEnd();
        long target = learned > pos ? learned : pos + INTRO_SKIP_MS;
        player.seekTo(target);
        if (learned < 0) introPrefs().edit().putLong("intro:" + item.id, target).apply();
        introDone = true;
        skipIntro.setVisibility(View.GONE);
        playerView.requestFocus();
    }

    private void goNextNow() {
        nextCard.setVisibility(View.GONE);
        countdown = -1;
        persistPosition();
        playNextEpisode();
    }

    private void updateEpisodeOverlays() {
        if (player == null || !"tv".equals(item.type) || current < 0) {
            skipIntro.setVisibility(View.GONE);
            nextCard.setVisibility(View.GONE);
            return;
        }
        boolean playing = player.getPlaybackState() == Player.STATE_READY;
        long pos = player.getCurrentPosition();
        long dur = player.getDuration();
        boolean menus = panel.getVisibility() == View.VISIBLE || Pip.active(this);

        // Saltar intro: primeros minutos (o hasta donde termina la aprendida)
        long learned = learnedIntroEnd();
        long until = learned > 0 ? learned - 3000 : INTRO_WINDOW_MS;
        boolean showSkip = playing && !introDone && !menus && pos > 15000 && pos < until;
        if (showSkip != (skipIntro.getVisibility() == View.VISIBLE)) {
            skipIntro.setVisibility(showSkip ? View.VISIBLE : View.GONE);
            if (showSkip && !playerView.isControllerFullyVisible()) skipIntro.requestFocus();
        }

        // Siguiente episodio: en los créditos, con cuenta regresiva
        if (dur > 0 && hasNextEpisode() && !nextDismissed && !menus && playing
                && dur - pos <= NEXT_AT_REMAINING_MS) {
            if (countdown < 0) {
                countdown = NEXT_COUNTDOWN_S;
                nextTitle.setText("Siguiente episodio · " + nextLabel());
                String img = item.backdrop != null ? item.backdrop : item.poster;
                if (img != null) com.bumptech.glide.Glide.with(this).load(img)
                        .into((android.widget.ImageView) findViewById(R.id.vodNextImage));
                nextCard.setVisibility(View.VISIBLE);
                nextCard.requestFocus();
            }
            nextCountdown.setText("Empieza en " + countdown + " s");
            if (countdown-- <= 0) goNextNow();
        } else if (countdown >= 0 && (dur - pos > NEXT_AT_REMAINING_MS || nextDismissed)) {
            nextCard.setVisibility(View.GONE);
            countdown = -1;
        }
    }

    // ---------------------------------------------------- calidad

    /** Ya reprodujo alguna vez (los "cargando" del arranque no son cortes). */
    private boolean everReady, seeking;
    private String qualityLabel = "";
    private final java.util.ArrayDeque<Long> rebuffers = new java.util.ArrayDeque<>();
    private final Runnable seekDone = () -> seeking = false;

    /**
     * Se quedó cargando a mitad de la reproducción. Dos veces en 2 minutos =
     * internet que no da para esta calidad: se fija un techo más bajo que la
     * calidad actual (ExoPlayer sola tarda en reaccionar).
     */
    private void onRebuffer() {
        long now = System.currentTimeMillis();
        rebuffers.addLast(now);
        while (!rebuffers.isEmpty() && now - rebuffers.peekFirst() > 120_000) rebuffers.pollFirst();
        if (rebuffers.size() < 2 || player == null) return;
        if (WatchHistory.quality(this) > 0) return; // el usuario fijó la calidad: se respeta
        androidx.media3.common.Format f = player.getVideoFormat();
        if (f == null || f.height <= 360) return; // ya está en lo más bajo razonable
        int cap = f.height >= 1000 ? 720 : f.height >= 700 ? 480 : 360;
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                .setMaxVideoSize(Integer.MAX_VALUE, cap)
                .build());
        rebuffers.clear();
        android.widget.Toast.makeText(this, "📶 Conexión lenta: bajando a " + cap + "p",
                android.widget.Toast.LENGTH_SHORT).show();
        Log.i(TAG, "VOD: cortes seguidos en " + f.height + "p, techo " + cap + "p");
    }

    private void initPlayer() {
        DefaultRenderersFactory renderers = new DefaultRenderersFactory(this)
                // Audio AC3/EAC3/MP2 por FFmpeg cuando el equipo no trae decodificador
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
                .setEnableDecoderFallback(true);
        player = new ExoPlayer.Builder(this, renderers)
                .setBandwidthMeter(Quality.bandwidthMeter(this))
                .setTrackSelector(Quality.trackSelector(this))
                .setSeekBackIncrementMs(SEEK_STEP_MS)
                .setSeekForwardIncrementMs(SEEK_STEP_MS)
                .build();
        playerView.setPlayer(player);
        playerView.requestFocus();

        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) {
                    statusBox.setVisibility(View.GONE);
                    everReady = true;
                } else if (state == Player.STATE_BUFFERING && everReady && !seeking) {
                    onRebuffer();
                } else if (state == Player.STATE_ENDED) {
                    persistPosition();
                    playNextEpisode();
                }
            }

            @Override
            public void onPositionDiscontinuity(Player.PositionInfo o, Player.PositionInfo n, int reason) {
                // Un salto (◀ ▶, saltar intro) también vacía el buffer: no cuenta como corte
                if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                    seeking = true;
                    ui.removeCallbacks(seekDone);
                    ui.postDelayed(seekDone, 4000);
                }
            }

            @Override
            public void onTracksChanged(androidx.media3.common.Tracks tracks) {
                if (panel.getVisibility() != View.VISIBLE) refreshServerList();
            }

            @Override
            public void onVideoSizeChanged(androidx.media3.common.VideoSize size) {
                String q = Quality.label(size.height);
                if (!q.isEmpty() && !q.equals(qualityLabel)) {
                    qualityLabel = q;
                    String base = subtitle.getText().toString().replaceAll("   ·   📶 \\S+", "");
                    subtitle.setText(base + "   ·   📶 " + q);
                }
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                Log.w(TAG, "VOD: falla en " + serverLabel() + ": " + error.getErrorCodeName());
                long pos = player.getCurrentPosition();
                tryNextServer(pos, "El servidor dejó de responder, probando otro…");
            }
        });
    }

    // ------------------------------------------------------------- carga

    private void loadEpisode() {
        final int gen = ++loadGeneration;
        unavailable = false;
        introDone = false;
        nextDismissed = false;
        countdown = -1;
        if (skipIntro != null) skipIntro.setVisibility(View.GONE);
        if (nextCard != null) nextCard.setVisibility(View.GONE);
        current = -1;
        servers = new ArrayList<>();
        title.setText(item.title);
        subtitle.setText("tv".equals(item.type)
                ? "Temporada " + season + " · Episodio " + episode + (isTelevision() ? "   ·   ▲ idioma y servidor" : "")
                : (item.year.isEmpty() ? "" : item.year) + (isTelevision() ? (item.year.isEmpty() ? "" : "   ·   ") + "▲ idioma y servidor" : ""));
        topBar.setVisibility(View.VISIBLE);
        showStatus("Buscando servidor sin anuncios…");

        final String lang = WatchHistory.language(this);
        long forced = getIntent().getLongExtra(EXTRA_POSITION, -1);
        getIntent().removeExtra(EXTRA_POSITION); // solo vale para el primer episodio
        final long resumeAt = forced >= 0 ? forced : WatchHistory.position(this, item.type, item.id, season, episode);
        pool.execute(() -> {
            // TONPlayer a veces responde vacío mientras arma la lista por
            // primera vez (pasó con Stranger Things T1 E3): se reintenta.
            List<StreamResolver.Server> list = new ArrayList<>();
            for (int attempt = 0; attempt < 3 && list.isEmpty() && gen == loadGeneration; attempt++) {
                if (attempt > 0) {
                    ui.post(() -> { if (gen == loadGeneration) showStatus("Buscando servidor sin anuncios… (reintento)"); });
                    try { Thread.sleep(3000); } catch (InterruptedException ie) { return; }
                }
                try {
                    list = StreamResolver.listServers(item.type, item.id, season, episode, lang);
                } catch (Exception e) {
                    Log.w(TAG, "VOD: no se pudieron listar servidores", e);
                    list = new ArrayList<>();
                }
            }
            final List<StreamResolver.Server> found = list;
            if (found.isEmpty()) {
                ui.post(() -> {
                    if (gen != loadGeneration) return;
                    unavailable = true;
                    showStatus("Este título aún no está disponible 😕\nPulsa OK para reintentar");
                });
                return;
            }

            // Se resuelven varios a la vez y se queda con el mejor que funcione.
            // El japonés sin subtítulos (al final de la lista) solo si no hay otra cosa.
            int limit = 0;
            while (limit < found.size() && !StreamResolver.isJapanese(found.get(limit).language)) limit++;
            if (limit == 0) limit = found.size();
            List<Future<StreamResolver.Stream>> futures = new ArrayList<>();
            int n = Math.min(PARALLEL_RESOLVE, limit);
            for (int i = 0; i < n; i++) {
                final StreamResolver.Server s = found.get(i);
                futures.add(pool.submit(() -> StreamResolver.resolve(s)));
            }
            StreamResolver.Stream best = null;
            int bestIndex = -1;
            for (int i = 0; i < futures.size() && best == null; i++) {
                try {
                    best = futures.get(i).get(20, TimeUnit.SECONDS);
                    bestIndex = i;
                } catch (Exception ignored) {
                }
            }
            for (int i = n; i < limit && best == null; i++) {
                best = StreamResolver.resolve(found.get(i));
                bestIndex = i;
            }

            final StreamResolver.Stream stream = best;
            final int index = bestIndex;
            ui.post(() -> {
                if (gen != loadGeneration || destroyed) return;
                servers = found;
                refreshServerList();
                if (stream == null) {
                    showStatus("Ningún servidor respondió. Prueba otro idioma con ▲");
                    return;
                }
                startStream(index, stream, resumeAt);
            });
        });
    }

    /** Resuelve y reproduce un servidor elegido (o el siguiente tras un fallo). */
    private void playServer(final int index, final long positionMs) {
        if (index < 0 || index >= servers.size()) return;
        final int gen = ++loadGeneration;
        showStatus("Conectando con " + servers.get(index).label() + "…");
        final StreamResolver.Server s = servers.get(index);
        pool.execute(() -> {
            final StreamResolver.Stream stream = StreamResolver.resolve(s);
            ui.post(() -> {
                if (gen != loadGeneration || destroyed) return;
                if (stream == null) {
                    current = index;
                    tryNextServer(positionMs, s.label() + " no responde, probando otro…");
                } else {
                    startStream(index, stream, positionMs);
                }
            });
        });
    }

    private void tryNextServer(long positionMs, String message) {
        int next = current + 1;
        if (next >= servers.size()) {
            showStatus("Ningún servidor respondió. Prueba otro idioma con ▲");
            return;
        }
        showStatus(message);
        playServer(next, positionMs);
    }

    private void startStream(int index, StreamResolver.Stream stream, long positionMs) {
        // Audio japonés (u otro que no es español) sin pista en español del
        // servidor: antes de reproducir se buscan subtítulos en OpenSubtitles
        String srvLang = stream.server.language.toLowerCase(java.util.Locale.ROOT);
        boolean audioNoEs = StreamResolver.isJapanese(stream.server.language)
                || !srvLang.matches(".*(latino|castellano|sub).*");
        boolean serverHasEs = false;
        for (String[] s : stream.subtitles) {
            if (s[1].toLowerCase(java.util.Locale.ROOT).matches(".*(span|espa|lat|cast).*")) serverHasEs = true;
        }
        final String subsKey = item.type + ":" + item.id + ":" + season + ":" + episode;
        final boolean wantOpenSubs = audioNoEs && !serverHasEs;
        if (wantOpenSubs && !subsKey.equals(openSubsKey)) {
            final int gen = loadGeneration;
            final int s = season, e = episode;
            showStatus("Buscando subtítulos en español…");
            pool.execute(() -> {
                final OpenSubs.Result r = OpenSubs.find(this, item.type, item.id, s, e);
                ui.post(() -> {
                    if (gen != loadGeneration || destroyed) return;
                    openSubs = r;
                    openSubsKey = subsKey;
                    startStream(index, stream, positionMs);
                });
            });
            return;
        }
        current = index;
        everReady = false;
        rebuffers.clear();
        qualityLabel = "";
        applyQuality();
        refreshServerList();
        Log.i(TAG, "VOD: reproduciendo " + stream.server.label() + " -> " + stream.m3u8);

        Map<String, String> headers = new HashMap<>();
        headers.put("Referer", stream.referer);
        headers.put("Origin", stream.referer.substring(0, stream.referer.length() - 1));
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent(Http.UA)
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(15000)
                .setAllowCrossProtocolRedirects(true)
                .setDefaultRequestProperties(headers);

        // Subtítulos aparte del servidor: se cargan con las mismas cabeceras.
        // Con el idioma "Subtitulado" se activan solos; si no, quedan en CC.
        boolean wantSubs = srvLang.contains("sub");
        String textLang = "es";
        List<MediaItem.SubtitleConfiguration> subs = new ArrayList<>();
        if (wantOpenSubs && openSubs != null) {
            // Los de OpenSubtitles se encienden solos (se apagan con ▲ → CC)
            boolean eng = "eng".equals(openSubs.lang);
            textLang = eng ? "en" : "es";
            wantSubs = true;
            subs.add(new MediaItem.SubtitleConfiguration.Builder(android.net.Uri.fromFile(openSubs.file))
                    .setMimeType(androidx.media3.common.MimeTypes.TEXT_VTT)
                    .setLanguage(textLang)
                    .setLabel(eng ? "Inglés (OpenSubtitles)" : "Español (OpenSubtitles)")
                    .setSelectionFlags(androidx.media3.common.C.SELECTION_FLAG_DEFAULT)
                    .build());
        }
        for (String[] s : stream.subtitles) {
            subs.add(new MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(s[0]))
                    .setMimeType(s[0].contains(".srt") ? androidx.media3.common.MimeTypes.APPLICATION_SUBRIP
                            : androidx.media3.common.MimeTypes.TEXT_VTT)
                    .setLanguage("es")
                    .setLabel(s[1].isEmpty() ? "Español" : s[1])
                    .setSelectionFlags(wantSubs ? androidx.media3.common.C.SELECTION_FLAG_DEFAULT : 0)
                    .build());
        }
        MediaItem media = new MediaItem.Builder().setUri(stream.m3u8)
                .setMimeType(androidx.media3.common.MimeTypes.APPLICATION_M3U8)
                .setSubtitleConfigurations(subs).build();
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                .setPreferredTextLanguage(textLang)
                .setSelectUndeterminedTextLanguage(wantSubs)
                .build());
        // DefaultMediaSourceFactory: además del HLS, monta los subtítulos aparte
        // DefaultDataSource: el HLS por http y los subtítulos de OpenSubtitles desde el caché
        player.setMediaSource(new androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
                new androidx.media3.datasource.DefaultDataSource.Factory(this, http))
                .createMediaSource(media));
        subtitle.setText(subtitle.getText().toString().replace("   ·   💬 subtítulos (CC)", "")
                + (subs.isEmpty() ? "" : "   ·   💬 subtítulos (CC)"));
        player.prepare();
        if (positionMs > 0) player.seekTo(positionMs);
        player.setPlayWhenReady(true);

        showStatus("Cargando " + stream.server.label() + "…");
        ui.removeCallbacks(saveProgress);
        ui.postDelayed(saveProgress, 10000);
        ui.postDelayed(hideTopBar, 5000);
    }

    private void playNextEpisode() {
        if (!"tv".equals(item.type) || episodeCounts == null) {
            finish();
            return;
        }
        int count = season < episodeCounts.length ? episodeCounts[season] : 0;
        if (episode < count) {
            episode++;
        } else if (season + 1 < episodeCounts.length && episodeCounts[season + 1] > 0) {
            season++;
            episode = 1;
        } else {
            finish();
            return;
        }
        player.stop();
        loadEpisode();
    }

    private void persistPosition() {
        if (player == null || current < 0) return;
        long pos = player.getCurrentPosition();
        long dur = player.getDuration();
        if (pos > 5000) WatchHistory.save(this, item, season, episode, pos, dur);
    }

    private boolean isTelevision() {
        android.app.UiModeManager ui = (android.app.UiModeManager) getSystemService(UI_MODE_SERVICE);
        return ui != null && ui.getCurrentModeType() == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION;
    }

    /** Pasa lo que se está viendo a la TV, en el mismo minuto, y cierra aquí. */
    private void sendToTv() {
        long pos = player != null ? player.getCurrentPosition() : 0;
        if (player != null) player.pause();
        persistPosition();
        TvLink.sendWithPicker(this,
                TvLink.vodCommand(item, season, episode, episodeCounts, pos, WatchHistory.language(this)),
                this::finish);
    }

    // --------------------------------------------------------------- ui

    private void showStatus(String text) {
        status.setText(text);
        statusBox.setVisibility(View.VISIBLE);
    }

    private String serverLabel() {
        return current >= 0 && current < servers.size() ? servers.get(current).label() : "?";
    }

    /** Filas del panel: String = título de sección, Integer = calidad (0 = auto), Server = servidor. */
    private final List<Object> panelRows = new ArrayList<>();

    /** Calidades que ofrece el servidor actual (alturas, de mayor a menor). */
    private List<Integer> availableHeights() {
        java.util.TreeSet<Integer> hs = new java.util.TreeSet<>(java.util.Collections.reverseOrder());
        if (player != null) {
            for (androidx.media3.common.Tracks.Group g : player.getCurrentTracks().getGroups()) {
                if (g.getType() != androidx.media3.common.C.TRACK_TYPE_VIDEO) continue;
                for (int i = 0; i < g.length; i++) {
                    int h = g.getTrackFormat(i).height;
                    if (h > 0 && g.isTrackSupported(i)) hs.add(h);
                }
            }
        }
        return new ArrayList<>(hs);
    }

    private boolean hasTextTracks() {
        for (androidx.media3.common.Tracks.Group g : player.getCurrentTracks().getGroups()) {
            if (g.getType() == androidx.media3.common.C.TRACK_TYPE_TEXT) return true;
        }
        return false;
    }

    private void refreshServerList() {
        panelRows.clear();
        List<String> labels = new ArrayList<>();
        int chosen = WatchHistory.quality(this);
        List<Integer> heights = availableHeights();
        if (heights.size() > 1 || chosen > 0) {
            panelRows.add("📶 CALIDAD");
            labels.add("📶  CALIDAD" + (qualityLabel.isEmpty() ? "" : "   (ahora " + qualityLabel + ")"));
            panelRows.add(0);
            labels.add((chosen == 0 ? "▶ " : "    ") + "Automática (según tu internet)");
            for (int h : heights) {
                panelRows.add(h);
                labels.add((chosen == h ? "▶ " : "    ") + Quality.label(h) + (h >= 700 ? "  HD" : ""));
            }
            panelRows.add("🌐 IDIOMA Y SERVIDOR");
            labels.add("🌐  IDIOMA Y SERVIDOR");
        }
        for (int i = 0; i < servers.size(); i++) {
            panelRows.add(servers.get(i));
            labels.add((i == current ? "▶ " : "    ") + servers.get(i).label());
        }
        // Subtítulos sí / no (algunos videos ya los traen grabados en la imagen)
        if (player != null && hasTextTracks()) {
            boolean off = player.getTrackSelectionParameters().disabledTrackTypes
                    .contains(androidx.media3.common.C.TRACK_TYPE_TEXT);
            panelRows.add("💬 SUBTÍTULOS");
            labels.add("💬  SUBTÍTULOS");
            panelRows.add(Boolean.TRUE);
            labels.add((off ? "    " : "▶ ") + "Activados");
            panelRows.add(Boolean.FALSE);
            labels.add((off ? "▶ " : "    ") + "Desactivados");
        }
        serverList.setAdapter(new ArrayAdapter<String>(this, R.layout.item_channel, R.id.channelName, labels) {
            @Override
            public boolean isEnabled(int position) {
                return !(panelRows.get(position) instanceof String); // títulos: no se eligen
            }

            @Override
            public View getView(int position, View convertView, android.view.ViewGroup parent) {
                TextView v = (TextView) super.getView(position, convertView, parent);
                boolean header = panelRows.get(position) instanceof String;
                v.setTextColor(header ? 0xFFE11D48 : 0xFFFFFFFF);
                v.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, header ? 14 : 17);
                return v;
            }
        });
    }

    /**
     * Calidad a mano: se fija la altura (mínimo y máximo) y ExoPlayer elige esa
     * pista. 0 = automática según el internet. Queda guardada para lo próximo.
     */
    private void chooseQuality(int height) {
        WatchHistory.setQuality(this, height);
        applyQuality();
        android.widget.Toast.makeText(this, height == 0 ? "📶 Calidad automática"
                : "📶 Calidad fija: " + Quality.label(height), android.widget.Toast.LENGTH_SHORT).show();
        refreshServerList();
    }

    private void applyQuality() {
        if (player == null) return;
        int h = WatchHistory.quality(this);
        androidx.media3.common.TrackSelectionParameters.Builder b =
                player.getTrackSelectionParameters().buildUpon().clearVideoSizeConstraints();
        if (h > 0) b.setMinVideoSize(0, h).setMaxVideoSize(Integer.MAX_VALUE, h);
        player.setTrackSelectionParameters(b.build());
    }

    private void togglePanel(boolean show) {
        panel.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            playerView.hideController();
            refreshServerList();
            serverList.requestFocus();
            // Se abre en la calidad elegida (arriba); si no hay calidades, en el servidor actual
            int pos = panelRows.indexOf(WatchHistory.quality(this));
            if (pos < 0 && current >= 0 && current < servers.size()) pos = panelRows.indexOf(servers.get(current));
            if (pos >= 0) serverList.setSelection(panelRows.get(pos) instanceof Integer ? 0 : pos);
        } else {
            playerView.requestFocus();
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event);
        boolean panelOpen = panel.getVisibility() == View.VISIBLE;
        boolean controls = playerView.isControllerFullyVisible();

        // Con "Siguiente episodio" a la vista: OK lo pone ya, Atrás lo cancela
        if (nextCard.getVisibility() == View.VISIBLE && !panelOpen) {
            int k = event.getKeyCode();
            if (k == KeyEvent.KEYCODE_BACK) {
                nextDismissed = true;
                nextCard.setVisibility(View.GONE);
                countdown = -1;
                return true;
            }
            if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER) {
                goNextNow();
                return true;
            }
        }
        // "No disponible": OK reintenta
        if (unavailable && (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_CENTER || event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
            loadEpisode();
            return true;
        }
        // "Saltar intro" a la vista y sin controles: OK salta
        if (skipIntro.getVisibility() == View.VISIBLE && !controls && !panelOpen
                && (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_CENTER || event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
            doSkipIntro();
            return true;
        }

        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_MENU:
                togglePanel(!panelOpen);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (!panelOpen && !controls) {
                    togglePanel(true);
                    return true;
                }
                break;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                // Sin controles a la vista, las flechas adelantan y retroceden
                // directamente, como en cualquier app de TV.
                if (!panelOpen && !controls && player != null) {
                    if (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT) player.seekBack(); else player.seekForward();
                    playerView.showController();
                    return true;
                }
                break;
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                persistPosition();
                playNextEpisode();
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (panelOpen) {
                    togglePanel(false);
                    return true;
                }
                if (controls) {
                    playerView.hideController();
                    return true;
                }
                break;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onPause() {
        super.onPause();
        persistPosition();
        // En ventana flotante (PiP) la actividad queda en pausa pero se sigue viendo
        if (player != null && !Pip.active(this)) player.pause();
    }

    /** Inicio mientras se ve algo: sigue en un cuadrito flotante (celular). */
    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        Pip.enter(this, player != null && player.isPlaying());
    }

    @Override
    public void onPictureInPictureModeChanged(boolean inPip, android.content.res.Configuration cfg) {
        super.onPictureInPictureModeChanged(inPip, cfg);
        // En el cuadrito solo el vídeo
        playerView.setUseController(!inPip);
        if (inPip) {
            playerView.hideController();
            topBar.setVisibility(View.GONE);
            panel.setVisibility(View.GONE);
            skipIntro.setVisibility(View.GONE);
            nextCard.setVisibility(View.GONE);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Se cerró el cuadrito flotante: dejar de sonar
        if (player != null && !Pip.active(this)) player.pause();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        ui.removeCallbacksAndMessages(null);
        pool.shutdownNow();
        if (player != null) {
            player.release();
            player = null;
        }
        super.onDestroy();
    }
}
