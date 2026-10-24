package cl.sopa1d.app;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * Subtítulos en español de OpenSubtitles para el anime que llega en japonés
 * sin subtítulos (lo mismo que /api/subs de server.js en el PC). TMDB suele
 * juntar todo un anime en la temporada 1 (Re:Zero: 85 capítulos) y
 * OpenSubtitles sigue las temporadas de IMDb (25 + 25 + 16…): si el capítulo
 * no está tal cual, se cuenta en orden por las temporadas de IMDb.
 * Si no hay en español, en inglés.
 */
final class OpenSubs {

    private static final String TAG = "Sopa1D";
    private static final String UA = "TemporaryUserAgent";

    /** Archivo .vtt listo y su idioma ("spa" o "eng"). */
    static final class Result {
        final File file;
        final String lang;

        Result(File file, String lang) {
            this.file = file;
            this.lang = lang;
        }
    }

    private static final Map<String, JSONArray> searchCache = Collections.synchronizedMap(new HashMap<>());
    private static final Map<String, String> imdbCache = Collections.synchronizedMap(new HashMap<>());

    private OpenSubs() {}

    /** Bloqueante (hilo de fondo). null si no hay subtítulos. */
    static Result find(Context ctx, String type, String tmdbId, int season, int episode) {
        try {
            String imdb = imdbId(type, tmdbId);
            if (imdb == null) return null;
            int s = "tv".equals(type) ? season : 0;
            for (String lang : new String[]{"spa", "eng"}) {
                File out = new File(ctx.getCacheDir(), "subs_" + imdb + "_" + s + "_" + episode + "_" + lang + ".vtt");
                if (out.exists() && out.length() > 20) return new Result(out, lang);
                JSONObject pick = pick(search(imdb, s, episode, lang));
                if (pick == null) continue;
                String vtt = toVtt(decode(download(pick.getString("SubDownloadLink"))));
                if (!vtt.contains("-->")) continue;
                try (FileOutputStream fo = new FileOutputStream(out)) {
                    fo.write(vtt.getBytes(StandardCharsets.UTF_8));
                }
                return new Result(out, lang);
            }
        } catch (Exception e) {
            Log.w(TAG, "OpenSubtitles: sin subtítulos", e);
        }
        return null;
    }

    private static String imdbId(String type, String tmdbId) throws Exception {
        String key = type + ":" + tmdbId;
        if (imdbCache.containsKey(key)) return imdbCache.get(key);
        JSONObject d = new JSONObject(Http.getString(Catalog.API + "/details?id=" + tmdbId
                + "&type=" + ("tv".equals(type) ? "tv" : "movie")));
        String imdb = d.optString("imdb_id", "");
        imdb = imdb.matches("tt\\d{5,10}") ? imdb.replaceFirst("^tt0*", "") : null;
        imdbCache.put(key, imdb);
        return imdb;
    }

    private static JSONArray query(String q) {
        JSONArray cached = searchCache.get(q);
        if (cached != null) return cached;
        JSONArray arr = new JSONArray();
        try {
            Map<String, String> h = new HashMap<>();
            h.put("User-Agent", UA);
            Http.Response r = Http.get("https://rest.opensubtitles.org/search/" + q, h, 15000);
            if (r.code == 200 && r.body.trim().startsWith("[")) arr = new JSONArray(r.body);
        } catch (Exception e) {
            return arr;
        }
        searchCache.put(q, arr);
        return arr;
    }

    private static JSONArray search(String imdb, int season, int episode, String lang) {
        if (season <= 0) return query("imdbid-" + imdb + "/sublanguageid-" + lang);
        JSONArray exact = query("episode-" + episode + "/imdbid-" + imdb + "/season-" + season + "/sublanguageid-" + lang);
        if (exact.length() > 0) return exact;
        int e = episode;
        for (int s = season; s < season + 8; s++) {
            JSONArray all = query("imdbid-" + imdb + "/season-" + s + "/sublanguageid-" + lang);
            int count = 0;
            for (int i = 0; i < all.length(); i++) {
                count = Math.max(count, all.optJSONObject(i).optInt("SeriesEpisode", 0));
            }
            if (count == 0) break;
            if (e <= count) {
                return s == season ? new JSONArray()
                        : query("episode-" + e + "/imdbid-" + imdb + "/season-" + s + "/sublanguageid-" + lang);
            }
            e -= count;
        }
        return new JSONArray();
    }

