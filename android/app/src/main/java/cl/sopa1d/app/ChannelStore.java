package cl.sopa1d.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Lista de canales compartida entre la WebView y el reproductor.
 *
 * Va en memoria estática y no en el Intent a propósito: la lista completa
 * ronda los 6.800 canales y excede el límite de una transacción Binder.
 */
public final class ChannelStore {

    private static List<Channel> channels = Collections.emptyList();

    private ChannelStore() {}

    public static synchronized void set(String json) {
        List<Channel> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String url = o.optString("url", "");
                if (url.isEmpty()) continue;
                Channel ch = new Channel(
                        o.optString("id", String.valueOf(i)),
                        o.optString("name", "Canal " + (i + 1)),
                        url,
                        o.optString("logo", ""),
                        o.optString("category", ""));
                ch.country = o.optString("country", "");
                out.add(ch);
            }
        } catch (Exception ignored) {
            // Una lista corrupta no debe tumbar la app: se queda con lo que haya.
        }
        channels = out;
    }

    public static synchronized void setChannels(List<Channel> list) {
        if (list == null) return;
        channels = new ArrayList<>(list);
    }

    public static synchronized List<Channel> get() {
        return channels;
    }

    public static synchronized int size() {
        return channels.size();
    }

    public static synchronized Channel at(int i) {
        if (channels.isEmpty()) return null;
        int n = channels.size();
        return channels.get(((i % n) + n) % n); // circular en ambos sentidos
    }
}
