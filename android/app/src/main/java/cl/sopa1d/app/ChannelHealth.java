package cl.sopa1d.app;

import android.content.Context;
import android.net.ConnectivityManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Revisión diaria de canales dentro de la app: prueba todos en segundo plano
 * y oculta los que no responden, sin depender de tools/check_channels.mjs.
 *
 * Su resultado manda sobre la marca "dead" del JSON: un canal marcado como
 * caído en el PC que hoy responde vuelve a aparecer, y al revés. Solo corre
 * con wifi/ethernet (no en datos móviles) y como mucho una vez cada 24 h.
 */
final class ChannelHealth {

    private static final String TAG = "Sopa1D";
    private static final String FILE = "channel_health.json";
    private static final long EVERY_MS = 24L * 3600_000;
    private static final int THREADS = 16;
    private static final int TIMEOUT_MS = 7000;

    private static volatile Set<String> dead;     // null = nunca se revisó aquí
    private static volatile long checkedAt;
    private static volatile boolean running;
    private static final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private static final Handler ui = new Handler(Looper.getMainLooper());

    private ChannelHealth() {}

    static void addListener(Runnable r) { listeners.add(r); }
    static void removeListener(Runnable r) { listeners.remove(r); }

    /** ¿Se oculta? Con revisión propia manda ella; si no, la marca del JSON. */
    static boolean isHidden(String url, boolean assetDead) {
        Set<String> d = dead;
        return d != null ? d.contains(url) : assetDead;
    }

    /** URLs caídas según la última revisión (para la versión web de la app). */
    static Set<String> deadUrls() {
        Set<String> d = dead;
        return d == null ? Collections.<String>emptySet() : d;
    }

    static synchronized void load(Context ctx) {
        if (dead != null) return;
        File f = new File(ctx.getFilesDir(), FILE);
        if (!f.exists()) return;
        try (InputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int read = 0;
            while (read < b.length) {
                int n = in.read(b, read, b.length - read);
                if (n < 0) break;
                read += n;
            }
            JSONObject o = new JSONObject(new String(b, 0, read, StandardCharsets.UTF_8));
            JSONArray arr = o.getJSONArray("dead");
            Set<String> s = new HashSet<>();
            for (int i = 0; i < arr.length(); i++) s.add(arr.getString(i));
            dead = s;
            checkedAt = o.optLong("checked");
        } catch (Exception e) {
            Log.w(TAG, "Revisión: archivo ilegible", e);
        }
    }

    /** Arranca la revisión si toca (cada 24 h, solo sin datos móviles). */
    static void maybeCheck(Context ctx) {
        final Context app = ctx.getApplicationContext();
        load(app);
        if (running || System.currentTimeMillis() - checkedAt < EVERY_MS) return;
        ConnectivityManager cm = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null || cm.isActiveNetworkMetered()) {
            Log.i(TAG, "Revisión de canales: se espera a tener wifi");
            return;
        }
        running = true;
        new Thread(() -> {
            try {
                check(app);
            } finally {
                running = false;
            }
        }, "sopa1d-health").start();
    }

    private static void check(Context ctx) {
        List<Channel> all = ChannelCatalog.all(ctx);
        final Set<String> found = Collections.synchronizedSet(new HashSet<String>());
        final AtomicInteger done = new AtomicInteger();
        long t0 = System.currentTimeMillis();
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        for (final Channel ch : all) {
            pool.execute(() -> {
                if (!alive(ch.url)) found.add(ch.url);
                done.incrementAndGet();
            });
        }
        pool.shutdown();
        try {
            if (!pool.awaitTermination(40, TimeUnit.MINUTES)) {
                pool.shutdownNow();
                return; // incompleta: no se guarda nada a medias
            }
        } catch (InterruptedException e) {
            return;
        }
        dead = new HashSet<>(found);
        checkedAt = System.currentTimeMillis();
        save(ctx);
        Log.i(TAG, "Revisión de canales: " + (all.size() - found.size()) + " vivos, " + found.size()
                + " caídos en " + (checkedAt - t0) / 1000 + " s");
        ui.post(() -> { for (Runnable r : listeners) r.run(); });
    }

    private static void save(Context ctx) {
        try {
            JSONArray arr = new JSONArray();
            for (String u : dead) arr.put(u);
            String json = new JSONObject().put("checked", checkedAt).put("dead", arr).toString();
            File tmp = new File(ctx.getFilesDir(), FILE + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(json.getBytes(StandardCharsets.UTF_8));
            }
            //noinspection ResultOfMethodCallIgnored
            tmp.renameTo(new File(ctx.getFilesDir(), FILE));
        } catch (Exception e) {
            Log.w(TAG, "Revisión: no se pudo guardar", e);
        }
    }

    /**
     * Vivo = la lista responde y, si es maestra, su primera variante tiene
     * segmentos. Se leen pocos KB: las transmisiones directas no terminan nunca.
     */
    static boolean alive(String url) {
        try {
            String[] r = fetchStart(url, 32 * 1024);
            if (r == null) return false;
            String text = r[1];
            if (!text.contains("#EXTM3U")) return !text.isEmpty() && !text.trim().startsWith("<");
            if (text.contains("#EXT-X-STREAM-INF")) {
                for (String line : text.split("\n")) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    String[] v = fetchStart(new URL(new URL(r[0]), line).toString(), 32 * 1024);
                    return v != null && v[1].contains("#EXTINF");
                }
                return false;
            }
            return text.contains("#EXTINF");
        } catch (Exception e) {
            return false;
        }
    }

    /** {URL final, primeros bytes como texto}, o null si no respondió 2xx. */
    private static String[] fetchStart(String url, int max) {
        HttpURLConnection c = null;
        try {
            String current = url;
            for (int hop = 0; hop < 4; hop++) {
                c = (HttpURLConnection) new URL(current).openConnection();
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(TIMEOUT_MS);
                c.setReadTimeout(TIMEOUT_MS);
                c.setRequestProperty("User-Agent", Http.UA);
                int code = c.getResponseCode();
                if (code >= 300 && code < 400 && c.getHeaderField("Location") != null) {
                    current = new URL(new URL(current), c.getHeaderField("Location")).toString();
                    c.disconnect();
                    continue;
                }
                if (code < 200 || code >= 300) return null;
                byte[] buf = new byte[max];
                int read = 0;
                try (InputStream in = c.getInputStream()) {
                    while (read < max) {
                        int n = in.read(buf, read, max - read);
                        if (n < 0) break;
                        read += n;
                        if (read >= 4096 && !new String(buf, 0, Math.min(read, 16), StandardCharsets.ISO_8859_1).contains("#")) break;
                    }
                }
                return new String[]{current, new String(buf, 0, read, StandardCharsets.UTF_8)};
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.disconnect();
        }
        return null;
    }
}
