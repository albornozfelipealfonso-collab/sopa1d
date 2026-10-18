package cl.sopa1d.app;

import android.content.Context;
import android.os.Handler;

import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.audio.AudioRendererEventListener;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.video.VideoRendererEventListener;

import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory;

import java.util.ArrayList;

/**
 * Elige el decodificador por separado para audio y vídeo.
 *
 * Audio: siempre FFmpeg primero. Android no trae decodificador MP2 y algunos
 * TV boxes traen uno de fabricante que dice soportarlo y luego falla.
 *
 * Vídeo: por hardware, salvo que se pida software. Decodificar 1080p con
 * FFmpeg funciona siempre pero satura la CPU de un TV box (se ve trabado);
 * el hardware va fluido pero algunos chips no toleran los errores de slice
 * que traen estas señales IPTV. PlayerActivity prueba hardware y, si falla,
 * reconstruye el reproductor con softwareVideo = true.
 */
@OptIn(markerClass = UnstableApi.class)
public class MixedRenderersFactory extends NextRenderersFactory {

    private final boolean softwareVideo;

    public MixedRenderersFactory(Context context, boolean softwareVideo) {
        super(context);
        this.softwareVideo = softwareVideo;
        setEnableDecoderFallback(true);
    }

    @Override
    protected void buildVideoRenderers(Context context, int extensionRendererMode,
                                       MediaCodecSelector mediaCodecSelector,
                                       boolean enableDecoderFallback, Handler eventHandler,
                                       VideoRendererEventListener eventListener,
                                       long allowedVideoJoiningTimeMs,
                                       ArrayList<Renderer> out) {
        super.buildVideoRenderers(context,
                softwareVideo ? EXTENSION_RENDERER_MODE_PREFER : EXTENSION_RENDERER_MODE_ON,
                mediaCodecSelector, enableDecoderFallback, eventHandler, eventListener,
                allowedVideoJoiningTimeMs, out);
    }

    @Override
    protected void buildAudioRenderers(Context context, int extensionRendererMode,
                                       MediaCodecSelector mediaCodecSelector,
                                       boolean enableDecoderFallback, AudioSink audioSink,
                                       Handler eventHandler,
                                       AudioRendererEventListener eventListener,
                                       ArrayList<Renderer> out) {
        super.buildAudioRenderers(context, EXTENSION_RENDERER_MODE_PREFER,
                mediaCodecSelector, enableDecoderFallback, audioSink, eventHandler,
                eventListener, out);
    }
}
