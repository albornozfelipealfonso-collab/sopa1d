package cl.sopa1d.app;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;

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
 * Inicio para Android TV, estilo Netflix: cartelera con la portada del título
 * enfocado, menú lateral que se encoge a íconos, y filas de carátulas por
 * categoría ("Top 10" con números gigantes, "Seguir viendo" con progreso).
 * Todo se maneja con las flechas y el OK del control.
 */
public class TvHomeActivity extends Activity {

    private static final String TAG = "Sopa1D";

    private static final int NAV_COLLAPSED_DP = 92;
    private static final int NAV_EXPANDED_DP = 270;
    /** Espera antes de cambiar la cartelera, para no recargar al pasar rápido. */
    private static final long HERO_DELAY_MS = 220;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newFixedThreadPool(4);

    private ImageView heroBackdrop;
    private TextView heroTitle, heroOverview, message, navLogo;
    private LinearLayout heroChips;
    private View nav;
    private EditText searchBox;
    private RecyclerView rows;
    private RowsAdapter rowsAdapter;

    private View[] navItems;
    private View selectedNav;
    /** Última carátula enfocada, para volver a ella desde el menú. */
    private View lastCard;
    /** La cartelera ya muestra algo (si no, se llena con el primer título). */
    private boolean heroFilled;
    private String heroImage;
    private boolean navExpanded;
    private ValueAnimator navAnim;
    private long lastBackPress;

