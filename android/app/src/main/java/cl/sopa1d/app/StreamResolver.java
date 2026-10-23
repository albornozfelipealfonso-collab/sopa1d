package cl.sopa1d.app;

import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Consigue el enlace HLS directo de una película o episodio, sin cargar
 * ningún reproductor web.
 *
 * Los reproductores web (MultiEmbed, VidSrc y los de TONPlayer) son los que
 * meten la publicidad: popups al dar play y anuncios encima del vídeo. Aquí
 * se hace lo mismo que server.js en /api/resolve: se piden a TONPlayer los
 * servidores disponibles, se descarga el HTML de cada embed y se saca de él
 * el .m3u8, que luego reproduce ExoPlayer. Probado con películas nuevas y
 * antiguas, series y anime: todos tenían varios servidores en Latino.
 */
final class StreamResolver {

    private static final String TAG = "Sopa1D";
    private static final String TONPLAYER = "https://player.tonplayer.space";

    /** Un servidor de TONPlayer: dirección del embed y su idioma. */
    static final class Server {
        final String host;      // "Streamwish 2"
        final String language;  // "Latino", "Castellano", "Subtitulado"...
        final String embedUrl;

        Server(String host, String language, String embedUrl) {
            this.host = host;
            this.language = language;
            this.embedUrl = embedUrl;
        }

        String label() {
            return (isJapanese(language) ? "Japonés (sin subtítulos)" : language) + " · " + host;
        }
    }

    /** Enlace listo para ExoPlayer, con el Referer que exige su CDN. */
    static final class Stream {
        final Server server;
        final String m3u8;
        final String referer;
        /** Subtítulos aparte que trae el reproductor del servidor: {url, etiqueta}. */
        final List<String[]> subtitles;

        Stream(Server server, String m3u8, String referer, List<String[]> subtitles) {
            this.server = server;
            this.m3u8 = m3u8;
            this.referer = referer;
            this.subtitles = subtitles;
        }
    }

    /**
     * Pistas de subtítulos del reproductor JW del embed:
     * tracks:[{file:"https://…/xxx_spa.vtt",label:"Spanish",kind:"captions"}].
     * Las hay en algunos servidores (Vimeus, Streamwish 2, Filelions 3…) y en
     * otros no; los "Subtitulado" suelen traerlos grabados en la imagen.
     */
    private static final Pattern SUB_TRACK = Pattern.compile(
            "file\\s*:\\s*\"(https?://[^\"]+\\.(?:vtt|srt)[^\"]*)\"\\s*,\\s*label\\s*:\\s*\"([^\"]*)\"");

    private static List<String[]> findSubtitles(String html) {
        List<String[]> out = new ArrayList<>();
        StringBuilder text = new StringBuilder(html);
        int idx = html.indexOf("eval(function(p,a,c,k,e,d)");
        if (idx >= 0) {
            String un = unpack(html.substring(idx, Math.min(html.length(), idx + 60000)));
            if (un != null) text.append(un);
        }
        Matcher m = SUB_TRACK.matcher(text);
        java.util.Set<String> seen = new java.util.HashSet<>();
        while (m.find()) {
            if (seen.add(m.group(1))) out.add(new String[]{m.group(1), m.group(2)});
        }
        return out;
    }

    private static final Pattern M3U8 = Pattern.compile("https?://[^'\"\\s\\\\]+\\.m3u8[^'\"\\s\\\\]*");
    private static final Pattern JS_REDIRECT =
            Pattern.compile("window\\.location\\.href\\s*=\\s*['\"](https?://[^'\"]+)['\"]", Pattern.CASE_INSENSITIVE);
    private static final Pattern PACKED = Pattern.compile(
            "\\}\\('(.*)',\\s*(\\d+),\\s*(\\d+),\\s*'(.*?)'\\.split\\('\\|'\\)", Pattern.DOTALL);
    private static final Pattern BASE64_STRING = Pattern.compile("['\"]([A-Za-z0-9+/=]{44,})['\"]");
    private static final Pattern WORD = Pattern.compile("\\b\\w+\\b");

