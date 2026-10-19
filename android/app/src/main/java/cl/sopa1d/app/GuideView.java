package cl.sopa1d.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Guía en grilla, como la del cable: canales en filas, horas en columnas.
 *
 * Es una sola vista que se dibuja entera (en vez de miles de vistas): con
 * cientos de canales y un día de programación, una grilla de vistas sería
 * lenta en un TV box. Se recorre con las flechas (▲ ▼ canal, ◀ ▶ programa)
 * o deslizando el dedo; OK / toque sobre lo elegido abre el canal.
 */
public class GuideView extends View {

    /** Un canal de la grilla con su programación. */
    static final class Row {
        final Channel channel;
        final int number;
        final boolean favorite;
        final List<Epg.Program> programs;

        Row(Channel channel, int number, boolean favorite, List<Epg.Program> programs) {
            this.channel = channel;
            this.number = number;
            this.favorite = favorite;
            this.programs = programs;
        }
    }

    interface Listener {
        void onSelected(Row row, Epg.Program program);
        void onOpen(int rowIndex);
    }

    private static final long SLOT_MS = 30 * 60_000L;

    private final float d = getResources().getDisplayMetrics().density;
    private final float channelW = 250 * d, rowH = 66 * d, headerH = 44 * d, pxPerMin = 7 * d;