    /** Canales agrupados por categoría (se cargan una vez desde los assets). */
    private static Map<String, List<CatalogItem>> liveByCategory;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_tv_home);

        heroBackdrop = findViewById(R.id.heroBackdrop);
        heroTitle = findViewById(R.id.heroTitle);
        heroChips = findViewById(R.id.heroChips);
        heroOverview = findViewById(R.id.heroOverview);
        message = findViewById(R.id.homeMessage);
        searchBox = findViewById(R.id.searchBox);
        rows = findViewById(R.id.rows);
        nav = findViewById(R.id.tvNav);
        navLogo = findViewById(R.id.navLogo);

        // La fila enfocada siempre queda arriba, con su título a la vista. El
        // desplazamiento por defecto solo mostraba la carátula (agrandada al
        // enfocarse) y el título de la fila quedaba tapado.
        rows.setLayoutManager(new LinearLayoutManager(this) {
            @Override
            public boolean requestChildRectangleOnScreen(RecyclerView parent, View child,
                                                         android.graphics.Rect rect, boolean immediate,
                                                         boolean focusedChildVisible) {
                int dy = child.getTop() - parent.getPaddingTop();
                if (dy != 0) parent.smoothScrollBy(0, dy);
                return true;
            }
        });
        rows.setItemAnimator(null);
        rowsAdapter = new RowsAdapter();
        rows.setAdapter(rowsAdapter);

        navItems = new View[]{
                findViewById(R.id.navHome), findViewById(R.id.navSearch), findViewById(R.id.navMovies),
                findViewById(R.id.navSeries), findViewById(R.id.navAnime), findViewById(R.id.navLive),
                findViewById(R.id.navMyList), findViewById(R.id.navWeb)};
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

        getWindow().getDecorView().getViewTreeObserver().addOnGlobalFocusChangeListener((oldF, newF) -> {
            if (newF == null) return;
            // Al volver del contenido al menú con ◀, el foco cae en la sección
            // actual y no en la que por casualidad quedó a la misma altura.
            if (isNav(newF) && oldF != null && !isNav(oldF) && selectedNav != null && newF != selectedNav) {
                selectedNav.requestFocus();
                return;
            }
            // Como en Netflix: el menú solo se despliega mientras se usa.
            setNavExpanded(isNav(newF));
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

        // Programación de los canales: se baja en segundo plano
        Epg.init(this);
        Epg.addListener(onEpgUpdate);

        select(findViewById(R.id.navHome));
        if (savedInstanceState == null) playSplash();
        // Se entra directo a las carátulas, con el menú plegado
        ui.postDelayed(this::focusFirstCard, savedInstanceState == null ? 1700 : 300);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // "Seguir viendo" y "Mi lista" cambian después de ver o guardar algo
        if (selectedNav == null) return;
        // Solo se recarga si cambió algo: recargar siempre hacía perder la
        // carátula en la que estaba el usuario al volver de una ficha.
        String sig = personalSignature();
        if (sig.equals(lastSignature)) return;
        if (selectedNav.getId() == R.id.navHome) showHome();
        else if (selectedNav.getId() == R.id.navMyList) showMyList();
    }

    private String lastSignature = "";

    private String personalSignature() {
        StringBuilder sb = new StringBuilder();
        for (CatalogItem c : WatchHistory.continueWatching(this)) sb.append(c.id).append(c.label).append(Math.round(c.progress * 100)).append(',');
        sb.append('|');
        for (CatalogItem c : WatchHistory.favorites(this)) sb.append(c.id).append(',');
        return sb.toString();
    }

    // -------------------------------------------------------------- menú

    private boolean isNav(View v) {
        for (View n : navItems) if (n == v) return true;
        return false;
    }

    private void setNavExpanded(boolean expand) {
        if (expand == navExpanded) return;
        navExpanded = expand;
        for (View v : navItems) {
            String[] parts = String.valueOf(v.getTag()).split("\\|");
            ((TextView) v).setText(expand ? parts[0] + "   " + parts[1] : parts[0]);
        }
        navLogo.setText(expand ? "🍜 SOPA1D" : "🍜");

        final ViewGroup.LayoutParams lp = nav.getLayoutParams();
        int target = dp(expand ? NAV_EXPANDED_DP : NAV_COLLAPSED_DP);
        if (navAnim != null) navAnim.cancel();
        navAnim = ValueAnimator.ofInt(lp.width, target).setDuration(180);
        navAnim.setInterpolator(new DecelerateInterpolator());
        navAnim.addUpdateListener(a -> {
            lp.width = (int) a.getAnimatedValue();
            nav.setLayoutParams(lp);
        });
        navAnim.start();
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
        else if (id == R.id.navMyList) showMyList();
        else if (id == R.id.navSearch) {
            lastQuery = "";
            rowsAdapter.setRows(new ArrayList<>());
            showMessage("Escribe arriba lo que quieres ver");
        }
        rows.scrollToPosition(0);
    }

    private void showHome() {
        lastSignature = personalSignature();
        List<Catalog.Row> list = new ArrayList<>();
        List<CatalogItem> cont = WatchHistory.continueWatching(this);
        if (!cont.isEmpty()) list.add(new Catalog.Row("▶ Seguir viendo", cont).style(Catalog.Row.WIDE));
        List<CatalogItem> favs = WatchHistory.favorites(this);
        if (!favs.isEmpty()) list.add(new Catalog.Row("❤️ Mi lista", favs));
        list.addAll(Catalog.homeRows());
        rowsAdapter.setRows(list);
    }

    private void showMyList() {
        lastSignature = personalSignature();
        List<Catalog.Row> list = new ArrayList<>();
        List<CatalogItem> favs = WatchHistory.favorites(this);
        if (!favs.isEmpty()) list.add(new Catalog.Row("❤️ Mi lista", favs));
        List<CatalogItem> cont = WatchHistory.continueWatching(this);
        if (!cont.isEmpty()) list.add(new Catalog.Row("▶ Seguir viendo", cont).style(Catalog.Row.WIDE));
        rowsAdapter.setRows(list);
        if (list.isEmpty()) showMessage("Aún no guardas nada. En cada título pulsa “＋ Mi lista”.");
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

    private void focusFirstCard() {
        RecyclerView.ViewHolder vh = rows.findViewHolderForAdapterPosition(0);
        if (vh == null) return;
        RecyclerView inner = vh.itemView.findViewById(R.id.rowItems);
        if (inner != null && inner.getChildCount() > 0) inner.getChildAt(0).requestFocus();
    }

    // ------------------------------------------------------------ entrada

    /** Logo que crece y se desvanece, como el "tudum" de Netflix. */
    private void playSplash() {
        final View splash = findViewById(R.id.splash);
        View logo = findViewById(R.id.splashLogo);
        splash.setVisibility(View.VISIBLE);
        logo.setScaleX(0.6f);
        logo.setScaleY(0.6f);
        logo.setAlpha(0f);
        logo.animate().alpha(1f).scaleX(1.05f).scaleY(1.05f).setDuration(700)
                .setInterpolator(new DecelerateInterpolator())
                .withEndAction(() -> logo.animate().scaleX(1.6f).scaleY(1.6f).alpha(0f).setDuration(450).start())
                .start();
        splash.animate().alpha(0f).setStartDelay(1150).setDuration(400)
                .withEndAction(() -> splash.setVisibility(View.GONE)).start();
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
                String cat = o.optString("category", "General");
                CatalogItem ch = new CatalogItem("live", url, name, "", 0,
                        o.optString("country", ""), emptyToNull(o.optString("logo")), null);
                ch.genres = cat;
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
            for (CatalogItem c : row) {
                arr.put(new JSONObject().put("id", c.id).put("name", c.title).put("url", c.id)
                        .put("logo", c.poster == null ? "" : c.poster).put("category", c.genres)
                        .put("country", c.overview));
            }
            ChannelStore.set(arr.toString());
            Intent i = new Intent(this, PlayerActivity.class);
            i.putExtra(PlayerActivity.EXTRA_INDEX, index);
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "No se pudo abrir el canal", Toast.LENGTH_SHORT).show();
        }
    }

    // ---------------------------------------------------------- cartelera

    private CatalogItem pendingHero;
    private final Runnable applyHero = () -> { if (pendingHero != null) showHeroNow(pendingHero); };

    private void showHero(CatalogItem item) {
        pendingHero = item;
        ui.removeCallbacks(applyHero);
        ui.postDelayed(applyHero, HERO_DELAY_MS);
    }

    private void showHeroNow(CatalogItem item) {
        heroTitle.setText(item.title);
        heroChips.removeAllViews();
        if (item.isLive()) {
            addChip("● EN VIVO", 0xFFE50914, true);
            if (!item.genres.isEmpty()) addChip(item.genres, 0, false);
            if (!item.overview.isEmpty()) addChip(item.overview, 0, false);
            heroOverview.setText(liveGuideText(item));
        } else {
            if (item.rating > 0) addChip(String.format(Locale.ROOT, "⭐ %.1f", item.rating), 0xFFF5C518, true);
            if (!item.year.isEmpty()) addChip(item.year, 0, false);
            addChip("HD", 0, false);
            addChip("movie".equals(item.type) ? "Película" : "Serie", 0, false);
            if (!item.label.isEmpty()) addChip("Vas en " + item.label, 0xFFE50914, true);
            if (!item.genres.isEmpty()) addText(item.genres);
            heroOverview.setText(item.overview);
        }

        String img = item.isLive() ? null : (item.backdrop != null ? item.backdrop : item.poster);
        if (img == null) {
            heroImage = null;
            heroBackdrop.setImageDrawable(null);
        } else if (!img.equals(heroImage)) {
            heroImage = img;
            Glide.with(this).load(img)
                    .transition(DrawableTransitionOptions.withCrossFade(450))
                    .into(heroBackdrop);
        }
    }

    /**
     * "AHORA 21:00 – 23:00  Colo-Colo vs. U. de Chile" + sinopsis + "DESPUÉS ...".
     * Sin guía para el canal, la ayuda de siempre.
     */
    private CharSequence liveGuideText(CatalogItem item) {
        Epg.Program[] nn = Epg.nowNext(item.title, item.overview);
        if (nn[0] == null && nn[1] == null) {
            return "Pulsa OK para ver. En el reproductor, ▲ ▼ cambian de canal y OK abre la lista.";
        }
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
        if (nn[0] != null) {
            appendLabel(sb, "AHORA ", 0xFFE50914);
            sb.append(nn[0].hours()).append("   ");
            int st = sb.length();
            sb.append(nn[0].title);
            sb.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), st, sb.length(), 0);
            if (!nn[0].desc.isEmpty()) sb.append("  ·  ").append(nn[0].desc);
            sb.append('\n');
        }
        if (nn[1] != null) {
            appendLabel(sb, "DESPUÉS ", 0xFFB3B3B3);
            sb.append(new java.text.SimpleDateFormat("HH:mm", Locale.ROOT).format(nn[1].start))
                    .append("   ").append(nn[1].title);
        }
        return sb;
    }

    private static void appendLabel(android.text.SpannableStringBuilder sb, String label, int color) {
        int st = sb.length();
        sb.append(label);
        sb.setSpan(new android.text.style.ForegroundColorSpan(color), st, sb.length(), 0);
        sb.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), st, sb.length(), 0);
    }

    /** Etiqueta de la cartelera; con color sólido si fill != 0. */
    private void addChip(String text, int fill, boolean bold) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setTextColor(fill == 0xFFF5C518 ? 0xFF07080C : 0xFFFFFFFF);
        if (bold) t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        t.setPadding(dp(10), dp(3), dp(10), dp(3));
        if (fill != 0) {
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setColor(fill);
            bg.setCornerRadius(dp(6));
            t.setBackground(bg);
        } else {
            t.setBackgroundResource(R.drawable.chip_bg);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(8);
        heroChips.addView(t, lp);
    }

    private void addText(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setTextColor(0xCCFFFFFF);
        t.setSingleLine(true);
        t.setPadding(dp(6), 0, 0, 0);
        heroChips.addView(t);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
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

    /** Llegó programación: se repintan los canales y la cartelera si es un canal. */
    private final Runnable onEpgUpdate = () -> {
        if (selectedNav != null && selectedNav.getId() == R.id.navLive) {
            for (int i = 0; i < rows.getChildCount(); i++) {
                RecyclerView inner = rows.getChildAt(i).findViewById(R.id.rowItems);
                if (inner != null && inner.getAdapter() != null) inner.getAdapter().notifyDataSetChanged();
            }
        }
        if (pendingHero != null && pendingHero.isLive()) showHeroNow(pendingHero);
    };

    @Override
    protected void onDestroy() {
        Epg.removeListener(onEpgUpdate);
        ui.removeCallbacksAndMessages(null);
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
            h.cards.setItems(new ArrayList<CatalogItem>(), row.style);
            if (row.fixed != null) {
                h.boundUrl = null;
                h.cards.setItems(row.fixed, row.style);
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
                if (row.style == Catalog.Row.TOP10 && items.size() > 10) items = items.subList(0, 10);
                final List<CatalogItem> result = items;
                ui.post(() -> {
                    if (gen != generation || !url.equals(h.boundUrl)) return;
                    h.cards.setItems(result, row.style);
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
        private int style = Catalog.Row.POSTER;

        void setItems(List<CatalogItem> list, int rowStyle) {
            items = list;
            style = rowStyle;
            notifyDataSetChanged();
        }

        @Override
        public int getItemViewType(int position) {
            return style;
        }

        @Override
        public CardHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            int layout = viewType == Catalog.Row.TOP10 ? R.layout.item_tv_top10
                    : viewType == Catalog.Row.WIDE ? R.layout.item_tv_wide
                    : R.layout.item_tv_card;
            return new CardHolder(LayoutInflater.from(parent.getContext()).inflate(layout, parent, false));
        }

        @Override
        public void onBindViewHolder(final CardHolder h, int position) {
            final CatalogItem item = items.get(position);
            if (!heroFilled) {
                heroFilled = true;
                showHeroNow(item);
            }

            if (style == Catalog.Row.TOP10) {
                h.rank.setText(String.valueOf(position + 1));
                load(h.image, item.poster);
            } else if (style == Catalog.Row.WIDE) {
                h.title.setText(item.title);
                load(h.image, item.backdrop != null ? item.backdrop : item.poster);
                h.badge.setText(item.label);
                h.badge.setVisibility(item.label.isEmpty() ? View.GONE : View.VISIBLE);
                h.progress.setProgress(Math.round(Math.max(0.03f, item.progress) * 1000));
            } else {
                bindPoster(h, item);
            }

            h.itemView.setOnFocusChangeListener((v, hasFocus) -> {
                float scale = hasFocus ? (style == Catalog.Row.WIDE ? 1.08f : 1.12f) : 1f;
                // En el Top 10 el contenedor es transparente (número + carátula):
                // elevarlo dibujaba una sombra gris cuadrada detrás de ambos.
                float z = hasFocus && style != Catalog.Row.TOP10 ? 16f : 0f;
                v.animate().scaleX(scale).scaleY(scale)
                        .translationZ(z).setDuration(140)
                        .setInterpolator(new DecelerateInterpolator()).start();
                if (h.play != null) h.play.setVisibility(hasFocus ? View.VISIBLE : View.GONE);
                if (hasFocus) {
                    lastCard = v;
                    showHero(item);
                }
            });
            h.itemView.setOnClickListener(v -> {
                if (item.isLive()) {
                    playLive(items, h.getBindingAdapterPosition());
                } else if (style == Catalog.Row.WIDE) {
                    // "Seguir viendo" va directo al reproductor, donde quedó
                    resume(item);
                } else {
                    Intent i = new Intent(TvHomeActivity.this, DetailActivity.class);
                    i.putExtra(DetailActivity.EXTRA_ITEM, item);
                    startActivity(i);
                }
            });
        }

        private void bindPoster(CardHolder h, CatalogItem item) {
            h.title.setText(item.title);
            h.badge.setVisibility(View.GONE);
            h.live.setVisibility(item.isLive() ? View.VISIBLE : View.GONE);

            ViewGroup.LayoutParams lp = h.itemView.getLayoutParams();
            if (item.isLive()) {
                // Los logos de canales son apaisados
                lp.width = dp(220);
                lp.height = dp(140);
                h.image.setScaleType(ImageView.ScaleType.FIT_CENTER);
                h.image.setPadding(dp(18), dp(26), dp(18), dp(34));
                // Con logo, el nombre va en una franja abajo; sin logo ya se
                // ve el nombre grande del fondo y la franja lo repetiría.
                Epg.Program now = Epg.nowNext(item.title, item.overview)[0];
                // Con guía, la franja dice qué se emite (el nombre ya está en la
                // cartelera); sin guía, el nombre bajo el logo.
                CharSequence badge = now != null ? "▶ " + now.title
                        : item.poster != null ? item.title : null;
                h.badge.setText(badge);
                h.badge.setVisibility(badge != null ? View.VISIBLE : View.GONE);
                h.liveProgress.setVisibility(now != null ? View.VISIBLE : View.GONE);
                if (now != null) h.liveProgress.setProgress(Math.round(now.progress(System.currentTimeMillis()) * 1000));
            } else {
                lp.width = dp(140);
                lp.height = dp(210);
                h.liveProgress.setVisibility(View.GONE);
                h.image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                h.image.setPadding(0, 0, 0, 0);
            }
            h.itemView.setLayoutParams(lp);
            load(h.image, item.poster);
        }

        private void load(ImageView view, String url) {
            if (url != null) {
                Glide.with(view).load(url).transition(DrawableTransitionOptions.withCrossFade(200)).into(view);
            } else {
                Glide.with(view).clear(view);
                view.setImageDrawable(null);
            }
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }

    /** Reanuda una película o el último episodio de una serie. */
    private void resume(CatalogItem item) {
        if ("tv".equals(item.type)) {
            // Sin la lista de temporadas no se sabría qué episodio sigue: se
            // pasa por la ficha, que la carga, y desde ahí al reproductor.
            Intent i = new Intent(this, DetailActivity.class);
            i.putExtra(DetailActivity.EXTRA_ITEM, item);
            i.putExtra(DetailActivity.EXTRA_AUTOPLAY, true);
            startActivity(i);
        } else {
            VodPlayerActivity.start(this, item, 0, 0, null);
        }
    }

    private static final class CardHolder extends RecyclerView.ViewHolder {
        final ImageView image;
        final TextView title, badge, live, rank, play;
        final ProgressBar progress, liveProgress;

        CardHolder(View v) {
            super(v);
            image = v.findViewById(R.id.cardImage);
            title = v.findViewById(R.id.cardTitle);
            badge = v.findViewById(R.id.cardBadge);
            live = v.findViewById(R.id.cardLive);
            rank = v.findViewById(R.id.rankNumber);
            play = v.findViewById(R.id.cardPlay);
            progress = v.findViewById(R.id.cardProgress);
            liveProgress = v.findViewById(R.id.cardLiveProgress);
        }
    }
}