    private StreamResolver() {}

    /** Servidores de TONPlayer para un título, ordenados por preferencia. */
    static List<Server> listServers(String type, String id, int season, int episode,
                                    String preferredLanguage) throws Exception {
        String path = "tv".equals(type)
                ? "/api/tv/" + id + "/" + season + "/" + episode
                : "/api/movie/" + id;
        // TONPlayer arma la lista consultando a sus fuentes en el momento: la
        // primera consulta de un título puede pasar de 10 s. Se espera más y se
        // reintenta una vez (la segunda suele salir de su caché).
        Http.Response resp;
        try {
            resp = Http.get(TONPLAYER + path, null, 25000);
        } catch (java.io.IOException first) {
            Log.i(TAG, "Resolver: TONPlayer lento (" + first.getMessage() + "), reintentando");
            resp = Http.get(TONPLAYER + path, null, 25000);
        }
        JSONObject json = new JSONObject(resp.body);
        JSONArray arr = json.optJSONArray("servers");
        List<Server> out = new ArrayList<>();
        if (arr == null) return out;

        for (int i = 0; i < arr.length(); i++) {
            JSONObject s = arr.getJSONObject(i);
            String host = s.optString("servidor");
            if (hostPriority(host) < 0) continue; // Voe, Dood... no exponen el .m3u8
            String embed = decodeToken(s.optString("token"));
            if (embed != null) out.add(new Server(host, s.optString("idioma", "?"), embed));
        }

        final String pref = preferredLanguage == null ? "Latino" : preferredLanguage;
        Collections.sort(out, (a, b) -> {
            int la = a.language.equalsIgnoreCase(pref) ? 0 : languageRank(a.language);
            int lb = b.language.equalsIgnoreCase(pref) ? 0 : languageRank(b.language);
            if (la != lb) return la - lb;
            return hostPriority(a.host) - hostPriority(b.host);
        });
        return out;
    }

    /**
     * "Jap" en TONPlayer = audio en japonés sin subtítulos (anime): va al final
     * y no se elige solo mientras haya otra opción.
     */
    static boolean isJapanese(String lang) {
        String l = lang == null ? "" : lang.toLowerCase(Locale.ROOT);
        return (l.contains("jap") || l.contains("raw")) && !l.contains("sub");
    }

    private static int languageRank(String lang) {
        String l = lang.toLowerCase(Locale.ROOT);
        if (isJapanese(lang)) return 9;
        if (l.contains("latino")) return 1;
        if (l.contains("castellano")) return 2;
        if (l.contains("sub")) return 3;
        return 4;
    }

    /** Menor = mejor. -1 = servidor del que no se sabe sacar el vídeo. */
    private static int hostPriority(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        if (h.contains("vimeus") || h.contains("vimeos")) return 1;
        if (h.contains("streamwish")) return 2;
        if (h.contains("filelions")) return 3;
        if (h.contains("vidmoly")) return 4;
        if (h.contains("vidhide") || h.contains("luluvdo")) return 5;
        if (h.contains("uqload")) return 6;
        if (h.contains("voe") || h.contains("dood") || h.equals("ok")
                || h.contains("mp4upload") || h.contains("primeload")) return -1;
        return 9;
    }

