package cl.sopa1d.app;

import android.net.Uri;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Da por terminado un segmento cuando la conexión se corta a pocos bytes del
 * final, en vez de fallar.
 *
 * Los servidores Astra (http://IP:8000/play/..., p. ej. TNT Señal 2) cierran
 * la conexión al terminar cada respuesta y en ese cierre se pierden los
 * últimos 3-25 bytes: HttpURLConnection lanza "unexpected end of stream".
 * ExoPlayer reintenta pidiendo esos bytes con Range, pero Astra ignora Range
 * y devuelve el segmento entero, lo que acaba en READ_POSITION_OUT_OF_RANGE y
 * un corte de varios segundos. Perder la cola es inofensivo: es un fragmento
 * del último paquete TS (188 bytes), que el extractor descarta igual.
 */
@OptIn(markerClass = UnstableApi.class)
final class TolerantDataSource implements DataSource {

    private static final String TAG = "Sopa1D";

    /** Margen de cola que se acepta perder: unos pocos paquetes TS. */
    private static final long TAIL_TOLERANCE_BYTES = 4096;

    static final class Factory implements DataSource.Factory {
        private final DataSource.Factory upstream;

        Factory(DataSource.Factory upstream) {
            this.upstream = upstream;
        }

        @Override
        public DataSource createDataSource() {
            return new TolerantDataSource(upstream.createDataSource());
        }
    }

    private final DataSource upstream;
    private long bytesRemaining = C.LENGTH_UNSET;

    private TolerantDataSource(DataSource upstream) {
        this.upstream = upstream;
    }

    @Override
    public long open(DataSpec dataSpec) throws IOException {
        bytesRemaining = upstream.open(dataSpec);
        return bytesRemaining;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        try {
            int read = upstream.read(buffer, offset, length);
            if (read > 0 && bytesRemaining != C.LENGTH_UNSET) bytesRemaining -= read;
            return read;
        } catch (IOException e) {
            if (bytesRemaining != C.LENGTH_UNSET && bytesRemaining <= TAIL_TOLERANCE_BYTES) {
                Log.d(TAG, "Conexión cortada a " + bytesRemaining + " bytes del final: se da el segmento por completo");
                bytesRemaining = 0;
                return C.RESULT_END_OF_INPUT;
            }
            throw e;
        }
    }

    @Override
    public void addTransferListener(TransferListener transferListener) {
        upstream.addTransferListener(transferListener);
    }

    @Nullable
    @Override
    public Uri getUri() {
        return upstream.getUri();
    }

    @Override
    public Map<String, List<String>> getResponseHeaders() {
        return upstream.getResponseHeaders();
    }

    @Override
    public void close() throws IOException {
        upstream.close();
    }
}
