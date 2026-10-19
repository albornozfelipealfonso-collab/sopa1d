package cl.sopa1d.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * Inicio para Android TV, estilo Netflix: menú lateral por secciones y filas
 * de carátulas por categoría, todo manejable con las flechas del control.
 * Arriba se muestra la portada y la sinopsis del título enfocado.
 */
public class TvHomeActivity extends Activity {

    private static final String TAG = "Sopa1D";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newFixedThreadPool(4);

    private ImageView heroBackdrop;
    private TextView heroTitle, heroMeta, heroOverview, message;
    private EditText searchBox;
    private RecyclerView rows;
    private RowsAdapter rowsAdapter;

    private View[] navItems;
    private View selectedNav;
    /** Última carátula enfocada, para volver a ella desde el menú. */
    private View lastCard;
    /** La cabecera ya muestra algo (si no, se llena con el primer título). */
    private boolean heroFilled;
    private long lastBackPress;

    /** Canales agrupados por categoría (se cargan una vez desde los assets). */
    private static Map<String, List<CatalogItem>> liveByCategory;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_tv_home);

        heroBackdrop = findViewById(R.id.heroBackdrop);
        heroTitle = findViewById(R.id.heroTitle);
        heroMeta = findViewById(R.id.heroMeta);
        heroOverview = findViewById(R.id.heroOverview);
        message = findViewById(R.id.homeMessage);
        searchBox = findViewById(R.id.searchBox);
        rows = findViewById(R.id.rows);

        rows.setLayoutManager(new LinearLayoutManager(this));
        rows.setItemAnimator(null);
        rowsAdapter = new RowsAdapter();
        rows.setAdapter(rowsAdapter);

        navItems = new View[]{
                findViewById(R.id.navHome), findViewById(R.id.navSearch), findViewById(R.id.navMovies),
                findViewById(R.id.navSeries), findViewById(R.id.navAnime), findViewById(R.id.navLive),
                findViewById(R.id.navWeb)};
        for (View v : navItems) {
            v.setOnClickListener(this::onNavClick);
            // ▶ desde el menú entra al contenido: la búsqueda de foco por
            // geometría no lo encuentra porque las carátulas quedan más abajo.
            v.setOnKeyListener((view, keyCode, event) -> {
                if (keyCode != KeyEvent.KEYCODE_DPAD_RIGHT || event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (searchBox.getVisibility() == View.VISIBLE && rowsAdapter.getItemCount() == 0) {
                    searchBox.requestFocus();
                } else if (lastCard != null && lastCard.isAttachedToWindow()) {
                    lastCard.requestFocus();
                } else {
                    focusFirstCard();
                }
                return true;
            });
        }

        // Al volver del contenido al menú con ◀, el foco cae en la sección
        // actual y no en la que por casualidad quedó a la misma altura.
        getWindow().getDecorView().getViewTreeObserver().addOnGlobalFocusChangeListener((oldF, newF) -> {
            if (newF != null && isNav(newF) && oldF != null && !isNav(oldF)
                    && selectedNav != null && newF != selectedNav) {
                selectedNav.requestFocus();
            }
        });

        searchBox.setOnEditorActionListener((v, actionId, event) -> {
            boolean enter = event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN;
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE || enter) {
                runSearch(v.getText().toString().trim());
                return true;
            }
            return false;
        });

        // Cada teclado de TV manda el "buscar" a su manera (o no lo manda): se
        // busca solo cuando el usuario deja de escribir.
        searchBox.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override
            public void afterTextChanged(Editable s) {
                ui.removeCallbacks(autoSearch);
                if (s.toString().trim().length() >= 3) ui.postDelayed(autoSearch, 900);
            }
        });

        select(findViewById(R.id.navHome));
        findViewById(R.id.navHome).requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // "Seguir viendo" cambia después de ver algo
        if (selectedNav != null && selectedNav.getId() == R.id.navHome) showHome();
    }

    private boolean isNav(View v) {
        for (View n : navItems) if (n == v) return true;
        return false;
    }

    private void onNavClick(View v) {
        if (v.getId() == R.id.navWeb) {
            startActivity(new Intent(this, MainActivity.class));
            return;
        }
        select(v);
        // Tras elegir sección, el foco salta a la primera carátula
        if (v.getId() == R.id.navSearch) {
            searchBox.requestFocus();
        } else {
            ui.postDelayed(this::focusFirstCard, 600);
        }
    }

    private void select(View v) {
        lastCard = null;
        heroFilled = false;
        if (selectedNav != null) selectedNav.setSelected(false);
        selectedNav = v;
        v.setSelected(true);
        searchBox.setVisibility(v.getId() == R.id.navSearch ? View.VISIBLE : View.GONE);
        message.setVisibility(View.GONE);

        int id = v.getId();
        if (id == R.id.navHome) showHome();
        else if (id == R.id.navMovies) rowsAdapter.setRows(Catalog.movieRows());
        else if (id == R.id.navSeries) rowsAdapter.setRows(Catalog.seriesRows());
        else if (id == R.id.navAnime) rowsAdapter.setRows(Catalog.animeRows());
        else if (id == R.id.navLive) showLive();
        else if (id == R.id.navSearch) {
            lastQuery = "";
            rowsAdapter.setRows(new ArrayList<>());
            showMessage("Escribe arriba lo que quieres ver");
        }
        rows.scrollToPosition(0);
    }

    private void showHome() {
        List<Catalog.Row> list = new ArrayList<>();
        List<CatalogItem> cont = WatchHistory.continueWatching(this);
        if (!cont.isEmpty()) list.add(new Catalog.Row("▶ Seguir viendo", cont));
        list.addAll(Catalog.homeRows());
        rowsAdapter.setRows(list);
    }

    private final Runnable autoSearch = () -> runSearch(searchBox.getText().toString().trim());
    private String lastQuery = "";

    private void runSearch(final String q) {
        ui.removeCallbacks(autoSearch);
        if (q.isEmpty() || q.equals(lastQuery)) return;
        lastQuery = q;
        showMessage("Buscando “" + q + "”…");
        List<Catalog.Row> list = new ArrayList<>();
        list.add(new Catalog.Row("Resultados para “" + q + "”", Catalog.search(q)));
        rowsAdapter.setRows(list);
        ui.postDelayed(() -> message.setVisibility(View.GONE), 1500);
    }

    private void showMessage(String text) {
        message.setText(text);
        message.setVisibility(View.VISIBLE);
    }

    /**
     * Deja la fila enfocada arriba del todo, con su título a la vista. Por
     * defecto RecyclerView solo desplaza lo justo para mostrar la carátula y
     * el título de la fila queda tapado.
     */
    private void alignRowOf(View card) {
        // Se aplaza: RecyclerView hace su propio desplazamiento al recibir el
        // foco justo después de este callback y pisaría el nuestro.
        ui.post(() -> {
            View rowView = rows.findContainingItemView(card);
            if (rowView == null) return;
            int pos = rows.getChildAdapterPosition(rowView);
            if (pos == RecyclerView.NO_POSITION || rowView.getTop() == 0) return;
            rows.smoothScrollBy(0, rowView.getTop());
        });
    }

    private void focusFirstCard() {
        RecyclerView.ViewHolder vh = rows.findViewHolderForAdapterPosition(0);
        if (vh == null) return;
        RecyclerView inner = vh.itemView.findViewById(R.id.rowItems);
        if (inner != null && inner.getChildCount() > 0) inner.getChildAt(0).requestFocus();
    }

    // ------------------------------------------------------------ en vivo

    private void showLive() {
        if (liveByCategory != null) {
            rowsAdapter.setRows(liveRows());
            return;
        }
        rowsAdapter.setRows(new ArrayList<>());
        showMessage("Cargando canales…");
        io.execute(() -> {
            Map<String, List<CatalogItem>> grouped = loadChannels();
            ui.post(() -> {
                liveByCategory = grouped;
                message.setVisibility(View.GONE);
                if (selectedNav != null && selectedNav.getId() == R.id.navLive) rowsAdapter.setRows(liveRows());
            });
        });
    }

    private List<Catalog.Row> liveRows() {
        List<Catalog.Row> list = new ArrayList<>();
        for (Map.Entry<String, List<CatalogItem>> e : liveByCategory.entrySet()) {
            list.add(new Catalog.Row(e.getKey(), e.getValue()));
        }
        return list;
    }

    /** Canales destacados: lo que más se busca en Chile. */
    private static final Pattern FEATURED = Pattern.compile(
            "(?i)tnt sports|espn|fox sports|dsports|directv sports|tyc|chilevisi|canal 13|\\btvn\\b|mega\\b|la red");

    private Map<String, List<CatalogItem>> loadChannels() {
        Map<String, List<CatalogItem>> out = new LinkedHashMap<>();
        try (InputStream in = getAssets().open("channels_spa.json");
             ByteArrayOutputStream buf = new ByteArrayOutputStream()) {
            byte[] b = new byte[65536];
            int n;
            while ((n = in.read(b)) != -1) buf.write(b, 0, n);
            JSONArray arr = new JSONArray(buf.toString("UTF-8"));

            List<String> order = Arrays.asList("Deportes", "Cine & Series", "Infantil", "Noticias",
                    "Entretenimiento", "Música", "Cultura", "General");
            Map<String, List<CatalogItem>> byCat = new HashMap<>();
            List<CatalogItem> featured = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String url = o.optString("url");
                if (url.isEmpty()) continue;
                String name = o.optString("name");
                CatalogItem ch = new CatalogItem("live", url, name, "", 0,
                        o.optString("country", ""), emptyToNull(o.optString("logo")), null);
                String cat = o.optString("category", "General");
                if (!byCat.containsKey(cat)) byCat.put(cat, new ArrayList<CatalogItem>());
                byCat.get(cat).add(ch);
                if (FEATURED.matcher(name).find() && featured.size() < 60) featured.add(ch);
            }
            out.put("⭐ Destacados", featured);
            for (String cat : order) if (byCat.containsKey(cat)) out.put(iconFor(cat) + " " + cat, byCat.remove(cat));
            for (Map.Entry<String, List<CatalogItem>> e : byCat.entrySet()) out.put(e.getKey(), e.getValue());
        } catch (Exception e) {
            Log.w(TAG, "No se pudieron leer los canales", e);
        }
        return out;
    }

    private static String iconFor(String cat) {
        switch (cat) {
            case "Deportes": return "⚽";
            case "Cine & Series": return "🎬";
            case "Infantil": return "🧸";
            case "Noticias": return "📰";
            case "Entretenimiento": return "🎉";
            case "Música": return "🎵";
            case "Cultura": return "🏛️";
            default: return "📡";
        }
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    /** Abre un canal en el reproductor en vivo, con su fila para zapear. */
    private void playLive(List<CatalogItem> row, int index) {
        try {
            JSONArray arr = new JSONArray();
            for (CatalogItem c : row) arr.put(new JSONObject().put("id", c.id).put("name", c.title).put("url", c.id));
            ChannelStore.set(arr.toString());
            Intent i = new Intent(this, PlayerActivity.class);
            i.putExtra(PlayerActivity.EXTRA_INDEX, index);
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "No se pudo abrir el canal", Toast.LENGTH_SHORT).show();
        }
    }

    // ------------------------------------------------------------- hero

    private void showHero(CatalogItem item) {
        heroTitle.setText(item.title);
        if (item.isLive()) {
            heroMeta.setText("📡 En vivo" + (item.overview.isEmpty() ? "" : " · " + item.overview));
            heroOverview.setText("Pulsa OK para ver. En el reproductor, ▲ ▼ cambian de canal.");
        } else {
            StringBuilder meta = new StringBuilder();
            if (item.rating > 0) meta.append(String.format(Locale.ROOT, "⭐ %.1f   ", item.rating));
            if (!item.year.isEmpty()) meta.append(item.year).append("   ");
            meta.append("movie".equals(item.type) ? "Película" : "Serie");
            heroMeta.setText(meta);
            heroOverview.setText(item.overview);
        }
        String img = item.backdrop != null ? item.backdrop : item.poster;
        if (img != null && !item.isLive()) {
            Glide.with(this).load(img).into(heroBackdrop);
        } else {
            heroBackdrop.setImageDrawable(null);
        }
    }

    @Override
    public void onBackPressed() {
        // Desde el contenido, Atrás vuelve al menú; desde el menú, doble Atrás sale.
        View f = getCurrentFocus();
        if (f != null && !isNav(f) && selectedNav != null) {
            selectedNav.requestFocus();
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastBackPress < 2000) {
            super.onBackPressed();
        } else {
            lastBackPress = now;
            Toast.makeText(this, "Pulsa Atrás otra vez para salir", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    // ---------------------------------------------------------- adapters

    private final class RowsAdapter extends RecyclerView.Adapter<RowHolder> {
        private List<Catalog.Row> data = new ArrayList<>();
        /** Cambia en cada sección, para descartar cargas de la anterior. */
        private int generation;

        void setRows(List<Catalog.Row> rows) {
            generation++;
            data = rows;
            notifyDataSetChanged();
        }

        @Override
        public RowHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            return new RowHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_tv_row, parent, false));
        }

        @Override
        public void onBindViewHolder(final RowHolder h, int position) {
            final Catalog.Row row = data.get(position);
            h.title.setText(row.title);
            h.cards.setItems(new ArrayList<CatalogItem>());
            if (row.fixed != null) {
                h.cards.setItems(row.fixed);
                return;
            }
            final int gen = generation;
            final String url = row.url;
            h.boundUrl = url;
            io.execute(() -> {
                List<CatalogItem> items;
                try {
                    items = Catalog.load(url);
                } catch (Exception e) {
                    Log.w(TAG, "Fila sin datos: " + url, e);
                    items = new ArrayList<>();
                }
                final List<CatalogItem> result = items;
                ui.post(() -> {
                    if (gen != generation || !url.equals(h.boundUrl)) return;
                    h.cards.setItems(result);
                    if (result.isEmpty() && data.size() == 1) showMessage("No se encontró nada 😕");
                });
            });
        }

        @Override
        public int getItemCount() {
            return data.size();
        }
    }

    private final class RowHolder extends RecyclerView.ViewHolder {
        final TextView title;
        final CardsAdapter cards = new CardsAdapter();
        String boundUrl;

        RowHolder(View v) {
            super(v);
            title = v.findViewById(R.id.rowTitle);
            RecyclerView list = v.findViewById(R.id.rowItems);
            list.setLayoutManager(new LinearLayoutManager(v.getContext(), LinearLayoutManager.HORIZONTAL, false));
            list.setItemAnimator(null);
            list.setAdapter(cards);
        }
    }

    private final class CardsAdapter extends RecyclerView.Adapter<CardHolder> {
        private List<CatalogItem> items = new ArrayList<>();

        void setItems(List<CatalogItem> list) {
            items = list;
            notifyDataSetChanged();
        }

        @Override
        public CardHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            return new CardHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_tv_card, parent, false));
        }

        @Override
        public void onBindViewHolder(final CardHolder h, int position) {
            final CatalogItem item = items.get(position);
            if (!heroFilled) {
                heroFilled = true;
                showHero(item);
            }
            h.title.setText(item.title);
            h.badge.setVisibility(View.GONE);

            ViewGroup.LayoutParams lp = h.itemView.getLayoutParams();
            float d = h.itemView.getResources().getDisplayMetrics().density;
            if (item.isLive()) {
                // Los logos de canales son apaisados
                lp.width = (int) (200 * d);
                lp.height = (int) (130 * d);
                h.image.setScaleType(ImageView.ScaleType.FIT_CENTER);
                h.image.setPadding((int) (14 * d), (int) (10 * d), (int) (14 * d), (int) (34 * d));
                // Con logo, el nombre va en una franja abajo; sin logo ya se
                // ve el nombre grande del fondo y la franja lo repetiría.
                h.badge.setText(item.title);
                h.badge.setVisibility(item.poster != null ? View.VISIBLE : View.GONE);
            } else {
                lp.width = (int) (140 * d);
                lp.height = (int) (210 * d);
                h.image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                h.image.setPadding(0, 0, 0, 0);
            }
            h.itemView.setLayoutParams(lp);

            if (item.poster != null) {
                Glide.with(h.image).load(item.poster).into(h.image);
            } else {
                Glide.with(h.image).clear(h.image);
                h.image.setImageDrawable(null);
            }

            h.itemView.setOnFocusChangeListener((v, hasFocus) -> {
                v.animate().scaleX(hasFocus ? 1.1f : 1f).scaleY(hasFocus ? 1.1f : 1f)
                        .translationZ(hasFocus ? 12f : 0f).setDuration(120).start();
                if (hasFocus) {
                    lastCard = v;
                    showHero(item);
                    alignRowOf(v);
                }
            });
            h.itemView.setOnClickListener(v -> {
                if (item.isLive()) {
                    playLive(items, h.getBindingAdapterPosition());
                } else {
                    Intent i = new Intent(TvHomeActivity.this, DetailActivity.class);
                    i.putExtra(DetailActivity.EXTRA_ITEM, item);
                    startActivity(i);
                }
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }

    private static final class CardHolder extends RecyclerView.ViewHolder {
        final ImageView image;
        final TextView title, badge;

        CardHolder(View v) {
            super(v);
            image = v.findViewById(R.id.cardImage);
            title = v.findViewById(R.id.cardTitle);
            badge = v.findViewById(R.id.cardBadge);
        }
    }
}
