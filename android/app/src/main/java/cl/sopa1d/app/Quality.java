package cl.sopa1d.app;

import android.content.Context;

import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter;

/**
 * Calidad automática según el internet. ExoPlayer ya cambia de calidad cuando
 * el servidor ofrece varias (Streamwish trae 360p/480p/720p/1080p), pero de
 * fábrica arranca optimista y sube rápido: con wifi justo se quedaba cargando.
 * Aquí se arranca prudente, se sube solo con margen y se baja enseguida.
 */
@OptIn(markerClass = UnstableApi.class)
final class Quality {

    /** Estimación inicial: ~2 Mbps alcanza para 480p-720p sin cortes. */
    private static final long INITIAL_BITRATE = 2_000_000;

    private Quality() {}

    static DefaultBandwidthMeter bandwidthMeter(Context c) {
        return new DefaultBandwidthMeter.Builder(c)
                .setInitialBitrateEstimate(INITIAL_BITRATE)
                .build();
    }

    static DefaultTrackSelector trackSelector(Context c) {
        AdaptiveTrackSelection.Factory adaptive = new AdaptiveTrackSelection.Factory(
                15_000,   // subir de calidad solo tras 15 s de buffer holgado
                10_000,   // bajar en cuanto quedan menos de 10 s de buffer
                20_000,
                0.65f);   // usar solo el 65 % del ancho de banda medido
        return new DefaultTrackSelector(c, adaptive);
    }

    /** "1080p", "720p"… según la altura del vídeo. */
    static String label(int height) {
        if (height <= 0) return "";
        if (height >= 1000) return "1080p";
        if (height >= 700) return "720p";
        if (height >= 460) return "480p";
        if (height >= 340) return "360p";
        return height + "p";
    }
}
