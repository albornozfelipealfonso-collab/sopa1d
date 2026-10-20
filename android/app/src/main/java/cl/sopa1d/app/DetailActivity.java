package cl.sopa1d.app;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Ficha de una película o serie: portada, sinopsis, ▶ Ver / Continuar e
 * idioma. En series, además, temporadas y episodios recorribles con el mando.
 */
public class DetailActivity extends Activity {

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(SystemBars.tvScale(base));
    }

    private static final String TAG = "Sopa1D";
    static final String EXTRA_ITEM = "item";
    /** Desde "Seguir viendo": reproducir en cuanto se sepan las temporadas. */
    static final String EXTRA_AUTOPLAY = "autoplay";

    private static final String[] LANGUAGES = {"Latino", "Castellano", "Subtitulado"};

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private CatalogItem item;
    private TextView btnPlay, btnLanguage, btnMyList;
    private boolean autoplay;
    private RecyclerView seasonsList, episodesList;

    private List<Catalog.Season> seasons = new ArrayList<>();
    private int selectedSeason = 1;
    private int[] episodeCounts;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        boolean tv = SystemBars.isTv(this);
        setContentView(tv ? R.layout.activity_detail : R.layout.activity_detail_phone);
        if (!tv) SystemBars.pad(this);

        item = (CatalogItem) getIntent().getSerializableExtra(EXTRA_ITEM);
        if (item == null) {
            finish();
            return;
        }

        ((TextView) findViewById(R.id.detailTitle)).setText(item.title);
        ((TextView) findViewById(R.id.detailOverview)).setText(item.overview);
        StringBuilder meta = new StringBuilder();
        if (item.rating > 0) meta.append(String.format(Locale.ROOT, "⭐ %.1f   ", item.rating));
        if (!item.year.isEmpty()) meta.append(item.year).append("   ");
        meta.append("movie".equals(item.type) ? "Película" : "Serie");
        ((TextView) findViewById(R.id.detailMeta)).setText(meta);

        String img = item.backdrop != null ? item.backdrop : item.poster;
        if (img != null) Glide.with(this).load(img).into((ImageView) findViewById(R.id.detailBackdrop));

        btnPlay = findViewById(R.id.btnPlay);
        btnLanguage = findViewById(R.id.btnLanguage);
        btnMyList = findViewById(R.id.btnMyList);
        btnMyList.setOnClickListener(v -> {
            boolean added = WatchHistory.toggleFavorite(this, item);
            updateMyListButton();
            android.widget.Toast.makeText(this, added ? "Agregado a Mi lista" : "Quitado de Mi lista",
                    android.widget.Toast.LENGTH_SHORT).show();
        });
        updateMyListButton();
        autoplay = getIntent().getBooleanExtra(EXTRA_AUTOPLAY, false);
        seasonsList = findViewById(R.id.seasonsList);
        episodesList = findViewById(R.id.episodesList);
        seasonsList.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        episodesList.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));

        btnLanguage.setOnClickListener(v -> cycleLanguage());
        btnPlay.setOnClickListener(v -> playResume());
        updateLanguageButton();
        btnPlay.requestFocus();

        if ("tv".equals(item.type)) loadSeasons();
        else if (autoplay) playResume();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updatePlayButton();
        // Al volver del reproductor, marcar el último episodio visto
        if (episodeCounts != null) {
            int[] last = WatchHistory.lastEpisode(this, item.id);
            if (last != null && last[0] < episodeCounts.length) selectedSeason = last[0];
            showEpisodes();
        }
    }

    private void cycleLanguage() {
        String cur = WatchHistory.language(this);
        int i = 0;
        for (int k = 0; k < LANGUAGES.length; k++) if (LANGUAGES[k].equalsIgnoreCase(cur)) i = k;
        WatchHistory.setLanguage(this, LANGUAGES[(i + 1) % LANGUAGES.length]);
        updateLanguageButton();
    }

    private void updateMyListButton() {
        btnMyList.setText(WatchHistory.isFavorite(this, item) ? "✓  En mi lista" : "＋  Mi lista");
    }

    private void updateLanguageButton() {
        btnLanguage.setText("🌐  " + WatchHistory.language(this));
    }

    /** "Ver ahora", o "Continuar T2 E5" si ya se había empezado. */
    private void updatePlayButton() {
        if ("tv".equals(item.type)) {
            int[] last = WatchHistory.lastEpisode(this, item.id);
            btnPlay.setText(last != null
                    ? "▶  Continuar T" + last[0] + " E" + last[1]
                    : "▶  Ver T1 E1");
        } else {
            long pos = WatchHistory.position(this, item.type, item.id, 0, 0);
            btnPlay.setText(pos > 0 ? "▶  Continuar (" + (pos / 60000) + " min)" : "▶  Ver ahora");
        }
    }

    private void playResume() {
        if ("tv".equals(item.type)) {
            int[] last = WatchHistory.lastEpisode(this, item.id);
            int s = last != null ? last[0] : (seasons.isEmpty() ? 1 : seasons.get(0).number);
            int e = last != null ? last[1] : 1;
            VodPlayerActivity.start(this, item, s, e, episodeCounts);
        } else {
            VodPlayerActivity.start(this, item, 0, 0, null);
        }
    }

    // ------------------------------------------------------------ series

    private void loadSeasons() {
        io.execute(() -> {
            List<Catalog.Season> list;
            try {
                list = Catalog.seasons(item.id);
            } catch (Exception e) {
                Log.w(TAG, "Sin temporadas para " + item.id, e);
                list = new ArrayList<>();
            }
            final List<Catalog.Season> result = list;
            ui.post(() -> showSeasons(result));
        });
    }

    private void showSeasons(List<Catalog.Season> list) {
        seasons = list;
        if (list.isEmpty()) {
            if (autoplay) { autoplay = false; playResume(); }
            return;
        }
        int max = 0;
        for (Catalog.Season s : list) max = Math.max(max, s.number);
        episodeCounts = new int[max + 1];
        for (Catalog.Season s : list) episodeCounts[s.number] = s.episodeCount;

        int[] last = WatchHistory.lastEpisode(this, item.id);
        selectedSeason = last != null ? last[0] : list.get(0).number;

        findViewById(R.id.seasonsLabel).setVisibility(View.VISIBLE);
        findViewById(R.id.episodesLabel).setVisibility(View.VISIBLE);
        seasonsList.setVisibility(View.VISIBLE);
        episodesList.setVisibility(View.VISIBLE);

        List<String> labels = new ArrayList<>();
        for (Catalog.Season s : list) labels.add("Temporada " + s.number);
        seasonsList.setAdapter(new ChipAdapter(labels, pos -> {
            selectedSeason = seasons.get(pos).number;
            showEpisodes();
            seasonsList.getAdapter().notifyDataSetChanged();
        }, pos -> seasons.get(pos).number == selectedSeason));
        showEpisodes();
        if (autoplay) {
            autoplay = false;
            playResume();
        }
    }

    private void showEpisodes() {
        int count = selectedSeason < episodeCounts.length ? episodeCounts[selectedSeason] : 0;
        ((TextView) findViewById(R.id.episodesLabel)).setText("Episodios de la temporada " + selectedSeason);
        final int[] last = WatchHistory.lastEpisode(this, item.id);
        List<String> labels = new ArrayList<>();
        for (int e = 1; e <= count; e++) {
            boolean current = last != null && last[0] == selectedSeason && last[1] == e;
            labels.add((current ? "▶ " : "") + "Episodio " + e);
        }
        episodesList.setAdapter(new ChipAdapter(labels,
                pos -> VodPlayerActivity.start(this, item, selectedSeason, pos + 1, episodeCounts),
                pos -> false));
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    // ---------------------------------------------------------- adapter

    interface IntAction { void run(int position); }
    interface IntTest { boolean test(int position); }

    /** Fila horizontal de botones (temporadas o episodios). */
    private static final class ChipAdapter extends RecyclerView.Adapter<ChipAdapter.H> {
        private final List<String> labels;
        private final IntAction onClick;
        private final IntTest isSelected;

        ChipAdapter(List<String> labels, IntAction onClick, IntTest isSelected) {
            this.labels = labels;
            this.onClick = onClick;
            this.isSelected = isSelected;
        }

        static final class H extends RecyclerView.ViewHolder {
            H(View v) { super(v); }
        }

        @Override
        public H onCreateViewHolder(ViewGroup parent, int viewType) {
            return new H(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_tv_chip, parent, false));
        }

        @Override
        public void onBindViewHolder(final H h, int position) {
            TextView t = (TextView) h.itemView;
            t.setText(labels.get(position));
            t.setSelected(isSelected.test(position));
            t.setOnClickListener(v -> onClick.run(h.getBindingAdapterPosition()));
        }

        @Override
        public int getItemCount() {
            return labels.size();
        }
    }
}
