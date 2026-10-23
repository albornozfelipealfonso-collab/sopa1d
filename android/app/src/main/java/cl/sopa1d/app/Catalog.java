package cl.sopa1d.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Catálogo de películas, series y anime. Usa la misma API que index.html
 * (un worker sobre TMDB que ya entrega títulos y sinopsis en español).
 */
final class Catalog {

    static final String API = "https://unlimplay.tvymas.workers.dev";

    /** Fila de la pantalla de inicio: título y de dónde salen sus carátulas. */
    static final class Row {
        /** Carátula vertical normal. */
        static final int POSTER = 0;
        /** "Top 10": número gigante al lado de la carátula. */
        static final int TOP10 = 1;
        /** Horizontal con portada y barra de progreso ("Seguir viendo"). */
        static final int WIDE = 2;

        final String title;
        final String url;            // null si los ítems vienen ya cargados
        final List<CatalogItem> fixed;
        int style = POSTER;

        Row(String title, String url) {
            this.title = title;
            this.url = url;
            this.fixed = null;
        }

        Row(String title, List<CatalogItem> items) {
            this.title = title;
            this.url = null;
            this.fixed = items;
        }

        Row style(int s) {
            style = s;
            return this;
        }
    }

    /** Temporada de una serie con su número de episodios. */
    static final class Season {
        final int number;
        final String name;
        final int episodeCount;

        Season(int number, String name, int episodeCount) {
            this.number = number;
            this.name = name;
            this.episodeCount = episodeCount;
        }
    }

    private static final Map<String, List<CatalogItem>> cache =
            Collections.synchronizedMap(new HashMap<String, List<CatalogItem>>());

    private Catalog() {}

    /** Para leer assets/series_genres.json (filas "local:"). */
    private static android.content.Context app;

    static void init(android.content.Context ctx) {
        app = ctx.getApplicationContext();
    }

    /**
     * Filas que vienen de un archivo de la app: "local:terror" (series_genres.json,
     * tools/build_series_genres.mjs) o "local:chile.json#series" (tools/build_chile.mjs).
     */
    static final String LOCAL = "local:";

    private static List<CatalogItem> loadLocal(String spec) throws Exception {
        List<CatalogItem> out = new ArrayList<>();
        if (app == null) return out;
        String file = "series_genres.json", key = spec;
        int hash = spec.indexOf('#');
        if (hash > 0) { file = spec.substring(0, hash); key = spec.substring(hash + 1); }
        String json;
        try (java.io.InputStream in = app.getAssets().open(file)) {
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[16384];
            int n;
            while ((n = in.read(b)) != -1) buf.write(b, 0, n);
            json = buf.toString("UTF-8");
        }
        JSONArray arr = new JSONObject(json).optJSONArray(key);
        for (int i = 0; arr != null && i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            String backdrop = o.optString("backdrop", "");
            out.add(new CatalogItem("movie".equals(o.optString("type")) ? "movie" : "tv",
                    String.valueOf(o.optLong("id")), o.optString("title"),
                    o.optString("year"), o.optDouble("rating", 0), o.optString("overview"),
                    o.optString("poster"), backdrop.isEmpty() ? null : backdrop));
        }
        return out;
    }

    static List<CatalogItem> load(String url) throws Exception {
        List<CatalogItem> hit = cache.get(url);
        if (hit != null) return hit;
        List<CatalogItem> items = url.startsWith(ANILIST)
                ? AniList.load(url.substring(ANILIST.length()))
                : url.startsWith(LOCAL)
                ? loadLocal(url.substring(LOCAL.length()))
                : CatalogItem.listFromJson(new JSONObject(Http.getString(url)));
        cache.put(url, items);
        return items;
    }

    /** Filas de anime por tipo: "anilist:tag:Shounen" (ver AniList). */
    static final String ANILIST = "anilist:";

    static String list(String kind, int genre) {
        return API + "/list?type=" + kind + "&page=1" + (genre > 0 ? "&genre=" + genre : "");
    }

    /**
     * Lo más votado de siempre. Por popularidad la API pone arriba talk shows
     * y telenovelas asiáticas diarias, que casi nunca tienen copia en latino;
     * por votos salen Juego de Tronos, Breaking Bad, La casa de papel...
     */
    static String best(String kind, int genre) {
        return list(kind, genre) + "&sort_by=vote_count.desc";
    }

    static String trending(String type) {
        return API + "/trending?time=day&type=" + type + "&page=1";
    }

    static String search(String query) {
        try {
            return API + "/search?q=" + URLEncoder.encode(query, "UTF-8") + "&page=1";
        } catch (Exception e) {
            return API + "/search?q=" + query + "&page=1";
        }
    }