    private final Paint bg = new Paint(), cell = new Paint(), cellNow = new Paint(), cellSel = new Paint(),
            line = new Paint(), nowLine = new Paint(), border = new Paint();
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG), small = new TextPaint(Paint.ANTI_ALIAS_FLAG),
            selText = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final SimpleDateFormat hm = new SimpleDateFormat("HH:mm", Locale.ROOT);

    private List<Row> rows = Collections.emptyList();
    private long windowStart;
    private float scrollX, scrollY;
    private int selRow;
    private long selTime;
    private Listener listener;
    private final GestureDetector gestures;

    public GuideView(Context c, AttributeSet a) {
        super(c, a);
        setFocusable(true);
        setFocusableInTouchMode(true);
        bg.setColor(0xFF07080C);
        cell.setColor(0xFF1A1D29);
        cellNow.setColor(0xFF262A3A);
        cellSel.setColor(0xFFFFFFFF);
        line.setColor(0xFF07080C);
        line.setStrokeWidth(2 * d);
        nowLine.setColor(0xFFE50914);
        nowLine.setStrokeWidth(3 * d);
        border.setColor(0xFFE50914);
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(3 * d);
        text.setColor(0xFFFFFFFF);
        text.setTextSize(16 * d);
        small.setColor(0xB3FFFFFF);
        small.setTextSize(13 * d);
        selText.setColor(0xFF07080C);
        selText.setTextSize(16 * d);
        selText.setFakeBoldText(true);

        gestures = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                scrollBy(dx, dy);
                return true;
            }

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                tapAt(e.getX(), e.getY());
                return true;
            }
        });
    }

    void setListener(Listener l) {
        listener = l;
    }

    void setRows(List<Row> list) {
        rows = list;
        long now = System.currentTimeMillis();
        windowStart = (now / SLOT_MS) * SLOT_MS - SLOT_MS; // media hora antes
        scrollX = 0;
        scrollY = 0;
        selRow = 0;
        selTime = now;
        ensureVisible();
        notifySelection();
        invalidate();
    }

    // ------------------------------------------------------------ dibujo

    private float xOf(long t) {
        return channelW + (t - windowStart) / 60000f * pxPerMin - scrollX;
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        c.drawRect(0, 0, w, h, bg);
        long now = System.currentTimeMillis();

        int first = Math.max(0, (int) (scrollY / rowH));
        int last = Math.min(rows.size() - 1, (int) ((scrollY + h - headerH) / rowH) + 1);
        for (int i = first; i <= last; i++) {
            Row row = rows.get(i);
            float top = headerH + i * rowH - scrollY;
            c.save();
            c.clipRect(channelW, headerH, w, h);
            for (Epg.Program p : row.programs) {
                float x0 = Math.max(xOf(p.start), channelW), x1 = xOf(p.stop);
                if (x1 < channelW || x0 > w) continue;
                boolean selected = i == selRow && p.start <= selTime && selTime < p.stop;
                boolean airing = p.start <= now && now < p.stop;
                RectF r = new RectF(x0 + 1 * d, top + 2 * d, x1 - 1 * d, top + rowH - 2 * d);
                c.drawRoundRect(r, 6 * d, 6 * d, selected ? cellSel : airing ? cellNow : cell);
                float tx = Math.max(x0, channelW) + 10 * d;
                float avail = r.right - tx - 8 * d;
                if (avail > 20 * d) {
                    TextPaint tp = selected ? selText : text;
                    c.drawText(TextUtils.ellipsize(p.title, tp, avail, TextUtils.TruncateAt.END).toString(),
                            tx, top + rowH / 2 - 2 * d, tp);
                    small.setColor(selected ? 0xFF555555 : 0xB3FFFFFF);
                    c.drawText(TextUtils.ellipsize(hm.format(p.start) + " – " + hm.format(p.stop), small, avail,
                            TextUtils.TruncateAt.END).toString(), tx, top + rowH / 2 + 16 * d, small);
                }
            }
            c.restore();

            // Columna del canal
            c.save();
            c.clipRect(0, headerH, channelW, h);
            c.drawRect(0, top, channelW, top + rowH, i == selRow ? cellNow : bg);
            if (i == selRow) c.drawRect(0, top, 4 * d, top + rowH, nowLine);
            String label = (row.favorite ? "★ " : "") + row.channel.name;
            c.drawText(String.valueOf(row.number), 14 * d, top + rowH / 2 + 6 * d, small);
            c.drawText(TextUtils.ellipsize(label, text, channelW - 64 * d, TextUtils.TruncateAt.END).toString(),
                    54 * d, top + rowH / 2 + 6 * d, text);
            c.drawLine(0, top + rowH, channelW, top + rowH, line);
            c.restore();
        }

        // Cabecera de horas
        c.drawRect(0, 0, w, headerH, bg);
        c.save();
        c.clipRect(channelW, 0, w, headerH);
        for (long t = windowStart; xOf(t) < w; t += SLOT_MS) {
            float x = xOf(t);
            if (x + 80 * d < channelW) continue;
            c.drawText(hm.format(t), x + 8 * d, headerH - 14 * d, small);
            c.drawLine(x, headerH - 10 * d, x, headerH, small);
        }
        c.restore();
        c.drawText("Hoy", 14 * d, headerH - 14 * d, text);

        // Línea de "ahora"
        float nx = xOf(now);
        if (nx > channelW && nx < w) c.drawLine(nx, 0, nx, h, nowLine);
    }

    // -------------------------------------------------------- navegación

    private Epg.Program selected() {
        if (selRow < 0 || selRow >= rows.size()) return null;
        for (Epg.Program p : rows.get(selRow).programs) {
            if (p.start <= selTime && selTime < p.stop) return p;
        }
        return null;
    }

    private void notifySelection() {
        if (listener != null && selRow < rows.size()) listener.onSelected(rows.get(selRow), selected());
    }

    private void ensureVisible() {
        float h = getHeight() - headerH;
        if (h <= 0) return;
        float top = selRow * rowH;
        if (top < scrollY) scrollY = top;
        if (top + rowH > scrollY + h) scrollY = top + rowH - h;

        Epg.Program p = selected();
        float w = getWidth() - channelW;
        if (p != null && w > 0) {
            float x0 = (p.start - windowStart) / 60000f * pxPerMin;
            float x1 = (p.stop - windowStart) / 60000f * pxPerMin;
            if (x0 < scrollX) scrollX = Math.max(0, x0);
            if (Math.min(x1, x0 + w) > scrollX + w) scrollX = Math.min(x1, x0 + w) - w;
        }
        clampScroll();
    }

    private void clampScroll() {
        float maxY = Math.max(0, rows.size() * rowH - (getHeight() - headerH));
        scrollY = Math.max(0, Math.min(scrollY, maxY));
        float maxX = 26 * 60 * pxPerMin;
        scrollX = Math.max(0, Math.min(scrollX, maxX));
    }

    private void scrollBy(float dx, float dy) {
        scrollX += dx;
        scrollY += dy;
        clampScroll();
        invalidate();
    }

    private void move(int dRow, int dProg) {
        if (rows.isEmpty()) return;
        if (dRow != 0) {
            selRow = Math.max(0, Math.min(rows.size() - 1, selRow + dRow));
        }
        if (dProg != 0) {
            List<Epg.Program> list = rows.get(selRow).programs;
            Epg.Program cur = selected();
            int idx = cur == null ? -1 : list.indexOf(cur);
            int next = idx + dProg;
            if (cur == null) {
                // Sin programa en esa hora: el primero en esa dirección
                for (int i = 0; i < list.size(); i++) {
                    if (dProg > 0 && list.get(i).start > selTime) { next = i; break; }
                    if (dProg < 0 && list.get(i).stop <= selTime) next = i;
                }
            }
            if (next >= 0 && next < list.size()) {
                Epg.Program p = list.get(next);
                selTime = Math.max(p.start, Math.min(System.currentTimeMillis(), p.stop - 1));
                if (p.start > System.currentTimeMillis()) selTime = p.start;
            }
        }
        ensureVisible();
        notifySelection();
        invalidate();
    }

    private void tapAt(float x, float y) {
        if (y < headerH) return;
        int row = (int) ((y - headerH + scrollY) / rowH);
        if (row < 0 || row >= rows.size()) return;
        long t = x < channelW ? System.currentTimeMillis()
                : windowStart + (long) ((x - channelW + scrollX) / pxPerMin * 60000f);
        boolean sameCell = row == selRow && selected() != null
                && selected().start <= t && t < selected().stop;
        selRow = row;
        selTime = t;
        notifySelection();
        invalidate();
        // Segundo toque sobre lo mismo (o sobre el nombre del canal): abrir
        if ((sameCell || x < channelW) && listener != null) listener.onOpen(selRow);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        return gestures.onTouchEvent(e) || super.onTouchEvent(e);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP: move(-1, 0); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: move(1, 0); return true;
            case KeyEvent.KEYCODE_DPAD_LEFT: move(0, -1); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT: move(0, 1); return true;
            case KeyEvent.KEYCODE_PAGE_UP:
            case KeyEvent.KEYCODE_CHANNEL_UP: move(-8, 0); return true;
            case KeyEvent.KEYCODE_PAGE_DOWN:
            case KeyEvent.KEYCODE_CHANNEL_DOWN: move(8, 0); return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (listener != null && !rows.isEmpty()) listener.onOpen(selRow);
                return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        ensureVisible();
    }

    /** Filas en el orden de la guía, para el reproductor. */
    List<Channel> channels() {
        List<Channel> out = new ArrayList<>();
        for (Row r : rows) out.add(r.channel);
        return out;
    }
}