    /** El token es JWT-like: la primera parte es JSON en base64url con "u" = embed. */
    private static String decodeToken(String token) {
        try {
            String part = token.split("\\.")[0];
            byte[] raw = Base64.decode(part, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
            return new JSONObject(new String(raw, StandardCharsets.UTF_8)).optString("u", null);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Saca el .m3u8 del embed y comprueba que de verdad responde: algunos
     * servidores entregan un enlace que luego da 502.
     */
    static Stream resolve(Server server) {
        try {
            String url = server.embedUrl;
            for (int hop = 0; hop < 3; hop++) {
                Map<String, String> h = new HashMap<>();
                h.put("Referer", TONPLAYER + "/");
                Http.Response r = Http.get(url, h, 8000);
                url = r.finalUrl;
                String html = r.body;

                Matcher redir = JS_REDIRECT.matcher(html);
                if (html.length() < 2500 && redir.find()) {
                    url = redir.group(1);
                    continue;
                }

                String m3u8 = findM3u8(html);
                if (m3u8 == null) return null;
                String referer = origin(url) + "/";
                if (!isPlayable(m3u8, referer)) {
                    Log.i(TAG, "Resolver: " + server.label() + " entregó un enlace que no responde");
                    return null;
                }
                return new Stream(server, m3u8, referer, findSubtitles(html));
            }
        } catch (Exception e) {
            Log.i(TAG, "Resolver: " + server.label() + " falló: " + e.getMessage());
        }
        return null;
    }

    private static String findM3u8(String html) {
        // 1. Script empaquetado con el "packer" de Dean Edwards (Vimeus,
        //    Streamwish, Filelions, Vidhide, Uqload...)
        int idx = html.indexOf("eval(function(p,a,c,k,e,d)");
        if (idx >= 0) {
            String unpacked = unpack(html.substring(idx, Math.min(html.length(), idx + 60000)));
            if (unpacked != null) {
                Matcher m = M3U8.matcher(unpacked);
                if (m.find()) return m.group();
            }
        }
        // 2. Enlace directo en el HTML (Vidmoly)
        Matcher direct = M3U8.matcher(html);
        if (direct.find()) return direct.group();
        // 3. Cadenas base64 que esconden el enlace
        Matcher b64 = BASE64_STRING.matcher(html);
        while (b64.find()) {
            try {
                String dec = new String(Base64.decode(b64.group(1), Base64.DEFAULT), StandardCharsets.UTF_8);
                Matcher m = M3U8.matcher(dec);
                if (m.find()) return m.group();
            } catch (IllegalArgumentException ignored) {
            }
        }
        return null;
    }

    /** Desempaquetador del "packer": sustituye cada palabra por su entrada del diccionario. */
    static String unpack(String packed) {
        Matcher m = PACKED.matcher(packed);
        if (!m.find()) return null;
        String payload = m.group(1);
        int base = Integer.parseInt(m.group(2));
        String[] dict = m.group(4).split("\\|", -1);

        Matcher w = WORD.matcher(payload);
        StringBuffer out = new StringBuffer(payload.length());
        while (w.find()) {
            String word = w.group();
            int i = decodeBase(word, base);
            String rep = (i >= 0 && i < dict.length && !dict[i].isEmpty()) ? dict[i] : word;
            w.appendReplacement(out, Matcher.quoteReplacement(rep));
        }
        w.appendTail(out);
        return out.toString();
    }

    private static final String DIGITS = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";

    private static int decodeBase(String word, int base) {
        int n = 0;
        for (int i = 0; i < word.length(); i++) {
            int d = DIGITS.indexOf(word.charAt(i));
            if (d < 0 || d >= base) return -1;
            n = n * base + d;
            if (n < 0) return -1; // desbordamiento: no es una clave
        }
        return n;
    }

    private static boolean isPlayable(String m3u8, String referer) {
        try {
            Map<String, String> h = new HashMap<>();
            h.put("Referer", referer);
            h.put("Origin", referer.substring(0, referer.length() - 1));
            Http.Response r = Http.get(m3u8, h, 7000);
            return r.code >= 200 && r.code < 300 && r.body.contains("#EXTM3U");
        } catch (Exception e) {
            return false;
        }
    }

    private static String origin(String url) {
        try {
            URL u = new URL(url);
            return u.getProtocol() + "://" + u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : "");
        } catch (Exception e) {
            return url;
        }
    }
}