    static List<Row> homeRows() {
        List<Row> rows = new ArrayList<>();
        rows.add(new Row("🔟 Top 10 películas de hoy", trending("movie")).style(Row.TOP10));
        rows.add(new Row("🔟 Top 10 series de hoy", trending("tv")).style(Row.TOP10));
        rows.add(new Row("🎬 Películas populares", list("movies", 0)));
        rows.add(new Row("⭐ Series imperdibles", best("tvshows", 0)));
        rows.add(new Row("🌸 Anime imperdible", best("anime", 0)));
        rows.add(new Row("🇨🇱 Series chilenas", LOCAL + "chile.json#series"));
        rows.add(new Row("🇨🇱 Películas chilenas", LOCAL + "chile.json#peliculas"));
        rows.add(new Row("💥 Acción", best("movies", 28)));
        rows.add(new Row("😂 Comedia", best("movies", 35)));
        rows.add(new Row("👻 Terror", best("movies", 27)));
        return rows;
    }

    static List<Row> movieRows() {
        List<Row> rows = new ArrayList<>();
        rows.add(new Row("Populares", list("movies", 0)));
        rows.add(new Row("🇨🇱 Chilenas", LOCAL + "chile.json#peliculas"));
        int[] ids = {28, 12, 16, 35, 80, 18, 10751, 14, 27, 878, 53, 9648, 10749, 99};
        String[] names = {"💥 Acción", "🗺️ Aventura", "🎨 Animación", "😂 Comedia", "🕵️ Crimen",
                "🎭 Drama", "👨‍👩‍👧 Familia", "✨ Fantasía", "👻 Terror", "🛸 Ciencia ficción",
                "😱 Suspenso", "🔎 Misterio", "💘 Romance", "🌍 Documental"};
        for (int i = 0; i < ids.length; i++) rows.add(new Row(names[i], best("movies", ids[i])));
        return rows;
    }

    static List<Row> seriesRows() {
        List<Row> rows = new ArrayList<>();
        rows.add(new Row("📺 Del momento", trending("tv")));
        rows.add(new Row("⭐ Imperdibles", best("tvshows", 0)));
        rows.add(new Row("🇨🇱 Chilenas", LOCAL + "chile.json#series"));
        int[] ids = {10759, 18, 35, 80, 10765, 9648, 16, 10751, 10762, 99, 10764};
        String[] names = {"💥 Acción y aventura", "🎭 Drama", "😂 Comedia", "🕵️ Crimen",
                "🛸 Ciencia ficción y fantasía", "🔎 Misterio", "🎨 Animación", "👨‍👩‍👧 Familia",
                "🧸 Infantil", "🌍 Documental", "📸 Reality"};
        for (int i = 0; i < ids.length; i++) rows.add(new Row(names[i], best("tvshows", ids[i])));
        // TMDB no tiene terror ni suspenso para series: listas armadas desde TVmaze
        rows.add(2, new Row("👻 Terror", LOCAL + "terror"));
        rows.add(3, new Row("⚡ Suspenso", LOCAL + "suspenso"));
        return rows;
    }

    static List<Row> animeRows() {
        List<Row> rows = new ArrayList<>();
        rows.add(new Row("🌸 Imperdibles", best("anime", 0)));
        rows.add(new Row("🔥 En emisión", list("anime", 0)));
        // Por tipo de anime: la API del catálogo no filtra el anime por género
        String[][] types = {
                {"⚔️ Shōnen", "tag:Shounen"}, {"🌀 Isekai", "tag:Isekai"}, {"🗡️ Seinen", "tag:Seinen"},
                {"🌸 Shōjo", "tag:Shoujo"}, {"💥 Acción", "genre:Action"}, {"🤖 Mecha", "genre:Mecha"},
                {"⚽ Deportes", "genre:Sports"}, {"💕 Romance", "genre:Romance"}, {"😂 Comedia", "genre:Comedy"},
                {"🔮 Fantasía", "genre:Fantasy"}, {"☕ Vida cotidiana", "genre:Slice of Life"},
                {"👁️ Sobrenatural", "genre:Supernatural"}, {"🕵️ Misterio", "genre:Mystery"},
                {"👻 Terror", "genre:Horror"}, {"⚡ Suspenso", "genre:Thriller"}, {"🎬 Películas de anime", "format:MOVIE"}};
        for (String[] t : types) rows.add(new Row(t[0], ANILIST + t[1]));
        return rows;
    }

    /** Temporadas de una serie (sin los "Especiales", temporada 0). */
    static List<Season> seasons(String id) throws Exception {
        JSONObject d = new JSONObject(Http.getString(API + "/details?id=" + id + "&type=tv"));
        List<Season> out = new ArrayList<>();
        JSONArray arr = d.optJSONArray("seasons");
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject s = arr.getJSONObject(i);
            int n = s.optInt("season_number");
            int count = s.optInt("episode_count");
            if (n <= 0 || count <= 0) continue;
            out.add(new Season(n, s.optString("name", "Temporada " + n), count));
        }
        return out;
    }
}
