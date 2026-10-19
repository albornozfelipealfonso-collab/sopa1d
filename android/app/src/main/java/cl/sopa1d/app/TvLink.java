package cl.sopa1d.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Log;
import android.widget.EditText;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * "Ver en la TV": el celular le pasa a la app Sopa1D de la TV qué
 * reproducir, por el wifi de la casa.
 *
 * El Chromecast no sirve aquí: no puede mandar la cabecera Referer que exigen
 * varios servidores (Vimeus responde 403 sin ella) y además cargaría el vídeo
 * fuera del escudo anti-publicidad. En cambio, la TV recibe solo "qué título y
 * en qué minuto", lo resuelve ella misma y lo reproduce en su reproductor
 * nativo, manejable con el control.
 *
 * La TV escucha en el puerto {@link #PORT} y se anuncia por mDNS (NSD) como
 * "_sopa1d._tcp". Si la red bloquea mDNS, se puede escribir la IP que la TV
 * muestra en su menú.
 */
final class TvLink {

    private static final String TAG = "Sopa1D";
    static final int PORT = 8765;
    private static final String SERVICE_TYPE = "_sopa1d._tcp.";

    private static ServerSocket server;
    private static NsdManager.RegistrationListener registration;

    private TvLink() {}

    // ============================================================ TV (recibe)

    /** Arranca el receptor (una sola vez por proceso). Devuelve "IP:puerto" o null. */
    static synchronized String startReceiver(Context ctx) {
        final Context app = ctx.getApplicationContext();
        if (server == null) {
            try {
                server = new ServerSocket(PORT);
            } catch (Exception e) {
                Log.w(TAG, "TvLink: puerto " + PORT + " ocupado", e);
                return null;
            }
            new Thread(() -> acceptLoop(app), "sopa1d-tvlink").start();
            register(app);
        }
        String ip = localIp(app);
        return ip == null ? null : ip;
    }

    private static void register(Context ctx) {
        try {
            NsdServiceInfo info = new NsdServiceInfo();
            info.setServiceName("Sopa1D " + Build.MODEL);
            info.setServiceType(SERVICE_TYPE);
            info.setPort(PORT);
            registration = new NsdManager.RegistrationListener() {
                @Override public void onServiceRegistered(NsdServiceInfo s) { Log.i(TAG, "TvLink: anunciado como " + s.getServiceName()); }
                @Override public void onRegistrationFailed(NsdServiceInfo s, int err) { Log.w(TAG, "TvLink: mDNS falló " + err); }
                @Override public void onServiceUnregistered(NsdServiceInfo s) {}
                @Override public void onUnregistrationFailed(NsdServiceInfo s, int err) {}
            };
            NsdManager nsd = (NsdManager) ctx.getSystemService(Context.NSD_SERVICE);
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration);
        } catch (Exception e) {
            Log.w(TAG, "TvLink: sin mDNS", e);
        }
    }

    private static void acceptLoop(Context ctx) {
        while (true) {
            try (Socket s = server.accept()) {
                s.setSoTimeout(5000);
                handle(ctx, s);
            } catch (Exception e) {
                Log.w(TAG, "TvLink: conexión fallida", e);
                if (server == null || server.isClosed()) return;
            }
        }
    }

    /** HTTP mínimo: GET /ping y POST /play con un JSON. */
    private static void handle(Context ctx, Socket s) throws Exception {
        // Se lee en bytes: Content-Length cuenta bytes y los títulos con tildes
        // ocupan más bytes que caracteres (leyendo caracteres se quedaba
        // esperando datos que nunca llegaban).
        java.io.InputStream in = new java.io.BufferedInputStream(s.getInputStream());
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int b, last4 = 0;
        while ((b = in.read()) != -1) {
            head.write(b);
            last4 = (last4 << 8) | b;
            if (last4 == 0x0D0A0D0A || head.size() > 16384) break; // fin de cabeceras
        }
        String[] lines = head.toString("UTF-8").split("\r\n");
        String requestLine = lines.length > 0 ? lines[0] : "";
        int length = 0;
        for (String h : lines) {
            if (h.toLowerCase(Locale.ROOT).startsWith("content-length:")) length = Integer.parseInt(h.substring(15).trim());
        }
        byte[] body = new byte[Math.min(length, 64 * 1024)];
        int read = 0;
        while (read < body.length) {
            int n = in.read(body, read, body.length - read);
            if (n < 0) break;
            read += n;
        }

        String reply;
        if (requestLine.startsWith("POST /play")) {
            JSONObject cmd = new JSONObject(new String(body, 0, read, StandardCharsets.UTF_8));
            new Handler(Looper.getMainLooper()).post(() -> play(ctx, cmd));
            reply = "{\"ok\":true,\"tv\":" + JSONObject.quote(Build.MODEL) + "}";
        } else {
            reply = "{\"sopa1d\":true,\"tv\":" + JSONObject.quote(Build.MODEL) + "}";
        }
        byte[] out = reply.getBytes(StandardCharsets.UTF_8);
        OutputStream os = s.getOutputStream();
        os.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: " + out.length
                + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        os.write(out);
        os.flush();
    }

    /** Abre en la TV lo que mandó el celular. */
    private static void play(Context ctx, JSONObject cmd) {
        try {
            Toast.makeText(ctx, "📱 Recibido del celular: " + cmd.optString("title"), Toast.LENGTH_LONG).show();
            if ("live".equals(cmd.optString("type"))) {
                JSONArray list = cmd.optJSONArray("channels");
                ChannelStore.set(list == null ? "[]" : list.toString());
                Intent i = new Intent(ctx, PlayerActivity.class)
                        .putExtra(PlayerActivity.EXTRA_INDEX, cmd.optInt("index", 0))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                ctx.startActivity(i);
                return;
            }
            CatalogItem item = new CatalogItem(
                    "movie".equals(cmd.optString("type")) ? "movie" : "tv",
                    cmd.optString("id"), cmd.optString("title"), cmd.optString("year"), 0,
                    cmd.optString("overview"), emptyToNull(cmd.optString("poster")),
                    emptyToNull(cmd.optString("backdrop")));
            int[] counts = null;
            JSONArray c = cmd.optJSONArray("episodeCounts");
            if (c != null) {
                counts = new int[c.length()];
                for (int k = 0; k < c.length(); k++) counts[k] = c.optInt(k);
            }
            if (cmd.has("language")) WatchHistory.setLanguage(ctx, cmd.optString("language"));
            Intent i = new Intent(ctx, VodPlayerActivity.class)
                    .putExtra(VodPlayerActivity.EXTRA_ITEM, item)
                    .putExtra(VodPlayerActivity.EXTRA_SEASON, cmd.optInt("season", 0))
                    .putExtra(VodPlayerActivity.EXTRA_EPISODE, cmd.optInt("episode", 0))
                    .putExtra(VodPlayerActivity.EXTRA_EPISODE_COUNTS, counts)
                    .putExtra(VodPlayerActivity.EXTRA_POSITION, cmd.optLong("position", -1))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            ctx.startActivity(i);
        } catch (Exception e) {
            Log.w(TAG, "TvLink: orden inválida", e);
        }
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() || "null".equals(s) ? null : s;
    }

    /** IP del equipo en el wifi/ethernet local. */
    static String localIp(Context ctx) {
        try {
            for (java.net.NetworkInterface ni : java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : java.util.Collections.list(ni.getInetAddresses())) {
                    if (a instanceof java.net.Inet4Address && a.isSiteLocalAddress()) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    // ======================================================== celular (envía)

    /** Una TV encontrada en la red. */
    static final class Tv {
        final String name, host;
        final int port;

        Tv(String name, String host, int port) {
            this.name = name;
            this.host = host;
            this.port = port;
        }
    }

    /**
     * Busca TVs con Sopa1D, deja elegir una (o escribir su IP) y le manda la
     * orden. onSent se llama en el hilo principal si la TV la aceptó.
     */
    static void sendWithPicker(final Activity act, final JSONObject command, final Runnable onSent) {
        final Handler ui = new Handler(Looper.getMainLooper());
        final List<Tv> found = new ArrayList<>();
        final NsdManager nsd = (NsdManager) act.getSystemService(Context.NSD_SERVICE);
        final WifiManager.MulticastLock lock = ((WifiManager) act.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE)).createMulticastLock("sopa1d");
        lock.acquire();
        Toast.makeText(act, "📺 Buscando tu TV con Sopa1D…", Toast.LENGTH_SHORT).show();

        final NsdManager.DiscoveryListener discovery = new NsdManager.DiscoveryListener() {
            @Override public void onStartDiscoveryFailed(String t, int e) {}
            @Override public void onStopDiscoveryFailed(String t, int e) {}
            @Override public void onDiscoveryStarted(String t) {}
            @Override public void onDiscoveryStopped(String t) {}
            @Override public void onServiceLost(NsdServiceInfo s) {}

            @Override
            public void onServiceFound(NsdServiceInfo s) {
                nsd.resolveService(s, new NsdManager.ResolveListener() {
                    @Override public void onResolveFailed(NsdServiceInfo s, int e) {}

                    @Override
                    public void onServiceResolved(NsdServiceInfo r) {
                        if (r.getHost() == null) return;
                        synchronized (found) {
                            for (Tv t : found) if (t.host.equals(r.getHost().getHostAddress())) return;
                            found.add(new Tv(r.getServiceName(), r.getHost().getHostAddress(), r.getPort()));
                        }
                    }
                });
            }
        };
        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery);
        } catch (Exception e) {
            Log.w(TAG, "TvLink: no se pudo buscar", e);
        }

        // Se espera un poco a que respondan las TVs y se muestra la lista
        ui.postDelayed(() -> {
            try { nsd.stopServiceDiscovery(discovery); } catch (Exception ignored) {}
            lock.release();
            if (act.isFinishing()) return;
            final List<Tv> tvs;
            synchronized (found) { tvs = new ArrayList<>(found); }
            List<String> labels = new ArrayList<>();
            for (Tv t : tvs) labels.add("📺  " + t.name);
            labels.add("⌨️  Escribir la IP de la TV");
            new AlertDialog.Builder(act)
                    .setTitle(tvs.isEmpty() ? "No encontré tu TV automáticamente" : "¿En qué TV lo veo?")
                    .setItems(labels.toArray(new String[0]), (d, which) -> {
                        if (which < tvs.size()) {
                            Tv t = tvs.get(which);
                            send(act, t.host, t.port, command, onSent);
                        } else {
                            askIp(act, command, onSent);
                        }
                    })
                    .setNegativeButton("Cancelar", null)
                    .show();
        }, 2500);
    }

    private static void askIp(final Activity act, final JSONObject command, final Runnable onSent) {
        final EditText ip = new EditText(act);
        ip.setHint("Ej: 192.168.1.20 (sale en el menú de Sopa1D en la TV)");
        ip.setInputType(InputType.TYPE_CLASS_PHONE);
        ip.setText(act.getSharedPreferences("sopa1d_tvlink", Context.MODE_PRIVATE).getString("ip", ""));
        new AlertDialog.Builder(act)
                .setTitle("IP de la TV")
                .setView(ip)
                .setPositiveButton("Enviar", (d, w) -> {
                    String host = ip.getText().toString().trim();
                    act.getSharedPreferences("sopa1d_tvlink", Context.MODE_PRIVATE).edit().putString("ip", host).apply();
                    send(act, host, PORT, command, onSent);
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private static void send(final Activity act, final String host, final int port,
                             final JSONObject command, final Runnable onSent) {
        new Thread(() -> {
            String error = null;
            try {
                HttpURLConnection c = (HttpURLConnection) new URL("http://" + host + ":" + port + "/play").openConnection();
                c.setConnectTimeout(4000);
                c.setReadTimeout(6000);
                c.setDoOutput(true);
                c.setRequestMethod("POST");
                c.setRequestProperty("Content-Type", "application/json");
                byte[] body = command.toString().getBytes(StandardCharsets.UTF_8);
                c.setFixedLengthStreamingMode(body.length);
                try (OutputStream os = c.getOutputStream()) {
                    os.write(body);
                }
                if (c.getResponseCode() != 200) error = "la TV respondió " + c.getResponseCode();
                c.disconnect();
            } catch (Exception e) {
                error = "no se pudo conectar (" + e.getClass().getSimpleName() + ")";
            }
            final String err = error;
            act.runOnUiThread(() -> {
                if (err == null) {
                    Toast.makeText(act, "✅ Reproduciendo en la TV", Toast.LENGTH_LONG).show();
                    if (onSent != null) onSent.run();
                } else {
                    Toast.makeText(act, "⚠️ No llegó a la TV: " + err + ". ¿Está Sopa1D abierta en la TV y en el mismo wifi?",
                            Toast.LENGTH_LONG).show();
                }
            });
        }, "sopa1d-tvlink-send").start();
    }

    /** Orden para una película o episodio. position < 0: desde donde quedó. */
    static JSONObject vodCommand(CatalogItem item, int season, int episode, int[] episodeCounts,
                                 long position, String language) {
        try {
            JSONObject o = new JSONObject()
                    .put("type", item.type).put("id", item.id).put("title", item.title)
                    .put("year", item.year).put("overview", item.overview)
                    .put("poster", item.poster == null ? "" : item.poster)
                    .put("backdrop", item.backdrop == null ? "" : item.backdrop)
                    .put("season", season).put("episode", episode).put("position", position);
            if (language != null) o.put("language", language);
            if (episodeCounts != null) {
                JSONArray c = new JSONArray();
                for (int n : episodeCounts) c.put(n);
                o.put("episodeCounts", c);
            }
            return o;
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    /** Orden para un canal en vivo, con su lista para zapear en la TV. */
    static JSONObject liveCommand(List<Channel> channels, int index) {
        try {
            // Se manda una ventana de canales alrededor: la lista completa
            // (6.800) es demasiado para una orden.
            int from = Math.max(0, index - 100), to = Math.min(channels.size(), index + 100);
            JSONArray arr = new JSONArray();
            for (int k = from; k < to; k++) {
                Channel ch = channels.get(k);
                arr.put(new JSONObject().put("id", ch.id).put("name", ch.name).put("url", ch.url)
                        .put("logo", ch.logo).put("category", ch.category).put("country", ch.country));
            }
            Channel cur = channels.get(index);
            return new JSONObject().put("type", "live").put("title", cur.name)
                    .put("channels", arr).put("index", index - from);
        } catch (Exception e) {
            return new JSONObject();
        }
    }
}
