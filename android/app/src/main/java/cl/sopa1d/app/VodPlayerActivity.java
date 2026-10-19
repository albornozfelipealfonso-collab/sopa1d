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

    private static final String TAG = "Sopa1D";

    static final String EXTRA_ITEM = "item";
    static final String EXTRA_SEASON = "season";
    static final String EXTRA_EPISODE = "episode";
    /** Episodios por temporada: índice = número de temporada. */
    static final String EXTRA_EPISODE_COUNTS = "episodeCounts";

    private static final long SEEK_STEP_MS = 10000;
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
                togglePanel(false);
                WatchHistory.setLanguage(VodPlayerActivity.this, servers.get(position).language);
                playServer(position, player != null ? player.getCurrentPosition() : 0);
            }
        });

        initPlayer();
        loadEpisode();
    }

    private void initPlayer() {
        DefaultRenderersFactory renderers = new DefaultRenderersFactory(this)
                // Audio AC3/EAC3/MP2 por FFmpeg cuando el equipo no trae decodificador
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
                .setEnableDecoderFallback(true);
        player = new ExoPlayer.Builder(this, renderers)
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
                } else if (state == Player.STATE_ENDED) {
                    persistPosition();
                    playNextEpisode();
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
        current = -1;
        servers = new ArrayList<>();
        title.setText(item.title);
        subtitle.setText("tv".equals(item.type)
                ? "Temporada " + season + " · Episodio " + episode + "   ·   ▲ idioma y servidor"
                : (item.year.isEmpty() ? "" : item.year + "   ·   ") + "▲ idioma y servidor");
        topBar.setVisibility(View.VISIBLE);
        showStatus("Buscando servidor sin anuncios…");

        final String lang = WatchHistory.language(this);
        final long resumeAt = WatchHistory.position(this, item.type, item.id, season, episode);
        pool.execute(() -> {
            List<StreamResolver.Server> list;
            try {
                list = StreamResolver.listServers(item.type, item.id, season, episode, lang);
            } catch (Exception e) {
                Log.w(TAG, "VOD: no se pudieron listar servidores", e);
                list = new ArrayList<>();
            }
            final List<StreamResolver.Server> found = list;
            if (found.isEmpty()) {
                ui.post(() -> { if (gen == loadGeneration) showStatus("Este título aún no está disponible 😕"); });
                return;
            }

            // Se resuelven varios a la vez y se queda con el mejor que funcione.
            List<Future<StreamResolver.Stream>> futures = new ArrayList<>();
            int n = Math.min(PARALLEL_RESOLVE, found.size());
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
            for (int i = n; i < found.size() && best == null; i++) {
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
        current = index;
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

        player.setMediaSource(new HlsMediaSource.Factory(http)
                .createMediaSource(MediaItem.fromUri(stream.m3u8)));
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

    // --------------------------------------------------------------- ui

    private void showStatus(String text) {
        status.setText(text);
        statusBox.setVisibility(View.VISIBLE);
    }

    private String serverLabel() {
        return current >= 0 && current < servers.size() ? servers.get(current).label() : "?";
    }

    private void refreshServerList() {
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < servers.size(); i++) {
            labels.add((i == current ? "▶ " : "    ") + servers.get(i).label());
        }
        serverList.setAdapter(new ArrayAdapter<>(this, R.layout.item_channel, R.id.channelName, labels));
    }

    private void togglePanel(boolean show) {
        panel.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            playerView.hideController();
            serverList.requestFocus();
            if (current >= 0) serverList.setSelection(current);
        } else {
            playerView.requestFocus();
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event);
        boolean panelOpen = panel.getVisibility() == View.VISIBLE;
        boolean controls = playerView.isControllerFullyVisible();

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
        if (player != null) player.pause();
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
