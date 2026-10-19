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
 * (son ~2 MB; los usa la revisión diaria de canales).
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
}
