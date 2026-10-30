package cl.sopa1d.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;

/**
 * Control Parental y Modo Niños:
 * Por defecto los canales para adultos están OCULTOS y protegidos.
 * Se pueden activar o desactivar desde el menú de Ajustes mediante un PIN de 4 dígitos.
 */
public final class ParentalControl {

    private static final String PREFS = "sopa1d_parental";
    private static final String KEY_ADULTS_ENABLED = "adults_enabled";
    private static final String KEY_PIN = "parental_pin";
    public static final String DEFAULT_PIN = "0000";

    private ParentalControl() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Estado actual: true si el usuario desbloqueó los canales para adultos con su PIN */
    public static boolean isAdultsEnabled(Context c) {
        return prefs(c).getBoolean(KEY_ADULTS_ENABLED, false);
    }

    public static void setAdultsEnabled(Context c, boolean enabled) {
        prefs(c).edit().putBoolean(KEY_ADULTS_ENABLED, enabled).apply();
    }

    public static String getPin(Context c) {
        return prefs(c).getString(KEY_PIN, DEFAULT_PIN);
    }

    public static void setPin(Context c, String pin) {
        prefs(c).edit().putString(KEY_PIN, pin).apply();
    }

    public static boolean checkPin(Context c, String enteredPin) {
        return getPin(c).equals(enteredPin != null ? enteredPin.trim() : "");
    }

    /** Comprueba si una categoría o nombre de canal corresponde a contenido adulto */
    public static boolean isAdultChannel(String category, String name) {
        if ("Adultos".equalsIgnoreCase(category)) return true;
        if (name == null) return false;
        String n = name.toLowerCase(Locale.ROOT);
        return n.contains("playboy") || n.contains("venus") || n.contains("hustler")
                || n.contains("penthouse") || n.contains("brazzers") || n.contains("redlight")
                || n.contains("sextreme") || n.contains("hentai") || n.contains("adult");
    }
}
