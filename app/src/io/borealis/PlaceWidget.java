package io.borealis;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;

/**
 * The weather card of the "weather location" screens (the news, Settings and the welcome): a pin, a city, a symbol and a
 * temperature. With no city chosen it shows sample cities one after another (each fades in and out); with one, that
 * city, and its real weather once it is known. Drawn for a card of any size (designed at 560 × 250).
 */
final class PlaceWidget {
    private static final String[] CITIES = {"Bogotá", "Madrid", "São Paulo", "Ciudad de México"};
    private static final String[] SYMBOLS = {"partly_cloudy_day", "sunny", "rainy", "cloud"};
    private static final String[] TEMPS = {"19°", "24°", "21°", "22°"};
    private static final float PERIOD = 2.4f;

    private float t;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path pin = new Path();
    private Paint cityPaint, tempPaint;
    private float paintScale;

    void advance(float dt) {
        t += dt;
    }

    /**
     * `city` null = the samples. `symbol` and `temp` are the real weather of the chosen city; either may be empty
     * (then a neutral symbol and no number).
     */
    void draw(Canvas c, Context ctx, RectF card, float alpha, String city, String symbol, String temp) {
        float s = card.width() / 560f;
        if (cityPaint == null || paintScale != s) {
            paintScale = s;
            cityPaint = Ui.text(Ui.medium, 36 * s, 0xFFFFFFFF);
            tempPaint = Ui.text(Ui.displaySemibold, 104 * s, 0xFFFFFFFF);
        }
        int i = (int) (t / PERIOD) % CITIES.length;
        float ph = (t % PERIOD) / PERIOD;
        boolean sample = city == null;
        float fade = !sample ? 1f : ph < 0.12f ? ph / 0.12f : ph > 0.88f ? (1 - ph) / 0.12f : 1f;
        String name = sample ? CITIES[i] : city;
        String sym = sample ? SYMBOLS[i] : symbol == null || symbol.isEmpty() ? "partly_cloudy_day" : symbol;
        String deg = sample ? TEMPS[i] : temp == null ? "" : temp;

        float l = card.left, tp = card.top, w = card.width(), h = card.height();
        fill.setShader(new LinearGradient(l, tp, l + w, tp + h,
                new int[] {Ui.alpha(Brand.LILY, 0.30f * alpha), Ui.alpha(Brand.PEACH, 0.22f * alpha)}, null, Shader.TileMode.CLAMP));
        c.drawRoundRect(card, 36 * s, 36 * s, fill);
        fill.setShader(null);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(Math.max(1f, 2 * s));
        line.setColor(Ui.alpha(0x44FFFFFF, alpha));
        c.drawRoundRect(card, 36 * s, 36 * s, line);
        line.setStyle(Paint.Style.FILL);

        // The pin: a dot with a point, and a dark hole.
        float px = l + 56 * s, py = tp + 60 * s;
        fill.setColor(Ui.alpha(Brand.MELON, alpha));
        c.drawCircle(px, py, 15 * s, fill);
        pin.reset();
        pin.moveTo(px - 12 * s, py + 8 * s);
        pin.lineTo(px + 12 * s, py + 8 * s);
        pin.lineTo(px, py + 30 * s);
        pin.close();
        c.drawPath(pin, fill);
        fill.setColor(Ui.alpha(Brand.INK, alpha));
        c.drawCircle(px, py, 6 * s, fill);

        cityPaint.setTextAlign(Paint.Align.LEFT);
        cityPaint.setAlpha(Math.round(255 * fade * alpha));
        // A long city name is shrunk to fit the card.
        float maxW = w - 92 * s - 40 * s;
        float cw = cityPaint.measureText(name);
        float was = cityPaint.getTextSize();
        if (cw > maxW) cityPaint.setTextSize(was * maxW / cw);
        c.drawText(name, l + 92 * s, py + 12 * s, cityPaint);
        cityPaint.setTextSize(was);

        if (!deg.isEmpty()) {
            tempPaint.setTextAlign(Paint.Align.LEFT);
            tempPaint.setAlpha(Math.round(255 * fade * alpha));
            c.drawText(deg, l + 56 * s, tp + h - 50 * s, tempPaint);
        }
        Symbols.draw(ctx, c, sym, l + w - 110 * s, tp + h / 2 + 20 * s + (float) Math.sin(t * 1.6f) * 4 * s, 120 * s, Ui.alpha(0xFFFFFFFF, fade * alpha));
    }
}
