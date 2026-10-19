package cl.sopa1d.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/** Una película, serie o anime del catálogo (o un canal en vivo). */
final class CatalogItem implements Serializable {

    /** "movie" o "tv" (el anime se reproduce como serie). "live" para canales. */
    final String type;
    final String id;
    final String title;
    final String year;
    final double rating;
    final String overview;
    final String poster;
    final String backdrop;

    /** Solo canales en vivo: posición en la lista que se pasa al reproductor. */
    int liveIndex = -1;

    CatalogItem(String type, String id, String title, String year, double rating,
                String overview, String poster, String backdrop) {
        this.type = type;
        this.id = id;
        this.title = title;
        this.year = year;
        this.rating = rating;
        this.overview = overview;
        this.poster = poster;
        this.backdrop = backdrop;
    }

    boolean isLive() {
        return "live".equals(type);
    }

    static CatalogItem fromJson(JSONObject o) {
        String rawType = o.optString("type", o.optString("contentType", "movie"));
        String type = "movie".equals(rawType) ? "movie" : "tv";
        return new CatalogItem(
                type,
                String.valueOf(o.optLong("tmdb_id", o.optLong("id"))),
                o.optString("title", o.optString("name", "")),
                o.optString("year", ""),
                o.optDouble("rating", 0),
                o.optString("overview", ""),
                emptyToNull(o.optString("poster", "")),
                emptyToNull(o.optString("backdrop", "")));
    }

    static List<CatalogItem> listFromJson(JSONObject root) {
        List<CatalogItem> out = new ArrayList<>();
        JSONArray arr = root.optJSONArray("results");
        if (arr == null) arr = root.optJSONArray("items");
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String t = o.optString("type", "");
            if ("person".equals(t)) continue; // la búsqueda "multi" también trae actores
            CatalogItem item = fromJson(o);
            if (item.poster != null) out.add(item);
        }
        return out;
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() || "null".equals(s) ? null : s;
    }
}
