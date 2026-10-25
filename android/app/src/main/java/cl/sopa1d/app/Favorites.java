package cl.sopa1d.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Canales favoritos: una lista corta propia en vez de recorrer más de 6.000.
 * Se identifican por su URL (el id del JSON se repite entre listas) y se
 * guarda el canal completo para mostrarlo aunque cambie la lista general.
 */
final class Favorites {

    private static final String PREFS = "sopa1d_favorites";
    private static final String KEY = "channels";

    private Favorites() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static JSONArray load(Context c) {
        try {
            return new JSONArray(prefs(c).getString(KEY, "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    /** Todas las URLs favoritas de una vez: para filtrar miles de canales sin releer la lista por cada uno. */
    static java.util.Set<String> urls(Context c) {
        JSONArray arr = load(c);
        java.util.Set<String> out = new java.util.HashSet<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null) out.add(o.optString("url"));
        }
        return out;
    }

    static boolean contains(Context c, String url) {
        JSONArray arr = load(c);
        for (int i = 0; i < arr.length(); i++) {
            if (url.equals(arr.optJSONObject(i).optString("url"))) return true;
        }
        return false;
    }

    /** Agrega o quita. Devuelve true si quedó como favorito. */
    static boolean toggle(Context c, String name, String url, String logo, String category, String country) {
        JSONArray old = load(c);
        JSONArray out = new JSONArray();
        boolean removed = false;
        for (int i = 0; i < old.length(); i++) {
            JSONObject o = old.optJSONObject(i);
            if (url.equals(o.optString("url"))) removed = true; else out.put(o);
        }
        if (!removed) {
            try {
                out.put(new JSONObject().put("name", name).put("url", url)
                        .put("logo", logo == null ? "" : logo).put("category", category == null ? "" : category)
                        .put("country", country == null ? "" : country));
            } catch (Exception ignored) {
            }
        }
        prefs(c).edit().putString(KEY, out.toString()).apply();
        return !removed;
    }

    /**
     * Si la lista de canales cambió la señal de un canal (p. ej. se
     * corrigieron las de TNT Premium Chile 1 y TNT Básico, que venían
     * cruzadas), el favorito guardado sigue apuntando a la vieja: se
     * actualiza por nombre. nameToUrl: nombre exacto → URL actual.
     */
    static void reconcile(Context c, java.util.Map<String, String> nameToUrl) {
        JSONArray arr = load(c);
        boolean changed = false;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            String now = nameToUrl.get(o.optString("name"));
            if (now != null && !now.equals(o.optString("url"))) {
                try { o.put("url", now); changed = true; } catch (Exception ignored) {}
            }
        }
        if (changed) prefs(c).edit().putString(KEY, arr.toString()).apply();
    }

    /** Favoritos como canales de la pantalla de inicio (mismo formato que los demás). */
    static List<CatalogItem> asItems(Context c) {
        List<CatalogItem> out = new ArrayList<>();
        JSONArray arr = load(c);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            String logo = o.optString("logo");
            CatalogItem ch = new CatalogItem("live", o.optString("url"), o.optString("name"), "", 0,
                    o.optString("country"), logo.isEmpty() ? null : logo, null);
            ch.genres = o.optString("category");
            out.add(ch);
        }
        return out;
    }
}