    /** Oficiales de Crunchyroll / de confianza primero, latino antes, nunca traducidos a máquina. */
    private static JSONObject pick(JSONArray list) {
        JSONObject best = null;
        double bestScore = -Double.MAX_VALUE;
        for (int i = 0; i < list.length(); i++) {
            JSONObject x = list.optJSONObject(i);
            if (x == null || x.optString("SubDownloadLink").isEmpty()) continue;
            if (!x.optString("SubFormat").toLowerCase(Locale.ROOT).matches("srt|ssa|ass|vtt")) continue;
            String name = x.optString("SubFileName");
            double score = x.optDouble("SubDownloadsCnt", 0);
            if ("1".equals(x.optString("SubAutoTranslation"))) score -= 1e9;
            if ("1".equals(x.optString("SubFromTrusted"))
                    || x.optString("SubTranslator").toLowerCase(Locale.ROOT).contains("crunchyroll")
                    || name.matches("(?i).*(\\[cr\\]|\\.CR\\.).*")) score += 1e8;
            if (name.matches("(?i).*(\\[lat\\]|latino|es-?419).*")) score += 1e7;
            if ("1".equals(x.optString("SubHearingImpaired"))) score -= 5e6;
            if (score > bestScore) {
                bestScore = score;
                best = x;
            }
        }
        return best;
    }

    private static byte[] download(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(15000);
            c.setReadTimeout(20000);
            c.setRequestProperty("User-Agent", UA);
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            byte[] raw = readAll(c.getInputStream());
            try {
                return readAll(new GZIPInputStream(new ByteArrayInputStream(raw)));
            } catch (IOException notGzip) {
                return raw;
            }
        } finally {
            c.disconnect();
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try (InputStream is = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = is.read(buf)) != -1) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    private static String decode(byte[] b) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(b)).toString();
        } catch (CharacterCodingException e) {
            return new String(b, Charset.forName("windows-1252"));
        }
    }

    private static final Pattern ASS_TIME = Pattern.compile("(\\d+):(\\d+):(\\d+)\\.(\\d+)");

    private static String assTime(String t) {
        Matcher m = ASS_TIME.matcher(t == null ? "" : t);
        if (!m.find()) return "00:00:00.000";
        String h = m.group(1).length() < 2 ? "0" + m.group(1) : m.group(1);
        return h + ":" + m.group(2) + ":" + m.group(3) + "." + (m.group(4) + "00").substring(0, 3);
    }

    /** SRT / ASS → WebVTT. */
    static String toVtt(String text) {
        text = text.replace("\uFEFF", "").replace("\r", "");
        if (text.startsWith("WEBVTT")) return text;
        if (text.toLowerCase(Locale.ROOT).contains("[script info]")) {
            List<String[]> cues = new ArrayList<>();
            boolean inEvents = false;
            List<String> fields = null;
            for (String line : text.split("\n")) {
                if (line.startsWith("[")) {
                    inEvents = line.toLowerCase(Locale.ROOT).startsWith("[events]");
                    continue;
                }
                if (!inEvents) continue;
                if (line.regionMatches(true, 0, "Format:", 0, 7)) {
                    fields = new ArrayList<>();
                    for (String f : line.substring(7).split(",")) fields.add(f.trim().toLowerCase(Locale.ROOT));
                    continue;
                }
                if (fields == null || !line.regionMatches(true, 0, "Dialogue:", 0, 9)) continue;
                int textIdx = fields.indexOf("text");
                String[] parts = line.substring(9).split(",", textIdx + 1);
                if (parts.length <= textIdx) continue;
                String body = parts[textIdx].replaceAll("\\{[^}]*\\}", "").replaceAll("(?i)\\\\N", "\n")
                        .replace("\\h", " ").replace("&", "&amp;").replace("<", "&lt;").trim();
                if (body.isEmpty()) continue;
                cues.add(new String[]{assTime(parts[fields.indexOf("start")]), assTime(parts[fields.indexOf("end")]), body});
            }
            Collections.sort(cues, (a, b) -> a[0].compareTo(b[0]));
            // La misma línea repetida en otra capa (sombra, borde) se vería encimada
            Map<String, String> seen = new HashMap<>();
            StringBuilder sb = new StringBuilder("WEBVTT\n\n");
            for (String[] c : cues) {
                String end = seen.get(c[2]);
                if (end != null && c[0].compareTo(end) <= 0) continue;
                seen.put(c[2], c[1]);
                sb.append(c[0]).append(" --> ").append(c[1]).append('\n').append(c[2]).append("\n\n");
            }
            return sb.toString();
        }
        return "WEBVTT\n\n" + text.replaceAll("(\\d\\d:\\d\\d:\\d\\d),(\\d\\d\\d)", "$1.$2")
                .replaceAll("(?m)^\\d+\n(?=\\d\\d:\\d\\d)", "");
    }
}
