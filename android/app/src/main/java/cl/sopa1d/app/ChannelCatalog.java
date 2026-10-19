package cl.sopa1d.app;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Todos los canales de channels_spa.json, leídos una sola vez por proceso
 * (son ~2 MB y los usan la revisión diaria y el cambio automático de señal).
 */
final class ChannelCatalog {

    private static final String TAG = "Sopa1D";
    private static List<Channel> all;

    private ChannelCatalog() {}

    static synchronized List<Channel> all(Context ctx) {
        if (all != null) return all;
        List<Channel> out = new ArrayList<>();
        try (InputStream in = ctx.getAssets().open("channels_spa.json");
             ByteArrayOutputStream buf = new ByteArrayOutputStream()) {
            byte[] b = new byte[65536];
            int n;
            while ((n = in.read(b)) != -1) buf.write(b, 0, n);
            JSONArray arr = new JSONArray(buf.toString("UTF-8"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String url = o.optString("url");
                if (url.isEmpty()) continue;
                Channel ch = new Channel(o.optString("id"), o.optString("name"), url,
                        o.optString("logo"), o.optString("category"));
                ch.country = o.optString("country");
                ch.assetDead = o.optBoolean("dead");
                out.add(ch);
            }
        } catch (Exception e) {
            Log.w(TAG, "No se pudo leer channels_spa.json", e);
        }
        all = Collections.unmodifiableList(out);
        return all;
    }

    /**
     * Otras señales del mismo canal ("TNT Sports Premium HD (Chile 1)" y
     * "(Señal 2 60fps)" se normalizan igual), vivas, primero las del mismo
     * país. Es lo que usa el cambio automático de señal.
     */
    static List<Channel> alternatives(Context ctx, Channel current) {
        String key = Epg.norm(current.name);
        List<Channel> same = new ArrayList<>(), other = new ArrayList<>();
        if (key.isEmpty()) return same;
        for (Channel ch : all(ctx)) {
            if (ch.url.equals(current.url) || ChannelHealth.isHidden(ch.url, ch.assetDead)) continue;
            if (!key.equals(Epg.norm(ch.name))) continue;
            if (current.country != null && current.country.equals(ch.country)) same.add(ch); else other.add(ch);
        }
        same.addAll(other);
        return same;
    }
}
