package cl.sopa1d.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * "Seguir viendo": dónde quedó cada película o episodio, y el idioma que
 * prefiere el usuario. Se guarda en SharedPreferences.
 */
final class WatchHistory {

    private static final String PREFS = "sopa1d_history";
    private static final String KEY_LIST = "continue";
    private static final String KEY_LANG = "language";
    private static final int MAX_ITEMS = 20;

    private WatchHistory() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static String key(String type, String id, int season, int episode) {
        return "tv".equals(type) ? "pos:tv:" + id + ":" + season + ":" + episode : "pos:movie:" + id;
    }

    static long position(Context c, String type, String id, int season, int episode) {
        return prefs(c).getLong(key(type, id, season, episode), 0);
    }

    /**
     * Guarda la posición y sube el título al principio de "Seguir viendo".
     * Si ya casi terminó, se borra la posición para que la próxima vez empiece
     * desde el principio (o pase al siguiente episodio).
     */
    static void save(Context c, CatalogItem item, int season, int episode, long posMs, long durMs) {
        boolean finished = durMs > 0 && posMs > durMs - 120000;
        SharedPreferences.Editor e = prefs(c).edit();
        String k = key(item.type, item.id, season, episode);
        if (finished) e.remove(k); else e.putLong(k, posMs);

        try {
            JSONArray old = new JSONArray(prefs(c).getString(KEY_LIST, "[]"));
            JSONArray out = new JSONArray();
            JSONObject me = new JSONObject()
                    .put("type", item.type).put("id", item.id).put("title", item.title)
                    .put("poster", item.poster).put("backdrop", item.backdrop)
                    .put("overview", item.overview).put("year", item.year)
                    .put("rating", item.rating).put("genres", item.genres)
                    .put("season", season).put("episode", episode)
                    .put("pos", posMs).put("dur", durMs);
            if (!(finished && "movie".equals(item.type))) out.put(me);
            for (int i = 0; i < old.length() && out.length() < MAX_ITEMS; i++) {
                JSONObject o = old.getJSONObject(i);
                if (o.optString("id").equals(item.id) && o.optString("type").equals(item.type)) continue;
                out.put(o);
            }
            e.putString(KEY_LIST, out.toString());
        } catch (Exception ignored) {
        }
        e.apply();
    }

    /** Último episodio visto de una serie: {temporada, episodio}, o null. */
    static int[] lastEpisode(Context c, String id) {
        try {
            JSONArray arr = new JSONArray(prefs(c).getString(KEY_LIST, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                if ("tv".equals(o.optString("type")) && id.equals(o.optString("id"))) {
                    return new int[]{o.optInt("season", 1), o.optInt("episode", 1)};
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    static List<CatalogItem> continueWatching(Context c) {
        List<CatalogItem> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs(c).getString(KEY_LIST, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                CatalogItem it = fromStored(o);
                long dur = o.optLong("dur", 0);
                it.progress = dur > 0 ? Math.min(1f, o.optLong("pos", 0) / (float) dur) : 0.05f;
                if ("tv".equals(it.type)) it.label = "T" + o.optInt("season", 1) + " E" + o.optInt("episode", 1);
                out.add(it);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static CatalogItem fromStored(JSONObject o) {
        CatalogItem it = new CatalogItem(o.optString("type"), o.optString("id"), o.optString("title"),
                o.optString("year"), o.optDouble("rating", 0), o.optString("overview"),
                emptyToNull(o.optString("poster", null)), emptyToNull(o.optString("backdrop", null)));
        it.genres = o.optString("genres", "");
        return it;
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() || "null".equals(s) ? null : s;
    }

    // ------------------------------------------------------------ Mi lista

    private static final String KEY_FAVS = "favorites";

    static boolean isFavorite(Context c, CatalogItem item) {
        try {
            JSONArray arr = new JSONArray(prefs(c).getString(KEY_FAVS, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                if (o.optString("id").equals(item.id) && o.optString("type").equals(item.type)) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /** Agrega o quita de "Mi lista". Devuelve true si quedó en la lista. */
    static boolean toggleFavorite(Context c, CatalogItem item) {
        try {
            JSONArray old = new JSONArray(prefs(c).getString(KEY_FAVS, "[]"));
            JSONArray out = new JSONArray();
            boolean removed = false;
            for (int i = 0; i < old.length(); i++) {
                JSONObject o = old.getJSONObject(i);
                if (o.optString("id").equals(item.id) && o.optString("type").equals(item.type)) {
                    removed = true;
                    continue;
                }
                out.put(o);
            }
            if (!removed) {
                JSONArray withNew = new JSONArray();
                withNew.put(new JSONObject()
                        .put("type", item.type).put("id", item.id).put("title", item.title)
                        .put("poster", item.poster).put("backdrop", item.backdrop)
                        .put("overview", item.overview).put("year", item.year)
                        .put("rating", item.rating).put("genres", item.genres));
                for (int i = 0; i < out.length(); i++) withNew.put(out.get(i));
                out = withNew;
            }
            prefs(c).edit().putString(KEY_FAVS, out.toString()).apply();
            return !removed;
        } catch (Exception e) {
            return false;
        }
    }

    static List<CatalogItem> favorites(Context c) {
        List<CatalogItem> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs(c).getString(KEY_FAVS, "[]"));
            for (int i = 0; i < arr.length(); i++) out.add(fromStored(arr.getJSONObject(i)));
        } catch (Exception ignored) {
        }
        return out;
    }

    /** Calidad elegida a mano: altura del vídeo (1080, 720…) o 0 = automática. */
    static int quality(Context c) {
        return prefs(c).getInt("quality", 0);
    }

    static void setQuality(Context c, int height) {
        prefs(c).edit().putInt("quality", height).apply();
    }

    static String language(Context c) {
        return prefs(c).getString(KEY_LANG, "Latino");
    }

    static void setLanguage(Context c, String lang) {
        prefs(c).edit().putString(KEY_LANG, lang).apply();
    }
}
