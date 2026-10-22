package cl.sopa1d.app;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;

/** Peticiones HTTP simples y bloqueantes (siempre desde un hilo de fondo). */
final class Http {

    static final String UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36";

    /** Respuesta ya leída: URL final tras redirecciones y cuerpo como texto. */
    static final class Response {
        final int code;
        final String finalUrl;
        final String body;

        Response(int code, String finalUrl, String body) {
            this.code = code;
            this.finalUrl = finalUrl;
            this.body = body;
        }
    }

    private Http() {}

    static String getString(String url) throws IOException {
        Response r = get(url, null, 10000);
        if (r.code < 200 || r.code >= 300) throw new IOException("HTTP " + r.code + " en " + url);
        return r.body;
    }

    /** POST de un cuerpo JSON (AniList); devuelve el cuerpo de la respuesta. */
    static String postJson(String url, String json) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            c.setDoOutput(true);
            c.setRequestMethod("POST");
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("Accept", "application/json");
            try (java.io.OutputStream out = c.getOutputStream()) {
                out.write(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String body = in == null ? "" : readAll(in);
            if (code < 200 || code >= 300) throw new IOException("HTTP " + code + " en " + url);
            return body;
        } finally {
            c.disconnect();
        }
    }

    /**
     * GET con redirecciones manuales: HttpURLConnection no sigue las que
     * cambian de http a https, y los reproductores rebotan entre dominios.
     */
    static Response get(String url, Map<String, String> headers, int timeoutMs) throws IOException {
        String current = url;
        for (int hop = 0; hop < 6; hop++) {
            HttpURLConnection c = (HttpURLConnection) new URL(current).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept-Language", "es-ES,es;q=0.9");
            if (headers != null) {
                for (Map.Entry<String, String> h : headers.entrySet()) {
                    c.setRequestProperty(h.getKey(), h.getValue());
                }
            }
            try {
                int code = c.getResponseCode();
                if (code >= 300 && code < 400) {
                    String loc = c.getHeaderField("Location");
                    if (loc == null) return new Response(code, current, "");
                    current = new URL(new URL(current), loc).toString();
                    continue;
                }
                InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
                return new Response(code, current, in == null ? "" : readAll(in));
            } finally {
                c.disconnect();
            }
        }
        throw new IOException("Demasiadas redirecciones: " + url);
    }

    private static String readAll(InputStream in) throws IOException {
        try (InputStream is = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = is.read(buf)) != -1) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }
}
