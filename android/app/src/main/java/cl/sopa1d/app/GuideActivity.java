package cl.sopa1d.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Guía de programación en grilla. Arriba van los canales favoritos y después
 * los que tienen programación, por categoría (deportes primero). Las señales
 * repetidas de un mismo canal (TNT Señal 1, 2, 3…) comparten guía: se muestra
 * una sola, la primera de la lista.
 */
public class GuideActivity extends Activity {

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(SystemBars.tvScale(base));
    }

    private static final String TAG = "Sopa1D";
    private static final List<String> ORDER = Arrays.asList("Deportes", "Cine & Series", "Infantil",
            "Noticias", "Entretenimiento", "Música", "Cultura", "General");

    private final Handler ui = new Handler(Looper.getMainLooper());
    private GuideView grid;
    private TextView channelView, titleView, hoursView, descView, message;
    private final SimpleDateFormat hm = new SimpleDateFormat("HH:mm", Locale.ROOT);

    private final Runnable onEpg = this::rebuild;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_guide);
        SystemBars.pad(this);
        grid = findViewById(R.id.guideGrid);
        channelView = findViewById(R.id.guideChannel);
        titleView = findViewById(R.id.guideTitle);
        hoursView = findViewById(R.id.guideHours);
        descView = findViewById(R.id.guideDesc);
        message = findViewById(R.id.guideMessage);

        grid.setListener(new GuideView.Listener() {
            @Override
            public void onSelected(GuideView.Row row, Epg.Program p) {
                channelView.setText((row.favorite ? "★ " : "") + row.number + " · " + row.channel.name);
                if (p == null) {
                    titleView.setText("Sin información");
                    hoursView.setText("");
                    descView.setText("");
                    return;
                }
                long now = System.currentTimeMillis();
                titleView.setText(p.title);
                String when = p.start <= now && now < p.stop ? "● AHORA   " : "";
                hoursView.setText(when + hm.format(p.start) + " – " + hm.format(p.stop));
                descView.setText(p.desc);
            }

            @Override
            public void onOpen(int rowIndex) {
                play(rowIndex);
            }
        });

        Epg.init(this);
        Epg.addListener(onEpg);
        ChannelHealth.load(this);
        rebuild();
        grid.requestFocus();
    }

    /** Arma las filas en segundo plano (lee ~6.800 canales) y las muestra. */
    private void rebuild() {
        new Thread(() -> {
            final List<GuideView.Row> rows = buildRows();
            ui.post(() -> {
                if (isFinishing()) return;
                message.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
                grid.setRows(rows);
            });
        }, "sopa1d-guide").start();
    }

    private List<GuideView.Row> buildRows() {
        List<Channel> favs = new ArrayList<>();
        for (CatalogItem f : Favorites.asItems(this)) {
            Channel ch = new Channel(f.title, f.title, f.id, f.poster == null ? "" : f.poster, f.genres);
            ch.country = f.overview;
            favs.add(ch);
        }
        List<Channel> others = new ArrayList<>();
        try (InputStream in = getAssets().open("channels_spa.json");
             ByteArrayOutputStream buf = new ByteArrayOutputStream()) {
            byte[] b = new byte[65536];
            int n;
            while ((n = in.read(b)) != -1) buf.write(b, 0, n);
            JSONArray arr = new JSONArray(buf.toString("UTF-8"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                if (o.optString("url").isEmpty() || ChannelHealth.isHidden(o.optString("url"), o.optBoolean("dead"))) continue;
                Channel ch = new Channel(o.optString("id"), o.optString("name"), o.optString("url"),
                        o.optString("logo"), o.optString("category"));
                ch.country = o.optString("country");
                others.add(ch);
            }
        } catch (Exception e) {
            Log.w(TAG, "Guía: no se pudieron leer los canales", e);
        }
        // Deportes primero; dentro de cada categoría, Chile primero
        java.util.Collections.sort(others, (a, b) -> {
            int ca = ORDER.indexOf(a.category), cb = ORDER.indexOf(b.category);
            if (ca < 0) ca = ORDER.size();
            if (cb < 0) cb = ORDER.size();
            if (ca != cb) return ca - cb;
            return ("Chile".equals(b.country) ? 1 : 0) - ("Chile".equals(a.country) ? 1 : 0);
        });

        List<GuideView.Row> rows = new ArrayList<>();
        Map<List<Epg.Program>, Boolean> used = new IdentityHashMap<>();
        for (Channel ch : favs) {
            List<Epg.Program> p = Epg.schedule(ch.name, ch.country);
            used.put(p, true);
            rows.add(new GuideView.Row(ch, rows.size() + 1, true, p));
        }
        for (Channel ch : others) {
            List<Epg.Program> p = Epg.schedule(ch.name, ch.country);
            if (p.isEmpty() || used.containsKey(p)) continue; // sin guía, o señal repetida
            used.put(p, true);
            rows.add(new GuideView.Row(ch, rows.size() + 1, Favorites.contains(this, ch.url), p));
        }
        return rows;
    }

    /** Abre el canal con la guía entera como lista para zapear. */
    private void play(int rowIndex) {
        try {
            JSONArray arr = new JSONArray();
            for (Channel c : grid.channels()) {
                arr.put(new JSONObject().put("id", c.id).put("name", c.name).put("url", c.url)
                        .put("logo", c.logo).put("category", c.category).put("country", c.country));
            }
            ChannelStore.set(arr.toString());
            startActivity(new Intent(this, PlayerActivity.class).putExtra(PlayerActivity.EXTRA_INDEX, rowIndex));
        } catch (Exception e) {
            Log.w(TAG, "Guía: no se pudo abrir el canal", e);
        }
    }

    @Override
    protected void onDestroy() {
        Epg.removeListener(onEpg);
        super.onDestroy();
    }
}
