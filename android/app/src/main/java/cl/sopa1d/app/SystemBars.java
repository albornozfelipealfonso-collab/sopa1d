package cl.sopa1d.app;

import android.app.Activity;
import android.app.UiModeManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.view.View;
import android.view.ViewGroup;

/**
 * Desde Android 15 (targetSdk 35) las apps se dibujan detrás de las barras
 * del sistema. En celulares con barra de 3 botones, esa barra tapaba los
 * íconos de abajo y los toques le llegaban a Android, no a la app. Aquí se
 * deja a la pantalla el espacio que ocupan las barras.
 */
final class SystemBars {

    private SystemBars() {}

    /** TV de verdad (Android TV / Google TV / TV box): control remoto, sin táctil. */
    static boolean isTv(Context c) {
        UiModeManager ui = (UiModeManager) c.getSystemService(Context.UI_MODE_SERVICE);
        return (ui != null && ui.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION)
                || c.getPackageManager().hasSystemFeature(PackageManager.FEATURE_LEANBACK);
    }

    /** Aplica como relleno el espacio de las barras del sistema y del notch. */
    static void pad(Activity a) {
        ViewGroup content = a.findViewById(android.R.id.content);
        final View root = content.getChildCount() > 0 ? content.getChildAt(0) : content;
        final int l = root.getPaddingLeft(), t = root.getPaddingTop(),
                r = root.getPaddingRight(), b = root.getPaddingBottom();
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(l + insets.getSystemWindowInsetLeft(), t + insets.getSystemWindowInsetTop(),
                    r + insets.getSystemWindowInsetRight(), b + insets.getSystemWindowInsetBottom());
            return insets;
        });
        root.requestApplyInsets();
    }
}
