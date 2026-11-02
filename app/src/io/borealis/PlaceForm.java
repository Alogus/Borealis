package io.borealis;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.KeyEvent;


/**
 * The two fields of the weather location, in Borealis's own look (the welcome and Settings share it): Country, a
 * and City, each asked with Borealis's own keyboard (TextEntry) that suggests matches as the letters come. It
 * keeps the choice and draws itself; the screen that holds it gives it the keys and calls draw.
 * Designed in 1920 × 1080 units, scaled by `k`.
 */
final class PlaceForm {
    static final float W = 760, H = 84, GAP = 20, HEIGHT = 2 * H + GAP;
    private static final int COUNTRY = 0, CITY = 1;

    private final Context ctx;
    private final Runnable redraw;
    private final Click click;
    private final Spring[] pop = {new Spring(0.28f, 0.85f, 0), new Spring(0.28f, 0.85f, 0)};

    private String countryCode = "", countryName = "", cityName = "", cityLabel = "", symbol = "", temp = "", note = "";
    private double lat, lon;
    private boolean cityChosen, okDown;
    private int field;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF(), tmp = new RectF();
    private Paint label, value, hint, noteP;
    private float paintK;

    PlaceForm(Context c, Runnable redraw) {
        this.ctx = c;
        this.redraw = redraw;
        click = new Click(c);
        // What was chosen before (Settings): the country and the city. A city saved without a country is asked again.
        if (Prefs.placeChosen() && !Prefs.placeCountry().isEmpty() && !Prefs.city().isEmpty()) {
            countryCode = Prefs.placeCountry();
            countryName = Places.countryName(countryCode);
            cityName = Prefs.city();
            cityLabel = cityName;
            try {
                lat = Double.parseDouble(Prefs.latitude());
                lon = Double.parseDouble(Prefs.longitude());
                cityChosen = true;
                loadWeather();
            } catch (NumberFormatException ignored) {}
        }
    }

    // MARK: - What the screen asks

    boolean complete() { return !countryCode.isEmpty() && cityChosen; }

    /** The city for the picture (null = the sample cities), and its weather once known. */
    String widgetCity() { return cityChosen ? cityName : null; }
    String widgetSymbol() { return symbol; }
    String widgetTemp() { return temp; }

    void save() {
        if (!complete()) return;
        Prefs.setPlace(countryCode, cityName, lat, lon);
        HomeData.get(ctx).settingsChanged();
    }

    void release() { click.release(); }

    void focusFirst() { field = COUNTRY; }

    void focusLast() { field = CITY; }

    // MARK: - Keys

    /** True when the form used the key. At the edges (Up on Country, Down on City) it is false: the screen moves on. */
    boolean onKey(KeyEvent e) {
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        int code = e.getKeyCode();
        boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
        if (ok) {
            if (down) {
                if (e.getRepeatCount() == 0) okDown = true;
            } else {
                if (okDown && !e.isCanceled()) activate();
                okDown = false;
            }
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_UP && field > 0) {
            if (down) { field--; click.move(); redraw.run(); }
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_DOWN && field < CITY) {
            if (down) { field++; click.move(); redraw.run(); }
            return true;
        }
        return false;
    }

    private void activate() {
        click.open();
        if (field == COUNTRY) {
            askCountry();
        } else if (countryCode.isEmpty()) {
            note = Ui.tr("Escolha primeiro o país.", "Pick the country first.", "Elige primero el país.");
            redraw.run();
        } else {
            askCity();
        }
    }

    /** Borealis's keyboard: the countries that match what is typed, from the first letter. */
    private void askCountry() {
        TextEntry.Request r = new TextEntry.Request();
        r.title = Ui.tr("Em que país você está?", "Which country are you in?", "¿En qué país estás?");
        r.hint = Ui.tr("Digite o país", "Type the country", "Escribe el país");
        r.mustPick = true;
        r.source = TextEntry.countries();
        r.done = (text, o) -> {
            String[] c = (String[]) o.tag;
            if (!c[0].equals(countryCode)) {   // another country: the city has to be chosen again
                countryCode = c[0];
                countryName = c[1];
                cityChosen = false;
                cityName = "";
                cityLabel = "";
                symbol = "";
                temp = "";
            }
            note = "";
            field = CITY;
            redraw.run();
            askCity();   // the next thing to ask
        };
        TextEntry.show(ctx, r);
    }

