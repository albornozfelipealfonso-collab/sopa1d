package cl.sopa1d.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Filas de anime por tipo (Shōnen, Isekai, Mecha…) desde AniList, igual que
 * los filtros de anime de index.html. La API del catálogo no filtra el anime
 * por género: con genre= devolvía siempre lo mismo o nada.
 *
 * Los títulos de AniList no traen ID de TMDB, que es lo que usan la ficha y
 * los reproductores: al abrir uno, {@link #resolve} lo busca en el catálogo.
 */
final class AniList {

    private static final String API = "https://graphql.anilist.co";
    static final String ID_PREFIX = "al:";

    /** Secuelas sueltas ("Season 2", "Part 2"…): en TMDB van dentro de la serie. */
    private static final Pattern SEQUEL = Pattern.compile(
            "\\bseason\\b|\\bpart\\s*\\d|\\b\\d+(st|nd|rd|th)\\b|\\bcour\\b|\\bII+\\b|final season",
            Pattern.CASE_INSENSITIVE);

    private static final String QUERY =
            "query($genre:String,$tag:String,$format:[MediaFormat],$minTag:Int){"
            + "Page(page:1,perPage:30){media(type:ANIME,isAdult:false,sort:[POPULARITY_DESC],"
            + "genre:$genre,tag:$tag,minimumTagRank:$minTag,format_in:$format){"
            + "id format seasonYear startDate{year} averageScore title{romaji english}"
            + " coverImage{extraLarge large} bannerImage}}}";

    private AniList() {}

    /** spec = "tag:Shounen", "genre:Action" o "format:MOVIE". */
    static List<CatalogItem> load(String spec) throws Exception {
        String[] kv = spec.split(":", 2);
        JSONObject vars = new JSONObject();
        if ("tag".equals(kv[0])) {
            vars.put("tag", kv[1]);
            vars.put("minTag", 60); // solo etiquetas centrales (Bleach tiene "Isekai" de pasada)
        } else if ("genre".equals(kv[0])) {
            vars.put("genre", kv[1]);
        }
        vars.put("format", new JSONArray("format".equals(kv[0])
                ? "[\"" + kv[1] + "\"]" : "[\"TV\",\"TV_SHORT\",\"ONA\",\"MOVIE\"]"));
        String body = new JSONObject().put("query", QUERY).put("variables", vars).toString();
        JSONArray media = new JSONObject(Http.postJson(API, body))
                .getJSONObject("data").getJSONObject("Page").getJSONArray("media");

        List<CatalogItem> out = new ArrayList<>();
        for (int i = 0; i < media.length(); i++) {
            JSONObject m = media.getJSONObject(i);
            JSONObject t = m.getJSONObject("title");
            String romaji = t.optString("romaji", "");
            String english = t.isNull("english") ? "" : t.optString("english", "");
            String format = m.optString("format");
            boolean movie = "MOVIE".equals(format);
            if (!movie && SEQUEL.matcher(english + " " + romaji).find()) continue;
            JSONObject cover = m.optJSONObject("coverImage");
            String poster = cover == null ? null : cover.optString("extraLarge", cover.optString("large", null));
            if (poster == null || poster.isEmpty()) continue;
            int year = m.optInt("seasonYear", 0);
            if (year == 0 && m.optJSONObject("startDate") != null) year = m.getJSONObject("startDate").optInt("year", 0);
            CatalogItem item = new CatalogItem(movie ? "movie" : "tv",
                    ID_PREFIX + m.optInt("id"),
                    english.isEmpty() ? romaji : english,
                    year > 0 ? String.valueOf(year) : "",
                    m.optInt("averageScore", 0) / 10.0,
                    "",
                    poster,
                    m.isNull("bannerImage") ? null : m.optString("bannerImage", null));
            item.altTitle = romaji;
            item.genres = movie ? "Película de anime" : "Anime";
            out.add(item);
        }
        return out;
    }

    static boolean isAniList(CatalogItem item) {
        return item != null && item.id.startsWith(ID_PREFIX);
    }

    /**
     * La ficha del catálogo (TMDB) del anime elegido, o null si no está. Se
     * busca por título en inglés y luego en romaji; entre los resultados
     * animados gana el del mismo año (±1).
     */
    static CatalogItem resolve(CatalogItem item) {
        Set<String> queries = new LinkedHashSet<>();
        queries.add(item.title);
        if (!item.altTitle.isEmpty()) queries.add(item.altTitle);
        int year = parse(item.year);
        for (String q : queries) {
            try {
                String url = Catalog.API + "/search?q=" + URLEncoder.encode(q, "UTF-8")
                        + "&type=" + item.type + "&page=1";
                JSONObject root = new JSONObject(Http.getString(url));
                JSONArray arr = root.optJSONArray("results");
                if (arr == null) continue;
                JSONObject first = null, sameYear = null;
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    JSONArray g = o.optJSONArray("genres");
                    if (g == null || !g.toString().contains("Animación")) continue;
                    if (first == null) first = o;
                    if (year > 0 && Math.abs(parse(o.optString("year")) - year) <= 1) { sameYear = o; break; }
                }
                JSONObject hit = sameYear != null ? sameYear : first;
                if (hit != null) {
                    // La búsqueda no siempre dice el tipo: se mantiene el de AniList
                    hit.put("type", item.type);
                    return CatalogItem.fromJson(hit);
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static int parse(String s) {
        try {
            return Integer.parseInt(s.length() >= 4 ? s.substring(0, 4) : s);
        } catch (Exception e) {
            return 0;
        }
    }
}
