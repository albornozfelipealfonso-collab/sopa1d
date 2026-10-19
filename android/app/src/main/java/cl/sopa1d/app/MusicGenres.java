package cl.sopa1d.app;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Géneros de la sección Música, deducidos del nombre del canal (la lista no
 * trae el género). El orden importa: un canal va al primer género que calza,
 * así "Vevo Retro Rock" queda en Rock y no en Retro.
 */
final class MusicGenres {

    private static final String[][] GENRES = {
            {"🎸 Rock & Metal", "\\brock|metal|punk|grunge|alternativ|indie\\b"},
            {"📼 Clásicos 80s & 90s", "\\b(80|90)s\\b|80's|90's|ochenta|noventa|retro|oldies|remember|vintage|clásicos|classic hits"},
            {"🔥 Urbano & Reggaetón", "regg?aet|urban|trap\\b|hip ?hop|\\brap\\b|perreo|latin hits"},
            {"💃 Tropical & Cumbia", "tropical|cumbia|salsa|bachata|merengue|vallenato|cuarteto|ranchera|grupera|banda\\b"},
            {"🎤 Pop & Hits", "\\bpop\\b|hits|top ?\\d|mtv|vevo|xite|music ?box|los 40|\\b40\\b|kiss|éxitos|exitos"},
            {"🎻 Clásica & Jazz", "clásica|clasica|classical|opera|ópera|jazz|blues|instrumental|piano|lounge|chill"},
            {"📻 Radios en video", "radio|\\bfm\\b"},
    };

    private static final Pattern[] PATTERNS = new Pattern[GENRES.length];
    static {
        for (int i = 0; i < GENRES.length; i++) {
            PATTERNS[i] = Pattern.compile(GENRES[i][1], Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        }
    }

    private MusicGenres() {}

    /** Género del canal, o "🎶 Más música" si no calza en ninguno. */
    static String of(String name) {
        for (int i = 0; i < GENRES.length; i++) {
            if (PATTERNS[i].matcher(name).find()) return GENRES[i][0];
        }
        return "🎶 Más música";
    }

    /** Canales de música agrupados por género, Rock primero. */
    static Map<String, List<CatalogItem>> group(List<CatalogItem> music) {
        Map<String, List<CatalogItem>> out = new LinkedHashMap<>();
        for (String[] g : GENRES) out.put(g[0], new ArrayList<CatalogItem>());
        out.put("🎶 Más música", new ArrayList<CatalogItem>());
        for (CatalogItem ch : music) out.get(of(ch.title)).add(ch);
        // Sin géneros vacíos
        out.values().removeIf(List::isEmpty);
        return out;
    }
}