    private void askCity() {
        TextEntry.Request r = new TextEntry.Request();
        r.title = Ui.tr("Qual é a sua cidade?", "What is your city?", "¿Cuál es tu ciudad?");
        r.hint = Ui.tr("Digite a cidade", "Type the city", "Escribe la ciudad");
        r.mustPick = true;
        r.slow = true;
        r.source = TextEntry.cities(countryCode);
        r.done = (text, o) -> {
            Places.Match m = (Places.Match) o.tag;
            cityName = m.name;
            cityLabel = m.label;
            lat = m.lat;
            lon = m.lon;
            cityChosen = true;
            symbol = "";
            temp = "";
            note = "";
            field = CITY;
            loadWeather();
            redraw.run();
        };
        TextEntry.show(ctx, r);
    }

    private void loadWeather() {
        final double la = lat, lo = lon;
        Places.weatherNow(la, lo, (s, t) -> {
            if (cityChosen && lat == la && lon == lo) {
                symbol = s;
                temp = t;
                redraw.run();
            }
        });
    }

    // MARK: - Drawing

    private void paints(float k) {
        if (label != null && paintK == k) return;
        paintK = k;
        label = Ui.text(Ui.regular, 27 * k, 0xCCFFFFFF);
        value = Ui.text(Ui.medium, 29 * k, 0xFFFFFFFF);
        value.setTextAlign(Paint.Align.RIGHT);
        hint = Ui.text(Ui.regular, 27 * k, 0x80FFFFFF);
        hint.setTextAlign(Paint.Align.RIGHT);
        noteP = Ui.text(Ui.regular, 25 * k, Brand.PEACH);
        noteP.setTextAlign(Paint.Align.CENTER);
    }

    private Shader gradient(RectF b) {
        return new LinearGradient(b.left, 0, b.right, 0, Brand.accents(), null, Shader.TileMode.CLAMP);
    }

    private void ring(Canvas c, RectF b, float radius, float width, float alpha) {
        float half = width / 2;
        tmp.set(b.left + half, b.top + half, b.right - half, b.bottom - half);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(width);
        line.setShader(gradient(tmp));
        line.setColor(Ui.alpha(0xFFFFFFFF, Math.min(1, alpha)));
        c.drawRoundRect(tmp, Math.max(0, radius - half), Math.max(0, radius - half), line);
        line.setShader(null);
        line.setStyle(Paint.Style.FILL);
    }

    private String fit(String text, Paint p, float width) {
        return TextUtils.ellipsize(text, new TextPaint(p), Math.max(10, width), TextUtils.TruncateAt.END).toString();
    }

    /** Draws the two fields (the first one's top at `top`, centered on `cx`). `focused`: the form has the focus. True while it moves. */
    boolean draw(Canvas c, float k, float cx, float top, boolean focused, float dt) {
        paints(k);
        boolean moving = false;
        for (int i = 0; i < 2; i++) {
            pop[i].target = focused && field == i ? 1 : 0;
            moving |= pop[i].step(dt);
            float f = Ui.clamp(pop[i].value), sc = 1 + 0.02f * f;
            float y = top + i * (H + GAP), cy = (y + H / 2) * k;
            float w = W * k * sc, h = H * k * sc;
            r.set(cx * k - w / 2, cy - h / 2, cx * k + w / 2, cy + h / 2);
            float rr = 24 * k;
            fill.setShader(null);
            fill.setColor(Ui.alpha(0xFFFFFFFF, 0.10f + 0.12f * f));
            c.drawRoundRect(r, rr, rr, fill);
            if (f > 0.01f) ring(c, r, rr, 3.5f * k, f);
            float pad = 32 * k;
            String name = i == COUNTRY ? Ui.tr("País", "Country", "País") : Ui.tr("Cidade", "City", "Ciudad");
            label.setTextAlign(Paint.Align.LEFT);
            c.drawText(name, r.left + pad, r.centerY() + Ui.halfCap(label), label);
            float right = r.right - pad - (i == COUNTRY ? 46 * k : 0);
            String text;
            boolean empty;
            if (i == COUNTRY) {
                empty = countryName.isEmpty();
                text = empty ? Ui.tr("Escolher", "Choose", "Elegir") : countryName;
            } else if (cityChosen) {
                empty = false;
                text = cityLabel;
            } else {
                empty = true;
                text = countryCode.isEmpty() ? Ui.tr("Primeiro o país", "Country first", "Primero el país") : Ui.tr("Aperte OK e digite", "Press OK and type", "Pulsa OK y escribe");
            }
            Paint p = empty ? hint : value;
            float avail = right - (r.left + pad + label.measureText(name) + 28 * k);
            c.drawText(fit(text, p, avail), right, r.centerY() + Ui.halfCap(p), p);
            if (i == COUNTRY) Symbols.draw(ctx, c, "chevron_down", r.right - pad - 12 * k, r.centerY(), 38 * k, 0xCCFFFFFF);
        }
        if (!note.isEmpty()) c.drawText(note, cx * k, (top + HEIGHT + 44) * k, noteP);
        return moving;
    }
}
