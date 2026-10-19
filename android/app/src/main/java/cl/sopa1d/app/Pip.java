package cl.sopa1d.app;

import android.app.Activity;
import android.app.PictureInPictureParams;
import android.app.UiModeManager;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;
import android.util.Rational;

/**
 * Imagen en ventana en el celular: al salir con Inicio mientras se ve algo,
 * el vídeo sigue en un cuadrito flotante. En la TV no aplica.
 */
final class Pip {

    private Pip() {}

    static boolean isTv(Context c) {
        UiModeManager ui = (UiModeManager) c.getSystemService(Context.UI_MODE_SERVICE);
        return ui != null && ui.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION;
    }

    /** Intenta pasar a ventana flotante. Devuelve true si lo hizo. */
    static boolean enter(Activity a, boolean playing) {
        if (!playing || Build.VERSION.SDK_INT < Build.VERSION_CODES.O || isTv(a)) return false;
        if (!a.getPackageManager().hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            return false;
        }
        try {
            return a.enterPictureInPictureMode(new PictureInPictureParams.Builder()
                    .setAspectRatio(new Rational(16, 9))
                    .build());
        } catch (Exception e) {
            return false; // el usuario desactivó PiP para la app en los ajustes
        }
    }

    static boolean active(Activity a) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && a.isInPictureInPictureMode();
    }
}
