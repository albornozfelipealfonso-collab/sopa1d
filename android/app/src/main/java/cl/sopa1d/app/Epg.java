package cl.sopa1d.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * Programación de los canales en vivo (guía EPG).
 *
 * Los canales de channels_spa.json no traen identificador de guía, así que se
 * emparejan por nombre normalizado con las guías XMLTV públicas de
 * iptv-epg.org ("TNT Sports Premium HD (Chile 1)" ↔ "CL - TNT Sports
 * Premium"). Solo coincidencias exactas: emparejar por prefijo metía la
 * programación de Mega en "Mega Ficción". Medido: 558 canales con guía,
 * entre ellos todos los deportivos y abiertos de Chile.
 *
 * Las guías se bajan en segundo plano cada {@link #MAX_AGE_MS} y se guarda
 * solo lo útil (nuestros canales, de 1 h atrás a 30 h adelante) en un JSON
 * compacto, para que abrir la app no espere a la red.
 */
final class Epg {

    private static final String TAG = "Sopa1D";

    /** Guías por país. La de EE.UU. pesa 61 MB y no compensa. */
    private static final String[] COUNTRIES = {"cl", "ar", "mx", "co", "pe", "es"};
    private static final String URL = "https://iptv-epg.org/files/epg-%s.xml.gz";
    private static final long MAX_AGE_MS = 8L * 3600_000;
    private static final long KEEP_PAST_MS = 3600_000;
    private static final long KEEP_AHEAD_MS = 30L * 3600_000;
    private static final String CACHE = "epg.json";

    /** Un programa: horas en milisegundos (UTC) y textos. */
    static final class Program {
        final long start, stop;
        final String title, desc;

        Program(long start, long stop, String title, String desc) {
            this.start = start;
            this.stop = stop;
            this.title = title;
            this.desc = desc;
        }

        float progress(long now) {
            return stop > start ? Math.max(0f, Math.min(1f, (now - start) / (float) (stop - start))) : 0f;
        }

        String hours() {
            SimpleDateFormat f = new SimpleDateFormat("HH:mm", Locale.ROOT);
            return f.format(start) + " – " + f.format(stop);
        }
    }

    /** "cl:tntsportspremium" → programas ordenados por hora. */
    private static volatile Map<String, List<Program>> guide = Collections.emptyMap();
    private static volatile long fetchedAt;
    private static volatile boolean refreshing;
    private static final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private static final Handler ui = new Handler(Looper.getMainLooper());

    private Epg() {}

    // ------------------------------------------------------------ consulta

    private static final Map<String, String> COUNTRY_CODE = new HashMap<>();
    static {
        COUNTRY_CODE.put("Chile", "cl");
        COUNTRY_CODE.put("Argentina", "ar");
        COUNTRY_CODE.put("México", "mx");
        COUNTRY_CODE.put("Colombia", "co");
        COUNTRY_CODE.put("Perú", "pe");
        COUNTRY_CODE.put("España", "es");
    }

    /** Programas de un canal, o lista vacía si no hay guía para él. */
    static List<Program> schedule(String channelName, String country) {
        String n = norm(channelName);
        if (n.isEmpty()) return Collections.emptyList();
        String own = country == null ? null : COUNTRY_CODE.get(country);
        Map<String, List<Program>> g = guide;
        if (own != null) {
            List<Program> p = g.get(own + ":" + n);
            if (p != null) return p;
        }
        // La guía de España solo vale para canales españoles: la señal
        // latina de un mismo canal tiene otra programación y otro horario.
        for (String c : new String[]{"cl", "ar", "mx", "co", "pe"}) {
            List<Program> p = g.get(c + ":" + n);
            if (p != null) return p;
        }
        return Collections.emptyList();
    }

    /** {ahora, después}; cualquiera puede ser null. */
    static Program[] nowNext(String channelName, String country) {
        List<Program> list = schedule(channelName, country);
        long now = System.currentTimeMillis();
        for (int i = 0; i < list.size(); i++) {
            Program p = list.get(i);
            if (p.start <= now && now < p.stop) {
                return new Program[]{p, i + 1 < list.size() ? list.get(i + 1) : null};
            }
            if (p.start > now) return new Program[]{null, p};
        }
        return new Program[]{null, null};
    }

    /** Programas desde el actual en adelante (máximo n). */
    static List<Program> upcoming(String channelName, String country, int n) {
        List<Program> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (Program p : schedule(channelName, country)) {
            if (p.stop <= now || p.stop <= p.start) continue;
            out.add(p);
            if (out.size() >= n) break;
        }
        return out;
    }

    /** Se avisa (en el hilo principal) cuando llega programación nueva. */
    static void addListener(Runnable r) {
        listeners.add(r);
    }

    static void removeListener(Runnable r) {
        listeners.remove(r);
    }

    // --------------------------------------------------------- normalizar

    private static final Pattern ACCENTS = Pattern.compile("\\p{M}+");
    private static final Pattern GUIDE_PREFIX = Pattern.compile("^[a-z]{2} - ");
    private static final Pattern BRACKETS = Pattern.compile("\\(.*?\\)|\\[.*?\\]");
    private static final Pattern SENAL = Pattern.compile("\\bsenal\\b.*$");
    private static final Pattern COUNTRY_WORDS = Pattern.compile(
            "\\b(chile|argentina|mexico|espana|peru|colombia|latinoamerica|latam|latin america|america|internacional|usa|ee ?uu)\\b");
    private static final Pattern NOISE = Pattern.compile(
            "\\b(hd|fhd|uhd|sd|4k|1080p|720p|tv|canal|en vivo|oficial|backup|directa|60fps)\\b");
    private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9]");

    /** "TNT Sports Premium HD (Chile 1)" y "CL - TNT Sports Premium" → "tntsportspremium". */
    static String norm(String s) {
        if (s == null) return "";
        String t = Normalizer.normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFD);
        t = ACCENTS.matcher(t).replaceAll("");
        t = GUIDE_PREFIX.matcher(t).replaceFirst("");
        t = BRACKETS.matcher(t).replaceAll(" ");
        t = SENAL.matcher(t).replaceAll(" ");
        t = COUNTRY_WORDS.matcher(t).replaceAll(" ");
        t = NOISE.matcher(t).replaceAll(" ");
        return NON_ALNUM.matcher(t).replaceAll("");
    }

    // ------------------------------------------------------------- carga

    /**
     * Carga la caché del disco y, si está vieja, baja guías nuevas. Se puede
     * llamar desde cualquier pantalla: solo trabaja la primera vez.
     */
    static void init(final Context ctx) {
        final Context app = ctx.getApplicationContext();
        if (refreshing) return;
        refreshing = true;
        new Thread(() -> {
            try {
                if (guide.isEmpty()) loadCache(app);
                if (System.currentTimeMillis() - fetchedAt > MAX_AGE_MS) download(app);
            } catch (Throwable t) {
                Log.w(TAG, "EPG: no se pudo actualizar", t);
            } finally {
                refreshing = false;
            }
        }, "sopa1d-epg").start();
    }

    private static void loadCache(Context ctx) {
        File f = new File(ctx.getFilesDir(), CACHE);
        if (!f.exists()) return;
        try (InputStream in = new FileInputStream(f)) {
            JSONObject root = new JSONObject(readAll(in));
            Map<String, List<Program>> map = new HashMap<>();
            JSONObject chans = root.getJSONObject("channels");
            for (java.util.Iterator<String> it = chans.keys(); it.hasNext(); ) {
                String key = it.next();
                JSONArray arr = chans.getJSONArray(key);
                List<Program> list = new ArrayList<>(arr.length());
                for (int i = 0; i < arr.length(); i++) {
                    JSONArray p = arr.getJSONArray(i);
                    list.add(new Program(p.getLong(0), p.getLong(1), p.getString(2), p.optString(3, "")));
                }
                map.put(key, list);
            }
            publish(map, root.optLong("fetched"));
            Log.i(TAG, "EPG: caché con " + map.size() + " canales");
        } catch (Exception e) {
            Log.w(TAG, "EPG: caché ilegible", e);
        }
    }

    private static void download(Context ctx) throws Exception {
        Set<String> wanted = ourChannelNames(ctx);
        long now = System.currentTimeMillis();
        Map<String, List<Program>> map = new HashMap<>();
        for (String c : COUNTRIES) {
            try {
                int before = map.size();
                parseCountry(c, wanted, now, map);
                // Se ordena antes de publicar: después la pantalla las lee
                // mientras se bajan los demás países y no se deben tocar.
                for (Map.Entry<String, List<Program>> e : map.entrySet()) {
                    if (e.getKey().startsWith(c + ":")) {
                        Collections.sort(e.getValue(), (a, b) -> Long.compare(a.start, b.start));
                    }
                }
                Log.i(TAG, "EPG: " + c + " +" + (map.size() - before) + " canales");
                // Se publica por país: Chile aparece en segundos sin esperar al resto
                publish(new HashMap<>(map), fetchedAt);
            } catch (Exception e) {
                Log.w(TAG, "EPG: falló la guía de " + c + ": " + e.getMessage());
            }
        }
        if (map.isEmpty()) return;
        publish(map, now);
        saveCache(ctx, map, now);
    }

    /** Nombres normalizados de nuestros canales: lo demás de la guía se descarta. */
    private static Set<String> ourChannelNames(Context ctx) throws Exception {
        Set<String> out = new HashSet<>();
        try (InputStream in = ctx.getAssets().open("channels_spa.json")) {
            JSONArray arr = new JSONArray(readAll(in));
            for (int i = 0; i < arr.length(); i++) out.add(norm(arr.getJSONObject(i).optString("name")));
        }
        return out;
    }

    private static void parseCountry(String code, Set<String> wanted, long now,
                                     Map<String, List<Program>> out) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(String.format(URL, code)).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", Http.UA);
        try (InputStream raw = new BufferedInputStream(c.getInputStream(), 65536);
             InputStream in = new GZIPInputStream(raw, 65536)) {
            XmlPullParser x = XmlPullParserFactory.newInstance().newPullParser();
            x.setInput(new InputStreamReader(in, StandardCharsets.UTF_8));

            Map<String, String> idToKey = new HashMap<>();
            String chanId = null, progChan = null, tag = null;
            long start = 0, stop = 0;
            String title = null, desc = null;
            SimpleDateFormat f = new SimpleDateFormat("yyyyMMddHHmmss Z", Locale.ROOT);

            for (int ev = x.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = x.next()) {
                if (ev == XmlPullParser.START_TAG) {
                    tag = x.getName();
                    if ("channel".equals(tag)) {
                        chanId = x.getAttributeValue(null, "id");
                    } else if ("programme".equals(tag)) {
                        progChan = x.getAttributeValue(null, "channel");
                        try {
                            start = f.parse(x.getAttributeValue(null, "start")).getTime();
                            stop = f.parse(x.getAttributeValue(null, "stop")).getTime();
                        } catch (Exception e) {
                            start = stop = 0;
                        }
                        title = desc = null;
                    }
                } else if (ev == XmlPullParser.TEXT && tag != null) {
                    if ("display-name".equals(tag) && chanId != null && !idToKey.containsKey(chanId)) {
                        String n = norm(x.getText());
                        if (wanted.contains(n)) idToKey.put(chanId, code + ":" + n);
                    } else if ("title".equals(tag) && title == null) {
                        title = x.getText().trim();
                    } else if ("desc".equals(tag) && desc == null) {
                        String d = x.getText().trim();
                        desc = d.length() > 220 ? d.substring(0, 217) + "…" : d;
                    }
                } else if (ev == XmlPullParser.END_TAG) {
                    String name = x.getName();
                    if ("programme".equals(name)) {
                        String key = progChan == null ? null : idToKey.get(progChan);
                        // Hay entradas de duración cero (p. ej. 01:40 – 01:40): solo ensucian
                        if (key != null && title != null && stop > start
                                && stop > now - KEEP_PAST_MS && start < now + KEEP_AHEAD_MS) {
                            List<Program> l = out.get(key);
                            if (l == null) out.put(key, l = new ArrayList<>());
                            l.add(new Program(start, stop, title, desc == null ? "" : desc));
                        }
                        progChan = null;
                    } else if ("channel".equals(name)) {
                        chanId = null;
                    }
                    tag = null;
                }
            }
        } finally {
            c.disconnect();
        }
    }

    private static void saveCache(Context ctx, Map<String, List<Program>> map, long fetched) {
        try {
            JSONObject chans = new JSONObject();
            for (Map.Entry<String, List<Program>> e : map.entrySet()) {
                JSONArray arr = new JSONArray();
                for (Program p : e.getValue()) {
                    arr.put(new JSONArray().put(p.start).put(p.stop).put(p.title).put(p.desc));
                }
                chans.put(e.getKey(), arr);
            }
            String json = new JSONObject().put("fetched", fetched).put("channels", chans).toString();
            File tmp = new File(ctx.getFilesDir(), CACHE + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(json.getBytes(StandardCharsets.UTF_8));
            }
            //noinspection ResultOfMethodCallIgnored
            tmp.renameTo(new File(ctx.getFilesDir(), CACHE));
        } catch (Exception e) {
            Log.w(TAG, "EPG: no se pudo guardar la caché", e);
        }
    }

    private static void publish(Map<String, List<Program>> map, long fetched) {
        guide = map;
        fetchedAt = fetched;
        ui.post(() -> { for (Runnable r : listeners) r.run(); });
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] b = new byte[65536];
        int n;
        while ((n = in.read(b)) != -1) buf.write(b, 0, n);
        return buf.toString("UTF-8");
    }
}
