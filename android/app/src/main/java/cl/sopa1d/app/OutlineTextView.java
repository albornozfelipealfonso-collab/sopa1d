package cl.sopa1d.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.widget.TextView;

/** Texto con contorno: los números gigantes del "Top 10". */
public class OutlineTextView extends TextView {

    private static final int STROKE_COLOR = 0xFFB3B3B3;
    private static final int FILL_COLOR = 0xFF07080C;

    /** setTextColor() pide redibujar: mientras se pinta se ignora o sería un bucle. */
    private boolean drawing;

    public OutlineTextView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    public void invalidate() {
        if (!drawing) super.invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        drawing = true;
        Paint p = getPaint();
        // Primero el contorno grueso, luego el relleno oscuro encima
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(getTextSize() / 22f);
        p.setStrokeJoin(Paint.Join.ROUND);
        setTextColor(STROKE_COLOR);
        super.onDraw(canvas);

        p.setStyle(Paint.Style.FILL);
        setTextColor(FILL_COLOR);
        super.onDraw(canvas);
        drawing = false;
    }
}
